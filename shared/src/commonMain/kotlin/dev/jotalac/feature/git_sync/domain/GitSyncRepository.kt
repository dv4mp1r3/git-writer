package dev.jotalac.feature.git_sync.domain

import kotlinx.coroutines.flow.Flow

interface GitSyncRepository {
    suspend fun validateCredentials(repoUrl: String, tokenOrPassword: String, username: String? = null): Result<Unit>
    suspend fun cloneRepository(
        repoUrl: String,
        tokenOrPassword: String,
        destinationPath: String,
        username: String? = null
    ): Result<Unit>

    suspend fun initRepository(currentNotebookPath: String): Result<Unit>

    suspend fun syncNotes(
        currentNotebookPath: String,
        tokenOrPassword: String,
        username: String? = null,
        commitMessage: String = "sync notes"
    ): Result<GitSyncStatus>

    suspend fun resolveSingleConflict(
        currentNotebookPath: String,
        conflictedFilePath: String,
        keepLocalChanges: Boolean,
    ): Result<Unit>

    suspend fun resolveAllConflicts(
        currentNotebookPath: String,
        keepLocalChanges: Boolean,
    ): Result<Unit>

    suspend fun pushChanges(
        currentNotebookPath: String,
        tokenOrPassword: String,
        username: String? = null
    ): Result<Unit>

    suspend fun updateRemoteUrl(
        currentNotebookPath: String,
        newRemoteUrl: String,
    ): Result<Unit>

    suspend fun abortMerge(currentNotebookPath: String): Result<Unit>

    suspend fun removeRemote(currentNotebookPath: String): Result<Unit>

    fun updateSyncStatus(remoteUrl: String?, syncStatus: GitSyncStatus? = null): Result<Unit>

    /**
     * Replaces the whole branch history with one commit of the current working tree (`message`): the
     * notes stay, the commits before it leave the branch. This is how the plaintext history disappears
     * when encryption is turned on - the old objects only leave the server once it garbage-collects.
     */
    suspend fun resetHistoryToCurrentState(currentNotebookPath: String, commitMessage: String): Result<Unit>

    /** Uploads the current branch even when it diverged from the remote (history was rewritten). */
    suspend fun forcePush(
        currentNotebookPath: String,
        tokenOrPassword: String,
        username: String? = null,
    ): Result<Unit>

    /** Fetches the remote and moves the current branch and the working tree onto its state, dropping the local history. */
    suspend fun resetToRemote(
        currentNotebookPath: String,
        tokenOrPassword: String,
        username: String? = null,
    ): Result<Unit>

    /**
     * The versions of a conflicted file by index stage: 1 = common ancestor, 2 = local, 3 = remote.
     * A stage is missing when that side has no version of the file (it was added or deleted).
     */
    suspend fun getConflictVersions(currentNotebookPath: String, conflictedFilePath: String): Result<Map<Int, ByteArray>>

    /** Writes [content] to the file relative to the notebook and stages it (`git add`). */
    suspend fun stageFile(currentNotebookPath: String, filePath: String, content: ByteArray): Result<Unit>

    /**
     * Stops tracking [relativePath] while keeping the working-tree file (`git rm --cached`) and
     * stages the removal, so the next commit records it. This is how the vault keystore leaves
     * the repository without disappearing from the device it lives on.
     * @return true when the file was tracked and is now staged for removal.
     */
    suspend fun untrackFile(currentNotebookPath: String, relativePath: String): Result<Boolean>

    /** Sets the repo-local git identity to the generic one, so commits do not leak the machine's global git config. */
    suspend fun setRepoIdentity(currentNotebookPath: String): Result<Unit>

    /** Commits what is staged, completing a merge in progress (`git commit`). */
    suspend fun commitStaged(currentNotebookPath: String, commitMessage: String): Result<Unit>

    val gitSyncStatus: Flow<GitSyncStatus>
}

/** The author name of commits in vault notebooks - the host must not learn who writes the notes. */
const val VAULT_GIT_NAME = "Git Writer"

/** The committer email of commits in vault notebooks, matching [VAULT_GIT_NAME]. */
const val VAULT_GIT_EMAIL = "sync@git-writer.local"