package dev.jotalac.feature.widget

import dev.jotalac.feature.editor_sidebar.domain.FileNode
import dev.jotalac.feature.notebooks_management.domain.NotebookRepository
import dev.jotalac.feature.notebooks_management.domain.Notebook
import dev.jotalac.feature.vault.data.VaultFileStore
import dev.jotalac.feature.vault.data.VaultLayout
import dev.jotalac.feature.vault.domain.NoteVault
import dev.jotalac.feature.vault.domain.VaultState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.koin.mp.KoinPlatform
import java.io.File

data class WidgetNote(
    val title: String,
    val path: String,
    val folder: String?,
    val preview: String,
    val lastModified: Long,
)

data class WidgetNotebook(
    val name: String,
    val notes: List<WidgetNote>,
)

/*
* access point for the android home screen widget (koin is not visible in the androidApp module)
* */
object WidgetNotesProvider {
    private const val MAX_NOTES = 30
    private const val PREVIEW_READ_CHARS = 600
    private const val PREVIEW_LENGTH = 140

    private val notebookRepository: NotebookRepository
        get() = KoinPlatform.getKoin().get()
    private val vault: NoteVault
        get() = KoinPlatform.getKoin().get()
    private val fileStore: VaultFileStore
        get() = KoinPlatform.getKoin().get()

    // emits null when there is no active notebook, re-emits whenever the active notebook changes
    fun activeNotebookFlow(): Flow<WidgetNotebook?> = notebookRepository.activeNotebookState
        .map { notebook -> if (notebook != null) loadNotebook(notebook) else null }
        .flowOn(Dispatchers.IO)

    private suspend fun loadNotebook(notebook: Notebook): WidgetNotebook = when (vault.stateOf(notebook)) {
        VaultState.Off -> loadPlainNotebook(notebook)
        is VaultState.Unlocked -> loadVaultNotebook(notebook)
        // no key on this device yet - the widget shows placeholders without names or text
        VaultState.Locked -> WidgetNotebook(notebook.name, loadLockedPlaceholders(notebook))
    }

    // the editor opens the active note, so activating it is enough to open it on app start
    suspend fun openNote(notePath: String): Result<Unit> {
        if (notePath.isBlank()) return Result.failure(IllegalArgumentException("Nothing to open"))
        return notebookRepository.activateNote(notePath)
    }

    private fun loadPlainNotebook(notebook: Notebook): WidgetNotebook {
        val root = File(notebook.directoryPath)
        if (!root.isDirectory) return WidgetNotebook(notebook.name, emptyList())

        val notes = root.walkTopDown()
            // skip .git and other hidden folders
            .onEnter { it == root || !it.name.startsWith(".") }
            .filter { it.isFile && it.extension.equals("md", ignoreCase = true) }
            .sortedByDescending { it.lastModified() }
            .take(MAX_NOTES)
            .map { it.toWidgetNote(root) }
            .toList()

        return WidgetNotebook(notebook.name, notes)
    }

    private suspend fun loadVaultNotebook(notebook: Notebook): WidgetNotebook {
        val tree = vault.buildFileTree(notebook) ?: return WidgetNotebook(notebook.name, emptyList())
        val rootPath = notebook.directoryPath

        val notes = mutableListOf<WidgetNote>()
        suspend fun collect(node: FileNode) {
            when (node) {
                is FileNode.Directory -> node.children.forEach { collect(it) }
                is FileNode.File -> {
                    if (!node.name.endsWith(".md", ignoreCase = true)) return
                    val diskPath = node.diskPath ?: return
                    val folder = node.path
                        .removePrefix("$rootPath/")
                        .substringBeforeLast('/')
                        .takeIf { it.isNotEmpty() && it != node.path }
                    notes += WidgetNote(
                        title = node.name.removeSuffix(".md"),
                        path = node.path,
                        folder = folder,
                        preview = readVaultPreview(rootPath, node.path),
                        lastModified = File(diskPath).lastModified(),
                    )
                }
            }
        }
        collect(tree)

        return WidgetNotebook(notebook.name, notes.sortedByDescending { it.lastModified }.take(MAX_NOTES))
    }

    private suspend fun readVaultPreview(rootPath: String, notePath: String): String {
        val session = vault.sessionForPath(rootPath) ?: return ""
        val head = runCatching {
            val content = fileStore.read(rootPath, session.vault, VaultLayout.relPathOf(rootPath, notePath))
                ?.decodeToString() ?: ""
            content.take(PREVIEW_READ_CHARS)
        }.getOrDefault("")
        return cleanPreview(head)
    }

    // locked vault: one placeholder per encrypted file, nothing readable
    private fun loadLockedPlaceholders(notebook: Notebook): List<WidgetNote> {
        val root = File(notebook.directoryPath)
        if (!root.isDirectory) return emptyList()

        return root.walkTopDown()
            .onEnter { it == root || !it.name.startsWith(".") }
            .filter { it.isFile && it.name.endsWith(".gwv") }
            .sortedByDescending { it.lastModified() }
            .take(MAX_NOTES)
            .map {
                WidgetNote(
                    title = "•••",
                    path = "", // nothing to open until the notebook is unlocked
                    folder = null,
                    preview = "",
                    lastModified = it.lastModified(),
                )
            }
            .toList()
    }

    private fun File.toWidgetNote(root: File): WidgetNote {
        val folder = parentFile
            ?.takeIf { it != root }
            ?.relativeTo(root)
            ?.path

        return WidgetNote(
            title = nameWithoutExtension,
            path = absolutePath,
            folder = folder,
            preview = cleanPreview(readPreviewHead()),
            lastModified = lastModified(),
        )
    }

    private fun File.readPreviewHead(): String {
        return runCatching {
            bufferedReader().use { reader ->
                val buffer = CharArray(PREVIEW_READ_CHARS)
                val read = reader.read(buffer)
                if (read > 0) String(buffer, 0, read) else ""
            }
        }.getOrDefault("")
    }

    // remove the common markdown syntax from the widget preview
    private fun cleanPreview(head: String): String =
        head.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("```") && !it.startsWith("$$") && !it.startsWith("![") }
            .map { it.trimStart('#', '>', '-', '*', '+', ' ').replace("**", "").replace("`", "").replace("~~", "") }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .take(PREVIEW_LENGTH)
}
