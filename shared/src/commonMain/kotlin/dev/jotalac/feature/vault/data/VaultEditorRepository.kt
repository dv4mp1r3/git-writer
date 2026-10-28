package dev.jotalac.feature.vault.data

import dev.jotalac.core.utils.suspendRunCatching
import dev.jotalac.core.utils.toSafeFileName
import dev.jotalac.feature.editor.data.mapper.chunkMarkdownIntoBlocks
import dev.jotalac.feature.editor.domain.EditorRepository
import dev.jotalac.feature.notebooks_management.domain.NotebookRepository
import dev.jotalac.feature.vault.crypto.UnlockedVault
import dev.jotalac.feature.vault.crypto.canonicalRelPath
import dev.jotalac.feature.vault.domain.NoteVault
import dev.jotalac.feature.vault.domain.VaultLockedException
import dev.jotalac.feature.vault.domain.VaultState
import kotlinx.coroutines.flow.first
import kotlinx.io.IOException

/**
 * Wraps the plain [EditorRepository] and intercepts every call whose path belongs to a vault
 * notebook: notes are decrypted on read and encrypted on write, renames and moves re-encrypt
 * under the new path (it lives inside the encrypted payload). The UI keeps working with virtual
 * note paths as if the notes were plain files; the encrypted `<cname>.gwv` files never surface
 * except as orphans, whose raw `.gwv` paths fall through to the base repository untouched.
 */
