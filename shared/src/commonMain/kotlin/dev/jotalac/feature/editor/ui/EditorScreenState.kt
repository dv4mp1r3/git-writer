package dev.jotalac.feature.editor.ui

import dev.jotalac.core.utils.RemoteStatus
import dev.jotalac.feature.editor.domain.EditorTabItem
import dev.jotalac.feature.git_sync.domain.GitSyncStatus

data class EditorScreenState(
    val isImage: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val gitSyncStatus: GitSyncStatus = GitSyncStatus.UpToDate,
    val conflictedFiles: List<String> = emptyList(),
    val openedTabs: List<EditorTabItem> = listOf(EditorTabItem(id = 0L, notePath = null)),
    val activeTabId: Long = 0L,
) {
    val activeTab: EditorTabItem?
        get() = openedTabs.firstOrNull { it.id == activeTabId }

    /** The note currently shown in the editor, derived from the active tab. */
    val activeNotePath: String?
        get() = activeTab?.notePath

    val activeFilename: String?
        get() = activeTab?.filename

    /**
     * What the sync button shows: the git status, or that the remote cannot be reached ([remoteStatus],
     * EditorViewModel.remoteStatus) - except while syncing, during a conflict, or without a remote, which
     * matter more.
     */
    fun syncButtonStatus(remoteStatus: RemoteStatus?): GitSyncStatus = when {
            gitSyncStatus is GitSyncStatus.Syncing ||
                gitSyncStatus is GitSyncStatus.Conflict ||
                gitSyncStatus is GitSyncStatus.GitSyncNotConfigured -> gitSyncStatus
            remoteStatus == RemoteStatus.OFFLINE -> GitSyncStatus.Offline
            remoteStatus == RemoteStatus.UNREACHABLE -> GitSyncStatus.RemoteUnreachable
            else -> gitSyncStatus
        }
}
