package dev.jotalac.feature.vault.data

import dev.jotalac.core.utils.ALLOWED_FILE_EXTENSIONS
import dev.jotalac.feature.editor_sidebar.domain.FileNode
import dev.jotalac.feature.notebooks_management.domain.Notebook
import dev.jotalac.feature.vault.crypto.KeystoreCodec
import dev.jotalac.feature.vault.crypto.OpenedVault
import dev.jotalac.feature.vault.crypto.RecoveryKeyCodec
import dev.jotalac.feature.vault.crypto.UnlockedVault
import dev.jotalac.feature.vault.crypto.VaultFileCodec
import dev.jotalac.feature.vault.crypto.VaultFormatException
import dev.jotalac.feature.vault.crypto.VaultKeystoreConflictException
import dev.jotalac.feature.vault.crypto.VaultKeystore
import dev.jotalac.feature.vault.crypto.VaultKeys
import dev.jotalac.feature.vault.crypto.Hex
import dev.jotalac.feature.vault.domain.NoteVault
import dev.jotalac.feature.vault.domain.SecureKeyCache
import dev.jotalac.feature.vault.domain.VaultLockedException
import dev.jotalac.feature.vault.domain.VaultState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.writeString

/**
 * Tracks the vault state of every notebook, keeps the unlocked sessions in memory and builds
 * the encrypted file-tree listing. Notebook folders are re-scanned whenever the notebook list
 * changes (encrypted files may arrive through a git pull; the keystore itself is device-local
 * and never travels - the key id is read from the `.gwv` headers instead).
 */
