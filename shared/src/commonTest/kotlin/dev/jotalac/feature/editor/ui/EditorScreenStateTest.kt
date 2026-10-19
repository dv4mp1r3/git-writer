package dev.jotalac.feature.editor.ui

import dev.jotalac.core.utils.RemoteStatus
import dev.jotalac.feature.git_sync.domain.GitSyncStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class EditorScreenStateTest {

    private fun button(git: GitSyncStatus, remote: RemoteStatus?) =
        EditorScreenState(gitSyncStatus = git).syncButtonStatus(remote)

    @Test
    fun `the sync button says when the remote cannot be reached`() {
        assertEquals(GitSyncStatus.Offline, button(GitSyncStatus.UpToDate, RemoteStatus.OFFLINE))
        assertEquals(GitSyncStatus.RemoteUnreachable, button(GitSyncStatus.UpToDate, RemoteStatus.UNREACHABLE))
        // a failed sync explained by the missing network
        assertEquals(GitSyncStatus.Offline, button(GitSyncStatus.GitSyncFailed, RemoteStatus.OFFLINE))
    }

    @Test
    fun `a reachable or unknown remote shows the git status`() {
        assertEquals(GitSyncStatus.GitSyncFailed, button(GitSyncStatus.GitSyncFailed, RemoteStatus.REACHABLE))
        assertEquals(GitSyncStatus.UpToDate, button(GitSyncStatus.UpToDate, null))
    }

    @Test
    fun `syncing and a conflict and a missing remote are not hidden by the network state`() {
        assertEquals(GitSyncStatus.Syncing, button(GitSyncStatus.Syncing, RemoteStatus.OFFLINE))
        val conflict = GitSyncStatus.Conflict(setOf("a.md"))
        assertEquals(conflict, button(conflict, RemoteStatus.OFFLINE))
        assertEquals(GitSyncStatus.GitSyncNotConfigured, button(GitSyncStatus.GitSyncNotConfigured, RemoteStatus.OFFLINE))
    }
}
