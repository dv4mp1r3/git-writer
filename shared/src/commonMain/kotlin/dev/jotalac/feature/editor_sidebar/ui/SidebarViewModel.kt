package dev.jotalac.feature.editor_sidebar.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.jotalac.core.utils.SnackbarManager
import dev.jotalac.core.utils.SnackbarText
import dev.jotalac.core.utils.buildFileTree
import dev.jotalac.core.utils.toSafeFileName
import dev.jotalac.feature.editor.domain.EditorRepository
import dev.jotalac.feature.editor_sidebar.domain.FileNode
import dev.jotalac.feature.editor_sidebar.domain.FlatFileNode
import dev.jotalac.feature.git_sync.domain.GitSyncRepository
import dev.jotalac.feature.git_sync.domain.GitSyncStatus
import dev.jotalac.feature.notebooks_management.domain.Notebook
import dev.jotalac.feature.notebooks_management.domain.NotebookRepository
import dev.jotalac.feature.pdf_export.PdfExportRequest
import dev.jotalac.feature.vault.data.VaultController
import dev.jotalac.feature.vault.crypto.VaultFormatException
import dev.jotalac.feature.vault.crypto.VaultKeystoreConflictException
import dev.jotalac.feature.vault.domain.NoteVault
import dev.jotalac.feature.vault.domain.VaultState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.io.files.Path
import kotlinx.io.files.SystemPathSeparator
import kotlin.time.Duration.Companion.milliseconds
import git_writer.shared.generated.resources.Res
import git_writer.shared.generated.resources.err_failed_add_folder
import git_writer.shared.generated.resources.err_failed_add_note
import git_writer.shared.generated.resources.err_failed_delete
import git_writer.shared.generated.resources.err_failed_export_pdf
import git_writer.shared.generated.resources.err_failed_move
import git_writer.shared.generated.resources.err_failed_rename
import git_writer.shared.generated.resources.err_keystore_import_conflict
import git_writer.shared.generated.resources.err_keystore_import_invalid
import git_writer.shared.generated.resources.err_vault_key_mismatch
import git_writer.shared.generated.resources.msg_keystore_imported
import git_writer.shared.generated.resources.msg_path_copied
import git_writer.shared.generated.resources.msg_pdf_exported
import git_writer.shared.generated.resources.msg_vault_unlocked
import git_writer.shared.generated.resources.sidebar_locked_wrong
import org.jetbrains.compose.resources.getString

data class SidebarState(
    val activeNotebook: Notebook? = null,
    val activeNotePath: String? = null,
    val fileTree: FileNode.Directory? = null,
    val expandedFolders: Set<String> = emptySet(),
    val itemToRename: String? = null,
    val pdfExport: PdfExportRequest? = null,
    val vaultState: VaultState = VaultState.Off,
    val vaultBusy: Boolean = false,
    val vaultError: String? = null,
    val keystoreImportRequest: Boolean = false,
)