class VaultManagerImpl(
    /**
     * The notebook list as a deferred flow: resolving the repository eagerly would close a Koin
     * cycle (the notebook repository is built on the git decorator, which is built on the vault).
     */
    private val allNotebooks: () -> Flow<List<Notebook>>,
    private val secureKeyStorage: SecureKeyCache,
    private val fileStore: VaultFileStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Tests disable the notebook-list watcher and call [refreshStates] themselves. */
    private val autoRefresh: Boolean = true,
) : NoteVault {

    private class Entry(
        val notebook: Notebook,
        val state: VaultState,
        val session: OpenedVault?,
    )

    private val entries = kotlinx.coroutines.flow.MutableStateFlow<Map<Long, Entry>>(emptyMap())
    private val refreshMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val states = entries
        .map { current -> current.mapValues { it.value.state } }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    init {
        if (autoRefresh) {
            scope.launch {
                allNotebooks().collect {
                    runCatching { refreshStates() }
                }
            }
        }
    }

    override fun stateOf(notebook: Notebook): VaultState {
        entries.value[notebook.id]?.let { return it.state }
        // unknown notebook: quick on-disk checks are enough to tell Off from Locked (the key id
        // of a keystore-less vault comes from the first `.gwv` header)
        if (VaultLayout.keystoreExists(notebook.directoryPath)) return VaultState.Locked(null, hasLocalKeystore = true)
        if (fileStore.firstKeyIdHexBlocking(notebook.directoryPath) != null) {
            return VaultState.Locked(null, hasLocalKeystore = false)
        }
        return VaultState.Off
    }

    override suspend fun refreshStates() {
        val notebooks = allNotebooks().first()
        refreshMutex.withLock {
            val newEntries = mutableMapOf<Long, Entry>()
            for (notebook in notebooks) {
                newEntries[notebook.id] = buildEntry(notebook)
            }
            entries.value = newEntries
        }
    }

    private suspend fun buildEntry(notebook: Notebook): Entry = withContext(ioDispatcher) {
        val keystore = readKeystore(notebook)
        if (keystore == null && !VaultLayout.keystoreExists(notebook.directoryPath)) {
            // no keystore on this device: the vault may still exist - its key id travels in the
            // `.gwv` headers; with neither of them present the notebook is simply plain
            val keyIdHex = fileStore.firstKeyIdHex(notebook.directoryPath)
                ?: return@withContext Entry(notebook, VaultState.Off, null)
            entries.value[notebook.id]?.session
                ?.takeIf { Hex.encode(it.vault.keyId) == keyIdHex }
                ?.let {
                    return@withContext Entry(notebook, VaultState.Unlocked(keyIdHex, hasLocalKeystore = false), OpenedVault(it.vault))
                }
            return@withContext cachedEntry(notebook, keyIdHex, keystore = null)
                ?: Entry(notebook, VaultState.Locked(keyIdHex, hasLocalKeystore = false), null)
        }
        if (keystore == null) {
            return@withContext Entry(notebook, VaultState.Locked(null, hasLocalKeystore = true), null) // present but unreadable
        }
        val keyIdHex = keystore.keystore.keyIdHex

        // an existing session survives (its master key is independent of the keystore wrapping)
        entries.value[notebook.id]?.session
            ?.takeIf { Hex.encode(it.vault.keyId) == keyIdHex }
            ?.let {
                return@withContext Entry(notebook, VaultState.Unlocked(keyIdHex, hasLocalKeystore = true), OpenedVault(keystore.json, keystore.keystore, it.vault))
            }

        // try the device key cache (auto-unlock)
        cachedEntry(notebook, keyIdHex, keystore)
            ?: Entry(notebook, VaultState.Locked(keyIdHex, hasLocalKeystore = true), null)
    }

    /** Auto-unlock through the device key cache; null when nothing usable is cached. */
    private suspend fun cachedEntry(notebook: Notebook, keyIdHex: String, keystore: KeystoreOnDisk?): Entry? {
        val cached = secureKeyStorage.get(keyIdHex) ?: return null
        return try {
            val vault = UnlockedVault(Hex.decode(keyIdHex), VaultKeys.fromMasterKey(cached))
            Entry(notebook, VaultState.Unlocked(keyIdHex, hasLocalKeystore = keystore != null), OpenedVault(keystore?.json, keystore?.keystore, vault))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null // cached bytes do not fit this key id - ignore them
        }
    }

    private suspend fun setSession(notebook: Notebook, opened: OpenedVault) = withContext(ioDispatcher) {
        val hasKeystore = opened.keystoreJson != null || VaultLayout.keystoreExists(notebook.directoryPath)
        entries.update { current ->
            current + (notebook.id to Entry(notebook, VaultState.Unlocked(Hex.encode(opened.vault.keyId), hasKeystore), opened))
        }
    }

    override suspend fun unlock(notebook: Notebook, passphrase: String): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            val keystore = readKeystore(notebook) ?: throw VaultLockedException("No keystore in '${notebook.name}'")
            val opened = KeystoreCodec.unlock(keystore.json, passphrase)
            secureKeyStorage.store(Hex.encode(opened.vault.keyId), opened.vault.keys.masterKey)
            setSession(notebook, opened)
        }
    }

    override suspend fun unlockWithRecoveryKey(notebook: Notebook, recoveryKey: String): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            val decoded = RecoveryKeyCodec.decode(recoveryKey)
            val keystore = readKeystore(notebook)
            // the key id names the vault: a GW2 key carries it, a legacy GW1 key needs the
            // local keystore or the first `.gwv` header
            val keyIdHex = decoded.keyIdHex
                ?: keystore?.keystore?.keyIdHex
                ?: fileStore.firstKeyIdHex(notebook.directoryPath)
                ?: throw VaultFormatException("The legacy recovery key needs the keystore or at least one encrypted note")
            val vault = UnlockedVault(Hex.decode(keyIdHex), VaultKeys.fromMasterKey(decoded.masterKey))
            // without a keystore the key is proven by decrypting one file; with no files at all
            // a GW2 key is trusted as-is (it names this vault and there is nothing to disprove it)
            if (keystore == null && fileStore.firstKeyIdHex(notebook.directoryPath) != null) {
                verifyMasterKey(notebook, vault)
            }
            secureKeyStorage.store(keyIdHex, decoded.masterKey)
            setSession(notebook, OpenedVault(vault))
        }
    }

    /**
     * Trial-decrypts the first `.gwv` file to prove that [vault] really opens this notebook -
     * used when the recovery key does not go through the keystore (which would check it).
     * @throws VaultFormatException when the key id or the key do not fit the notebook's files
     */
    private suspend fun verifyMasterKey(notebook: Notebook, vault: UnlockedVault) = withContext(ioDispatcher) {
        val candidate = SystemFileSystem.list(Path(notebook.directoryPath))
            .filter { it.name.endsWith(VaultLayout.ENCRYPTED_EXTENSION) }
            .firstOrNull() ?: throw VaultFormatException("No encrypted notes to verify the recovery key against")
        try {
            val encoded = SystemFileSystem.source(candidate).buffered().use { it.readByteArray() }
            VaultFileCodec.decode(encoded, vault, candidate.name.removeSuffix(VaultLayout.ENCRYPTED_EXTENSION))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw VaultFormatException("The recovery key does not open this vault", e)
        }
        Unit
    }

    override suspend fun lock(notebook: Notebook) {
        entries.update { current ->
            val entry = current[notebook.id] ?: return@update current
            if (entry.state is VaultState.Off) return@update current
            // locking only drops the in-memory session - key id and keystore flag survive
            val keyIdHex = (entry.state as? VaultState.Unlocked)?.keyIdHex
                ?: (entry.state as? VaultState.Locked)?.keyIdHex
                ?: entry.session?.let { Hex.encode(it.vault.keyId) }
            val hasKeystore = (entry.state as? VaultState.Unlocked)?.hasLocalKeystore
                ?: (entry.state as? VaultState.Locked)?.hasLocalKeystore
                ?: false
            current + (notebook.id to Entry(notebook, VaultState.Locked(keyIdHex, hasKeystore), null))
        }
    }

    override suspend fun forgetKeyOnDevice(notebook: Notebook) {
        lock(notebook)
        val keyIdHex = (stateOf(notebook) as? VaultState.Locked)?.keyIdHex
            ?: readKeystore(notebook)?.keystore?.keyIdHex
        keyIdHex?.let { secureKeyStorage.remove(it) }
    }

    override suspend fun createVault(notebook: Notebook, passphrase: String): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            val opened = KeystoreCodec.createVault(passphrase)
            val vaultDir = Path(Path(notebook.directoryPath), VaultLayout.KEYSTORE_DIR)
            SystemFileSystem.createDirectories(vaultDir)
            writeKeystore(notebook.directoryPath, requireNotNull(opened.keystoreJson))
            ensureKeystoreIgnored(notebook.directoryPath)
            secureKeyStorage.store(Hex.encode(opened.vault.keyId), opened.vault.keys.masterKey)
            setSession(notebook, opened)
        }
    }

    override suspend fun changePassphrase(notebook: Notebook, currentPassphrase: String, newPassphrase: String): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            val keystore = readKeystore(notebook) ?: throw VaultLockedException("No keystore in '${notebook.name}'")
            val newJson = KeystoreCodec.changePassphrase(keystore.json, currentPassphrase, newPassphrase)
            writeKeystore(notebook.directoryPath, newJson)
            // the master key does not change - re-open to refresh the session's keystore snapshot
            val reopened = KeystoreCodec.unlock(newJson, newPassphrase)
            setSession(notebook, reopened)
        }
    }

    override suspend fun importKeystore(notebook: Notebook, keystoreJson: String): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            val parsed = KeystoreCodec.parseKeystore(keystoreJson)
            if (parsed.version != VaultKeystore.FORMAT_VERSION) {
                throw VaultFormatException("Unsupported keystore version: ${parsed.version}")
            }
            readKeystore(notebook)?.let { existing ->
                if (existing.keystore.keyIdHex != parsed.keyIdHex) {
                    throw VaultKeystoreConflictException()
                }
            }
            val vaultDir = Path(Path(notebook.directoryPath), VaultLayout.KEYSTORE_DIR)
            SystemFileSystem.createDirectories(vaultDir)
            writeKeystore(notebook.directoryPath, keystoreJson)
            ensureKeystoreIgnored(notebook.directoryPath)
            refreshStates()
        }
    }

    override suspend fun localKeystoreJson(notebook: Notebook): Result<String?> = withContext(ioDispatcher) {
        runCatching {
            val path = VaultLayout.keystorePath(Path(notebook.directoryPath))
            if (!SystemFileSystem.exists(path)) return@runCatching null
            SystemFileSystem.source(path).buffered().use { it.readByteArray() }.decodeToString()
        }
    }

    override suspend fun exportRecoveryKey(notebook: Notebook, currentPassphrase: String): Result<String> = withContext(ioDispatcher) {
        runCatching {
            val keystore = readKeystore(notebook) ?: throw VaultLockedException("No keystore in '${notebook.name}'")
            val opened = KeystoreCodec.unlock(keystore.json, currentPassphrase)
            RecoveryKeyCodec.encode(opened.vault.keyId, opened.vault.keys.masterKey)
        }
    }

    override fun recoveryKeyOf(notebook: Notebook): String? {
        val session = entries.value[notebook.id]?.session ?: return null
        return RecoveryKeyCodec.encode(session.vault.keyId, session.vault.keys.masterKey)
    }

    override fun sessionForPath(directoryPath: String): OpenedVault? =
        entries.value.values.firstOrNull { it.notebook.directoryPath == directoryPath }?.session

    // region file tree

    override suspend fun buildFileTree(notebook: Notebook): FileNode.Directory? = withContext(ioDispatcher) {
        val root = Path(notebook.directoryPath)
        if (SystemFileSystem.metadataOrNull(root)?.isDirectory != true) return@withContext null
        val rootPath = notebook.directoryPath
        val session = entries.value[notebook.id]?.session

        val notes = mutableListOf<Pair<String, Path>>() // canonical relPath -> encrypted file
        val orphans = mutableListOf<Path>()
        SystemFileSystem.list(root)
            .filter { it.name.endsWith(VaultLayout.ENCRYPTED_EXTENSION) }
            .forEach { disk ->
                if (session == null) {
                    orphans += disk
                    return@forEach
                }
                try {
                    val encoded = SystemFileSystem.source(disk).buffered().use { it.readByteArray() }
                    val cname = disk.name.removeSuffix(VaultLayout.ENCRYPTED_EXTENSION)
                    val decoded = VaultFileCodec.decode(encoded, session.vault, cname)
                    if (ALLOWED_FILE_EXTENSIONS.any { decoded.relPath.lowercase().endsWith(it) }) {
                        notes += decoded.relPath to disk
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    orphans += disk // wrong key id or corrupted - shown under its encrypted name
                }
            }

        FileNode.Directory(
            name = root.name,
            path = rootPath,
            children = buildNodes(rootPath, "", notes, orphans),
        )
    }

    private fun buildNodes(
        root: String,
        prefix: String,
        notes: List<Pair<String, Path>>,
        orphans: List<Path>,
    ): List<FileNode> {
        val remaining = notes.filter { it.first.startsWith(prefix) }
        val fileNodes = remaining
            .filter { !it.first.removePrefix(prefix).contains('/') }
            .map { (rel, disk) ->
                FileNode.File(
                    name = rel.substringAfterLast('/'),
                    path = VaultLayout.joinVirtual(root, rel),
                    diskPath = disk.toString(),
                )
            }
        val dirNodes = remaining
            .filter { it.first.removePrefix(prefix).contains('/') }
            .groupBy { it.first.removePrefix(prefix).substringBefore('/') }
            .map { (name, subNotes) ->
                val subPrefix = "$prefix$name/"
                FileNode.Directory(
                    name = name,
                    path = VaultLayout.joinVirtual(root, subPrefix.trimEnd('/')),
                    children = buildNodes(root, subPrefix, notes, emptyList()),
                )
            }
        // orphans have no real path - they only make sense at the top level
        val orphanNodes = orphans.map { disk ->
            FileNode.File(name = disk.name, path = disk.toString(), diskPath = disk.toString())
        }

        return (dirNodes + fileNodes + orphanNodes)
            .sortedWith(compareBy<FileNode> { it is FileNode.File }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    // endregion

    // region keystore io

    private class KeystoreOnDisk(val json: String, val keystore: VaultKeystore)

    private suspend fun readKeystore(notebook: Notebook): KeystoreOnDisk? = withContext(ioDispatcher) {
        val path = VaultLayout.keystorePath(Path(notebook.directoryPath))
        if (!SystemFileSystem.exists(path)) return@withContext null
        val json = try {
            SystemFileSystem.source(path).buffered().use { it.readByteArray() }.decodeToString()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return@withContext null
        }
        try {
            KeystoreOnDisk(json, KeystoreCodec.parseKeystore(json))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
    }

    /** Writes the keystore atomically: a truncated keystore would brick the notebook. */
    private suspend fun writeKeystore(root: String, json: String): Unit = withContext(ioDispatcher) {
        val target = VaultLayout.keystorePath(Path(root))
        val tmp = Path(target.parent ?: Path(root), "tmp-keystore")
        try {
            SystemFileSystem.sink(tmp).buffered().use { it.writeString(json) }
            SystemFileSystem.atomicMove(tmp, target)
        } finally {
            if (SystemFileSystem.exists(tmp)) SystemFileSystem.delete(tmp)
        }
    }

    // endregion
}