class VaultEditorRepository(
    private val base: EditorRepository,
    private val notebookRepository: NotebookRepository,
    private val vault: NoteVault,
    private val fileStore: VaultFileStore,
) : EditorRepository {

    private sealed interface Route {
        data object Plain : Route
        data class Vault(val root: String) : Route
        data object LockedVault : Route

        /** A raw `.gwv` path (an orphan encrypted file addressed by its real disk name). */
        data object RawEncrypted : Route
    }

    private suspend fun routeOf(path: String): Route {
        // orphan encrypted files are addressed by their real disk path: deleting/moving them is
        // a raw file operation, but their ciphertext must never surface as note content
        if (path.endsWith(VaultLayout.ENCRYPTED_EXTENSION)) return Route.RawEncrypted

        val notebooks = notebookRepository.getAllNotebooks().first()
        val root = VaultLayout.notebookRootOf(notebooks.map { it.directoryPath }, path) ?: return Route.Plain
        val notebook = notebooks.first { it.directoryPath == root }
        return when (vault.stateOf(notebook)) {
            is VaultState.Unlocked -> Route.Vault(root)
            VaultState.Locked -> Route.LockedVault
            VaultState.Off -> Route.Plain
        }
    }

    private fun sessionOf(root: String): UnlockedVault =
        vault.sessionForPath(root)?.vault ?: throw VaultLockedException()

    private fun requireNoteRelPath(root: String, vaultKeys: UnlockedVault, fullPath: String): String =
        fileStore.requireRelPath(root, vaultKeys, fullPath)

    /** Relative path of a target directory inside the notebook; empty for the root itself. */
    private fun dirRelPathOf(root: String, vaultKeys: UnlockedVault, directoryPath: String): String {
        val rel = VaultLayout.relPathOf(root, directoryPath)
        return if (rel.isEmpty()) "" else canonicalRelPath(rel)
    }

    private fun joinRel(dir: String, name: String): String = if (dir.isEmpty()) name else "$dir/$name"

    /** Reading or writing the ciphertext of an encrypted file as note content is never allowed. */
    private fun encryptedPathError(): IllegalStateException =
        IllegalStateException("Path points to an encrypted vault file, not a note")

    override suspend fun fileExists(filePath: String): Boolean = when (val route = routeOf(filePath)) {
        Route.Plain, Route.RawEncrypted -> base.fileExists(filePath)
        Route.LockedVault -> false
        is Route.Vault -> suspendRunCatching {
            val keys = sessionOf(route.root)
            fileStore.exists(route.root, keys, requireNoteRelPath(route.root, keys, filePath))
        }.getOrDefault(false)
    }

    override suspend fun loadMarkdownFileBlocks(filePath: String): Result<List<String>> = when (val route = routeOf(filePath)) {
        Route.Plain -> base.loadMarkdownFileBlocks(filePath)
        Route.RawEncrypted -> Result.failure(encryptedPathError())
        Route.LockedVault -> Result.failure(VaultLockedException())
        is Route.Vault -> suspendRunCatching {
            val keys = sessionOf(route.root)
            val rel = requireNoteRelPath(route.root, keys, filePath)
            val content = fileStore.read(route.root, keys, rel)?.decodeToString() ?: throw IOException("File doesn't exist")
            chunkMarkdownIntoBlocks(content)
        }
    }

    override suspend fun readNoteContent(filePath: String): Result<String> = when (val route = routeOf(filePath)) {
        Route.Plain -> base.readNoteContent(filePath)
        Route.RawEncrypted -> Result.failure(encryptedPathError())
        Route.LockedVault -> Result.failure(VaultLockedException())
        is Route.Vault -> suspendRunCatching {
            val keys = sessionOf(route.root)
            val rel = requireNoteRelPath(route.root, keys, filePath)
            fileStore.read(route.root, keys, rel)?.decodeToString() ?: throw IOException("File doesn't exist")
        }
    }

    override suspend fun saveFile(fileContent: String, filePath: String): Result<Unit> = when (val route = routeOf(filePath)) {
        Route.Plain -> base.saveFile(fileContent, filePath)
        Route.RawEncrypted -> Result.failure(encryptedPathError())
        Route.LockedVault -> Result.failure(VaultLockedException())
        is Route.Vault -> suspendRunCatching {
            val keys = sessionOf(route.root)
            val rel = requireNoteRelPath(route.root, keys, filePath)
            if (!fileStore.exists(route.root, keys, rel)) throw IOException("File doesn't exist")
            fileStore.write(route.root, keys, rel, fileContent.encodeToByteArray())
        }
    }

    override suspend fun createNote(directoryPath: String, baseName: String, noteContent: String): Result<String> =
        when (val route = routeOf(directoryPath)) {
            Route.Plain -> base.createNote(directoryPath, baseName, noteContent)
            Route.RawEncrypted -> Result.failure(encryptedPathError())
            Route.LockedVault -> Result.failure(VaultLockedException())
            is Route.Vault -> suspendRunCatching {
                val keys = sessionOf(route.root)
                val dirRel = dirRelPathOf(route.root, keys, directoryPath)
                val safeBaseName = baseName.toSafeFileName()
                val takenNames = fileStore.listNoteRelPaths(route.root, keys)
                    .filter { rel -> (if ('/' in rel) rel.substringBeforeLast('/') else "") == dirRel }
                    .map { rel -> rel.substringAfterLast('/') }
                    .toSet()

                var filename = "$safeBaseName.md"
                var counter = 0
                while (filename in takenNames) {
                    counter++
                    filename = "$safeBaseName $counter.md"
                }

                val relPath = joinRel(dirRel, filename)
                fileStore.write(route.root, keys, relPath, noteContent.encodeToByteArray())
                VaultLayout.joinVirtual(route.root, relPath)
            }
        }

    // folders are virtual: they appear once a note is encrypted under a path inside them
    override suspend fun addFolder(folderName: String, filePath: String): Result<Unit> = when (val route = routeOf(filePath)) {
        Route.Plain -> base.addFolder(folderName, filePath)
        Route.RawEncrypted -> Result.failure(encryptedPathError())
        Route.LockedVault -> Result.failure(VaultLockedException())
        is Route.Vault -> Result.success(Unit)
    }

    override suspend fun moveItem(sourcePath: String, destinationDirectoryPath: String): Result<Unit> {
        // the same guard rails as the plain implementation
        if (sourcePath.substringBeforeLast("/") == destinationDirectoryPath) return Result.success(Unit)
        if (destinationDirectoryPath == sourcePath || destinationDirectoryPath.startsWith("$sourcePath/")) {
            return Result.failure(IllegalStateException("Cannot move a folder into itself or its subfolder."))
        }

        return when (val route = routeOf(sourcePath)) {
            Route.Plain, Route.RawEncrypted -> base.moveItem(sourcePath, destinationDirectoryPath)
            Route.LockedVault -> Result.failure(VaultLockedException())
            is Route.Vault -> suspendRunCatching {
                val keys = sessionOf(route.root)
                val sourceRel = requireNoteRelPath(route.root, keys, sourcePath)
                val destDirRel = dirRelPathOf(route.root, keys, destinationDirectoryPath)
                val sourceIsFile = fileStore.exists(route.root, keys, sourceRel)

                // a folder move relocates every note under it; the folder name travels along
                val moves: List<Pair<String, String>> = if (sourceIsFile) {
                    listOf(sourceRel to joinRel(destDirRel, sourceRel.substringAfterLast('/')))
                } else {
                    val folderPrefix = "$sourceRel/"
                    fileStore.listNoteRelPaths(route.root, keys)
                        .filter { it.startsWith(folderPrefix) }
                        .map { it to joinRel(destDirRel, it) }
                }

                for ((_, target) in moves) {
                    if (fileStore.exists(route.root, keys, target)) {
                        throw IllegalStateException("A file or folder named '${target.substringAfterLast('/')}' already exists in this directory.")
                    }
                }
                for ((from, to) in moves) fileStore.move(route.root, keys, from, to)
            }
        }
    }

    override suspend fun renameItem(sourcePath: String, newName: String): Result<Unit> = when (val route = routeOf(sourcePath)) {
        Route.Plain, Route.RawEncrypted -> base.renameItem(sourcePath, newName)
        Route.LockedVault -> Result.failure(VaultLockedException())
        is Route.Vault -> suspendRunCatching {
            val keys = sessionOf(route.root)
            val sourceRel = requireNoteRelPath(route.root, keys, sourcePath)
            val parentRel = sourceRel.substringBeforeLast('/', "")
            val sourceIsFile = fileStore.exists(route.root, keys, sourceRel)

            val moves: List<Pair<String, String>> = if (sourceIsFile) {
                listOf(sourceRel to joinRel(parentRel, newName))
            } else {
                val folderPrefix = "$sourceRel/"
                fileStore.listNoteRelPaths(route.root, keys)
                    .filter { it.startsWith(folderPrefix) }
                    .map { it to joinRel(parentRel, newName) + "/" + it.removePrefix(folderPrefix) }
            }

            for ((from, to) in moves) {
                if (from == to) continue
                if (fileStore.exists(route.root, keys, to)) {
                    throw IllegalStateException("A file or folder named '$newName' already exists.")
                }
            }
            for ((from, to) in moves) if (from != to) fileStore.move(route.root, keys, from, to)
        }
    }

    override suspend fun deleteItem(path: String): Result<Unit> = when (val route = routeOf(path)) {
        Route.Plain, Route.RawEncrypted -> base.deleteItem(path)
        Route.LockedVault -> Result.failure(VaultLockedException())
        is Route.Vault -> suspendRunCatching {
            val keys = sessionOf(route.root)
            val rel = requireNoteRelPath(route.root, keys, path)
            if (fileStore.exists(route.root, keys, rel)) {
                fileStore.delete(route.root, keys, rel)
            } else {
                // a (virtual) folder: drop every note under it
                val folderPrefix = "$rel/"
                fileStore.listNoteRelPaths(route.root, keys)
                    .filter { it.startsWith(folderPrefix) }
                    .forEach { fileStore.delete(route.root, keys, it) }
            }
        }
    }

    override suspend fun savePastedImage(notebookRootPath: String, imageBytes: ByteArray, filename: String): Result<Unit> =
        when (val route = routeOf(notebookRootPath)) {
            Route.Plain, Route.RawEncrypted -> base.savePastedImage(notebookRootPath, imageBytes, filename)
            Route.LockedVault -> Result.failure(VaultLockedException())
            is Route.Vault -> suspendRunCatching {
                val keys = sessionOf(route.root)
                fileStore.write(route.root, keys, "images/$filename", imageBytes)
            }
        }
}