class EditorSidebarViewModel(
    private val notebookRepository: NotebookRepository,
    private val editorRepository: EditorRepository,
    private val snackbarManager: SnackbarManager,
    private val gitSyncRepository: GitSyncRepository,
    private val vault: NoteVault,
    private val vaultController: VaultController,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SidebarState())
    val uiState: StateFlow<SidebarState> = _uiState.asStateFlow()

    private var filesRefreshJob: Job? = null

    private var expectedNotePath: String? = null


    init {
        viewModelScope.launch {
            notebookRepository.activeNotebookState
                .distinctUntilChanged()
                .collectLatest { notebook ->
                    if (notebook != null) {
                        _uiState.update {
                            it.copy(
                                activeNotebook = notebook,
                                expandedFolders = emptySet(),
                                vaultState = vault.stateOf(notebook),
                                vaultError = null,
                            )
                        }

                        refreshFileTree()
                    } else {
                        _uiState.update { currentState ->
                            currentState.copy(
                                activeNotebook = null,
                                fileTree = null,
                                expandedFolders = emptySet(),
                                vaultState = VaultState.Off,
                                vaultError = null,
                            )
                        }

                        gitSyncRepository.updateSyncStatus(null)
                    }
                }
        }

        viewModelScope.launch {
            // switching to the decrypted tree (or back to raw names) when the vault state changes
            vault.states.collect {
                val activeNotebook = _uiState.value.activeNotebook ?: return@collect
                val newState = vault.stateOf(activeNotebook)
                if (newState != _uiState.value.vaultState) {
                    _uiState.update { state -> state.copy(vaultState = newState) }
                    refreshFileTree()
                }
            }
        }

        viewModelScope.launch {
            // handle the initial sync state
            val activeNotebookRemoteUrl = notebookRepository.activeNotebookState.firstOrNull()?.remoteUrl
            gitSyncRepository.updateSyncStatus(activeNotebookRemoteUrl)

            // refresh file tree on git sync
            gitSyncRepository.gitSyncStatus.collect { status ->
                if (status is GitSyncStatus.UpToDate) {
                    refreshFileTree()
                }
            }
        }

        viewModelScope.launch {
            notebookRepository.activeNotePath.collect { notePath ->
                _uiState.update { it.copy(activeNotePath = notePath) }

                // a note created outside the sidebar (Ctrl+N) isn't in the tree yet - refresh and
                // start renaming it. Skip when we just created/moved/renamed the note ourselves.
                if (notePath != null && isExpectedNotePath(notePath)) {
                    expectedNotePath = null
                } else if (notePath != null && _uiState.value.fileTree != null && findNode(notePath) == null) {
                    refreshFileTree()
                    setRenameItem(notePath)
                }
            }
        }
    }

    fun onAction(action: SidebarAction) {
        when (action) {
            is SidebarAction.MoveItem -> moveItem(action.sourcePath, action.destinationDirectoryPath)
            is SidebarAction.AddNote -> addNote(action.path)
            is SidebarAction.AddFolder -> addFolder(action.folderPath)
            is SidebarAction.DeleteItem -> deleteItem(action.path)
            is SidebarAction.RenameItem -> renameItem(action.path, action.newName)
            is SidebarAction.OpenNote -> setActiveNote(action.notePath)
            is SidebarAction.SetRenameItem -> setRenameItem(action.path)
            is SidebarAction.DuplicateNote -> duplicateNote(action.notePath)
            is SidebarAction.ExportPdf -> exportPdf(action.notePath)
            is SidebarAction.PdfExportFinished -> finishPdfExport(action.result)
            is SidebarAction.UnlockVault -> unlockVault(action.input, action.isRecoveryKey)
            is SidebarAction.ImportVaultKeystore -> _uiState.update { it.copy(keystoreImportRequest = true) }
            is SidebarAction.VaultKeystoreImportFinished -> finishKeystoreImport(action.result)

            is SidebarAction.CopyItemPath -> {
                snackbarManager.showMessage(SnackbarText.resource(Res.string.msg_path_copied))
            }
        }
    }

    /**
     * Unlocking from the sidebar is the catch-up flow for a notebook another device encrypted:
     * unlock and pull the remote state in, carrying over any notes typed while it was locked.
     */
    private fun unlockVault(input: String, isRecoveryKey: Boolean) {
        val notebook = _uiState.value.activeNotebook ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(vaultBusy = true, vaultError = null) }

            val result = if (isRecoveryKey) {
                vaultController.adoptEncryptedNotebookWithRecoveryKey(notebook, input)
            } else {
                vaultController.adoptEncryptedNotebook(notebook, input)
            }

            result.onSuccess {
                _uiState.update { it.copy(vaultBusy = false, vaultError = null) }
                snackbarManager.showMessage(SnackbarText.resource(Res.string.msg_vault_unlocked))
            }.onFailure { error ->
                if (error is CancellationException) throw error
                // a key that belongs to another vault says so - everything else reads as a wrong input
                _uiState.update {
                    it.copy(
                        vaultBusy = false,
                        vaultError = if (error is VaultFormatException) {
                            getString(Res.string.err_vault_key_mismatch)
                        } else {
                            getString(Res.string.sidebar_locked_wrong)
                        }
                    )
                }
            }
        }
    }

    /** The keystore file picked from the locked panel: installed or reported through a snackbar. */
    private fun finishKeystoreImport(result: Result<Unit>?) {
        _uiState.update { it.copy(keystoreImportRequest = false) }
        when {
            result == null -> {} // the picker was cancelled, nothing to say
            result.isSuccess -> snackbarManager.showMessage(SnackbarText.resource(Res.string.msg_keystore_imported))
            result.exceptionOrNull() is VaultKeystoreConflictException ->
                snackbarManager.showMessage(SnackbarText.resource(Res.string.err_keystore_import_conflict))
            else -> snackbarManager.showMessage(SnackbarText.message(Res.string.err_keystore_import_invalid, result.exceptionOrNull()?.message))
        }
    }

    // the item itself, or a note inside a renamed / moved folder
    private fun isExpectedNotePath(notePath: String): Boolean {
        val expected = expectedNotePath ?: return false
        return notePath == expected || notePath.startsWith("$expected$SystemPathSeparator")
    }

    private fun setRenameItem(path: String?) {
        _uiState.update { it.copy(itemToRename = path) }
    }

    private fun deleteItem(path: String) {
        viewModelScope.launch {
            editorRepository.deleteItem(path)
                .onSuccess {
                    notebookRepository.syncActiveNotePathOnDeleted(path)
                    refreshFileTree()
                }.onFailure {
                    snackbarManager.showMessage(SnackbarText.message(Res.string.err_failed_delete, it.message))
                }
        }
    }

    private fun renameItem(path: String, newName: String) {
        if (newName.isBlank()) {
            setRenameItem(null)
            return
        }
        val fileExtension = Path(path).name.substringAfterLast('.', "")
        val node = findNode(path)
        val finalName =
            if (node is FileNode.File && fileExtension.isNotEmpty() && !newName.endsWith(".$fileExtension")) {
                "$newName.$fileExtension"
            } else {
                newName
            }
        val safeName = finalName.toSafeFileName()

        // check if the original and final names are the same
        if (node?.path?.substringAfterLast("/") == safeName) {
            setRenameItem(null)
            return
        }

        viewModelScope.launch {
            editorRepository.renameItem(path, safeName)
                .onSuccess {
                    setRenameItem(null)
                    val parentPath = Path(path).parent
                    val newPath = if (parentPath != null) Path(parentPath, safeName).toString() else safeName
                    expectedNotePath = newPath
                    notebookRepository.syncActiveNotePathOnMoved(path, newPath)
                    refreshFileTree()
                }.onFailure {
                    snackbarManager.showMessage(SnackbarText.message(Res.string.err_failed_rename, it.message))
                }
        }
    }

    private fun findNode(path: String, root: FileNode? = _uiState.value.fileTree): FileNode? {
        if (root == null) return null
        if (root.path == path) return root
        if (root is FileNode.Directory) {
            for (child in root.children) {
                val found = findNode(path, child)
                if (found != null) return found
            }
        }
        return null
    }

    private fun getUniqueName(baseName: String, isFolder: Boolean, parentPath: String?): String {
        val rootNode = _uiState.value.fileTree ?: return baseName
        val targetPath = parentPath ?: rootNode.path
        val targetDir = findNode(targetPath) as? FileNode.Directory ?: rootNode

        val extension = if (!isFolder) ".md" else ""

        fun exists(name: String): Boolean {
            return targetDir.children.any { it.name == name }
        }

        var counter = 0
        var candidate = "$baseName$extension"
        while (exists(candidate)) {
            counter++
            candidate = "$baseName $counter$extension"
        }
        return candidate
    }

    fun onWindowFocusChanged(hasFocus: Boolean) {
        // debounce the file tree reload on fast alt-tabs
        if (hasFocus) {
            filesRefreshJob?.cancel()

            filesRefreshJob = viewModelScope.launch {
                delay(300.milliseconds)
                refreshFileTree()
            }
        }
    }

    private fun duplicateNote(notePath: String) {
        viewModelScope.launch {
            editorRepository.readNoteContent(notePath)
                .onSuccess { content ->
                    addNote(
                        parentPath = notePath.substringBeforeLast("/"),
                        defaultName = notePath.substringAfterLast("/").substringBeforeLast("."),
                        noteContent = content
                    )
                }
                .onFailure {
                    snackbarManager.showMessage(SnackbarText.message(Res.string.err_failed_add_note, it.message))
                }
        }
    }

    private fun exportPdf(notePath: String) {
        // one export at a time - another request would replace the running one halfway
        if (_uiState.value.pdfExport != null) return

        viewModelScope.launch {
            editorRepository.readNoteContent(notePath)
                .onSuccess { content ->
                    val noteName = Path(notePath).name.substringBeforeLast(".")
                    _uiState.update { it.copy(pdfExport = PdfExportRequest(noteName, content)) }
                }
                .onFailure {
                    snackbarManager.showMessage(SnackbarText.message(Res.string.err_failed_export_pdf, it.message))
                }
        }
    }

    private fun finishPdfExport(result: Result<String?>) {
        _uiState.update { it.copy(pdfExport = null) }
        result
            .onSuccess { fileName ->
                // null - the save dialog was cancelled, nothing to say
                fileName ?: return@onSuccess
                snackbarManager.showMessage(SnackbarText.resource(Res.string.msg_pdf_exported, fileName))
            }
            .onFailure {
                snackbarManager.showMessage(SnackbarText.message(Res.string.err_failed_export_pdf, it.message))
            }
    }

    private fun refreshFileTree() {
        val activeNotebook = _uiState.value.activeNotebook ?: return

        filesRefreshJob?.cancel()
        filesRefreshJob = viewModelScope.launch {
            val notebookPath = Path(activeNotebook.directoryPath)
            val filesTree = withContext(Dispatchers.IO) {
                when (vault.stateOf(activeNotebook)) {
                    // vault notebooks list their decrypted paths, built from the encrypted headers
                    is VaultState.Unlocked, is VaultState.Locked -> vault.buildFileTree(activeNotebook) ?: notebookPath.buildFileTree()
                    VaultState.Off -> notebookPath.buildFileTree()
                }
            }

            _uiState.update { currentState ->
                currentState.copy(fileTree = filesTree)
            }
        }
    }

    fun setActiveNote(notePath: String) {
        viewModelScope.launch {
            val result = notebookRepository.activateNote(notePath)

            result.onFailure {
                println("Failed to open note: $it")
            }
        }
    }

    fun toggleFolder(path: String) {
        _uiState.update { currentState ->
            val currentSet = currentState.expandedFolders
            val newSet = if (currentSet.contains(path)) currentSet - path else currentSet + path

            currentState.copy(
                expandedFolders = newSet,
            )
        }
    }

    fun getVisibleNodes(root: FileNode.Directory, expandedPaths: Set<String>): List<FlatFileNode> {
        val result = mutableListOf<FlatFileNode>()

        fun traverse(node: FileNode, depth: Int) {
            when (node) {
                is FileNode.File -> {
                    result.add(FlatFileNode(node, depth))
                }

                is FileNode.Directory -> {
                    val isExpanded = expandedPaths.contains(node.path)
                    result.add(FlatFileNode(node, depth, isExpanded))

                    if (isExpanded) {
                        node.children.forEach { child ->
                            traverse(child, depth + 1)
                        }
                    }
                }
            }
        }

        root.children.forEach { traverse(it, 0) }
        return result
    }

    fun expandFolder(path: String) {
        _uiState.update { currentState ->
            if (currentState.expandedFolders.contains(path)) currentState
            else currentState.copy(expandedFolders = currentState.expandedFolders + path)
        }
    }

    private fun addNote(parentPath: String?, defaultName: String = "untitled", noteContent: String = "") {
        val rootPath = _uiState.value.fileTree?.path ?: return
        val targetPath = parentPath ?: rootPath

        viewModelScope.launch {
            editorRepository.createNote(targetPath, defaultName, noteContent)
                .onSuccess { newPath ->
                    expandFolder(targetPath)
                    refreshFileTree()
                    expectedNotePath = newPath
                    setActiveNote(newPath)
                    setRenameItem(newPath)
                }.onFailure {
                    snackbarManager.showMessage(SnackbarText.message(Res.string.err_failed_add_note, it.message))
                }
        }
    }

    private fun addFolder(parentPath: String?) {
        val rootPath = _uiState.value.fileTree?.path ?: return
        val targetPath = parentPath ?: rootPath
        val folderName = getUniqueName("untitled", true, targetPath)

        viewModelScope.launch {
            val result = editorRepository.addFolder(folderName.toSafeFileName(), targetPath)

            result.onSuccess {
                val newPath = Path(Path(targetPath), folderName.toSafeFileName()).toString()
                expandFolder(targetPath)
                refreshFileTree()

                setRenameItem(newPath)
            }.onFailure {
                snackbarManager.showMessage(SnackbarText.message(Res.string.err_failed_add_folder, it.message))
            }
        }
    }

    private fun moveItem(sourcePath: String, destinationDirectoryPath: String) {
        viewModelScope.launch {
            editorRepository.moveItem(sourcePath, destinationDirectoryPath)
                .onSuccess {
                    val itemName = Path(sourcePath).name
                    val newPath = Path(Path(destinationDirectoryPath), itemName).toString()
                    expectedNotePath = newPath
                    notebookRepository.syncActiveNotePathOnMoved(sourcePath, newPath)
                    refreshFileTree()
                }.onFailure {
                    snackbarManager.showMessage(SnackbarText.message(Res.string.err_failed_move, it.message))
                }
        }
    }

    fun toggleFolderCollapse() {
        if (_uiState.value.expandedFolders.isNotEmpty()) {
            // collapse
            _uiState.update {
                it.copy(
                    expandedFolders = emptySet(),
                )
            }
        } else {
            // expand
            _uiState.update { currentState ->
                val allDirs = mutableSetOf<String>()
                fun traverse(node: FileNode.Directory) {
                    allDirs.add(node.path)
                    node.children.forEach { child ->
                        if (child is FileNode.Directory) {
                            traverse(child)
                        }
                    }
                }
                currentState.fileTree?.let { traverse(it) }

                currentState.copy(expandedFolders = allDirs)
            }
        }
    }


}