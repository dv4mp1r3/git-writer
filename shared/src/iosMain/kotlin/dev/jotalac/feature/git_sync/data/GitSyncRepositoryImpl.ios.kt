@file:OptIn(ExperimentalForeignApi::class)

package dev.jotalac.feature.git_sync.data

import dev.jotalac.core.data.UserSettings
import dev.jotalac.core.domain.ProxyConfig
import dev.jotalac.core.utils.suspendRunCatching
import dev.jotalac.feature.git_sync.data.libgit2.GitCredentials
import dev.jotalac.feature.git_sync.data.libgit2.GitException
import dev.jotalac.feature.git_sync.data.libgit2.GitRepository
import dev.jotalac.feature.git_sync.data.libgit2.MergeResult
import dev.jotalac.feature.git_sync.data.libgit2.addRemote
import dev.jotalac.feature.git_sync.data.libgit2.checkRemoteAccess
import dev.jotalac.feature.git_sync.data.libgit2.clone
import dev.jotalac.feature.git_sync.data.libgit2.commit
import dev.jotalac.feature.git_sync.data.libgit2.commitAll
import dev.jotalac.feature.git_sync.data.libgit2.conflictVersions
import dev.jotalac.feature.git_sync.data.libgit2.conflictedFiles
import dev.jotalac.feature.git_sync.data.libgit2.deleteRemote
import dev.jotalac.feature.git_sync.data.libgit2.fetch
import dev.jotalac.feature.git_sync.data.libgit2.hasConflicts
import dev.jotalac.feature.git_sync.data.libgit2.hasUnpushedCommits
import dev.jotalac.feature.git_sync.data.libgit2.mergeFetched
import dev.jotalac.feature.git_sync.data.libgit2.push
import dev.jotalac.feature.git_sync.data.libgit2.remoteNames
import dev.jotalac.feature.git_sync.data.libgit2.resetHard
import dev.jotalac.feature.git_sync.data.libgit2.resolveConflict
import dev.jotalac.feature.git_sync.data.libgit2.setRemoteUrl
import dev.jotalac.feature.git_sync.data.libgit2.setUpstream
import dev.jotalac.feature.git_sync.data.libgit2.stagePath
import dev.jotalac.feature.git_sync.domain.GitSyncRepository
import dev.jotalac.feature.git_sync.domain.GitSyncStatus
import dev.jotalac.feature.git_sync.domain.VAULT_GIT_EMAIL
import dev.jotalac.feature.git_sync.domain.VAULT_GIT_NAME
import git2.git_libgit2_init
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeByteArray

private const val DEFAULT_REMOTE = "origin"

// one operation at a time - libgit2 must not work on the same repository from two threads
private val GitDispatcher = Dispatchers.IO.limitedParallelism(1)

/** iOS [GitSyncRepository] on libgit2 (see the libgit2 package) - does what JGitSyncRepositoryImpl does. */
class LibGit2SyncRepositoryImpl(
    // null in tests without a settings store - then no proxy is ever applied
    private val settings: UserSettings? = null,
) : GitSyncRepository {

    init {
        // required before any other libgit2 call - it is counted, so another instance does no harm
        git_libgit2_init()
    }

    private val _syncStatus = MutableSharedFlow<GitSyncStatus>(extraBufferCapacity = 1)
    override val gitSyncStatus: Flow<GitSyncStatus> = _syncStatus.asSharedFlow()

    // the proxy config lives in the app settings; an incomplete one stays off (see ProxyConfig.isActive)
    private suspend fun proxy(): ProxyConfig? =
        settings?.userSettingsStateFlow?.first()?.proxy?.takeIf { it.isActive() }

    override suspend fun validateCredentials(
        repoUrl: String,
        tokenOrPassword: String,
        username: String?,
    ): Result<Unit> = runGit {
        checkRemoteAccess(repoUrl, GitCredentials(username, tokenOrPassword), proxy())
    }

    override suspend fun initRepository(currentNotebookPath: String): Result<Unit> = runGit {
        GitRepository.init(currentNotebookPath, initialBranch = "main").close()
    }

    override suspend fun cloneRepository(
        repoUrl: String,
        tokenOrPassword: String,
        destinationPath: String,
        username: String?,
    ): Result<Unit> = runGit {
        GitRepository.clone(repoUrl, destinationPath, GitCredentials(username, tokenOrPassword), proxy()).close()
    }

    override suspend fun syncNotes(
        currentNotebookPath: String,
        tokenOrPassword: String,
        username: String?,
        commitMessage: String,
    ): Result<GitSyncStatus> {
        _syncStatus.tryEmit(GitSyncStatus.Syncing)

        return runGit {
            GitRepository.open(currentNotebookPath).use { repo ->
                val credentials = GitCredentials(username, tokenOrPassword)
                val proxy = proxy()
                repo.commitAll(commitMessage)
                repo.fetch(DEFAULT_REMOTE, credentials, proxy)

                when (repo.mergeFetched(DEFAULT_REMOTE)) {
                    MergeResult.Conflicts -> GitSyncStatus.Conflict(repo.conflictedFiles())
                    MergeResult.UpToDate, MergeResult.Merged -> {
                        if (repo.hasUnpushedCommits(DEFAULT_REMOTE)) repo.push(DEFAULT_REMOTE, credentials, proxy = proxy)
                        GitSyncStatus.UpToDate
                    }
                }
            }
        }
            .onSuccess { _syncStatus.tryEmit(it) }
            // also on failure, so observers do not stay on Syncing
            .onFailure { _syncStatus.tryEmit(GitSyncStatus.GitSyncFailed) }
    }

    override suspend fun resolveSingleConflict(
        currentNotebookPath: String,
        conflictedFilePath: String,
        keepLocalChanges: Boolean,
    ): Result<Unit> = runGit {
        GitRepository.open(currentNotebookPath).use { repo ->
            repo.resolveConflict(conflictedFilePath, keepLocalChanges)
            // that was the last one - the merge can be completed
            if (!repo.hasConflicts()) repo.commit("Resolved conflict")
        }
    }.onSuccess { _syncStatus.tryEmit(GitSyncStatus.UpToDate) }

    override suspend fun resolveAllConflicts(
        currentNotebookPath: String,
        keepLocalChanges: Boolean,
    ): Result<Unit> = runGit {
        GitRepository.open(currentNotebookPath).use { repo ->
            val conflictedFiles = repo.conflictedFiles().ifEmpty { return@use }
            conflictedFiles.forEach { repo.resolveConflict(it, keepLocalChanges) }
            repo.commit("Resolved all conflicts - keep ${if (keepLocalChanges) "local" else "remote"}")
        }
    }.onSuccess { _syncStatus.tryEmit(GitSyncStatus.UpToDate) }

    override suspend fun pushChanges(
        currentNotebookPath: String,
        tokenOrPassword: String,
        username: String?,
    ): Result<Unit> = runGit {
        GitRepository.open(currentNotebookPath).use { repo ->
            repo.push(DEFAULT_REMOTE, GitCredentials(username, tokenOrPassword), proxy = proxy())
        }
    }.onSuccess { _syncStatus.tryEmit(GitSyncStatus.UpToDate) }

    override suspend fun updateRemoteUrl(currentNotebookPath: String, newRemoteUrl: String): Result<Unit> = runGit {
        GitRepository.open(currentNotebookPath).use { repo ->
            if (repo.remoteNames().isEmpty()) {
                repo.addRemote(DEFAULT_REMOTE, newRemoteUrl)
                repo.setUpstream(DEFAULT_REMOTE)
            } else {
                repo.setRemoteUrl(DEFAULT_REMOTE, newRemoteUrl)
            }
        }
    }

    override suspend fun removeRemote(currentNotebookPath: String): Result<Unit> = runGit {
        GitRepository.open(currentNotebookPath).use { repo ->
            val remote = repo.remoteNames().firstOrNull() ?: return@use
            repo.deleteRemote(remote)
        }
    }

    override suspend fun abortMerge(currentNotebookPath: String): Result<Unit> = runGit {
        GitRepository.open(currentNotebookPath).use { repo ->
            // only during a merge - otherwise it would throw away notes that are not committed yet
            if (repo.isMerging) repo.resetHard()
        }
    }

    override suspend fun resetHistoryToCurrentState(currentNotebookPath: String, commitMessage: String): Result<Unit> =
        runGit {
            GitRepository.open(currentNotebookPath).use { repo ->
                val branch = repo.currentBranch ?: throw GitException("HEAD is detached, there is no branch to rewrite")
                repo.stageEverything()
                // a commit without parents on the branch: everything before it leaves the history
                repo.commitOrphan(commitMessage, branch)
            }
        }

    override suspend fun forcePush(
        currentNotebookPath: String,
        tokenOrPassword: String,
        username: String?,
    ): Result<Unit> = runGit {
        GitRepository.open(currentNotebookPath).use { repo ->
            repo.push(DEFAULT_REMOTE, GitCredentials(username, tokenOrPassword), force = true, proxy = proxy())
        }
    }

    override suspend fun resetToRemote(
        currentNotebookPath: String,
        tokenOrPassword: String,
        username: String?,
    ): Result<Unit> = runGit {
        GitRepository.open(currentNotebookPath).use { repo ->
            val branch = repo.currentBranch ?: throw GitException("HEAD is detached, there is no branch to reset")
            repo.fetch(DEFAULT_REMOTE, GitCredentials(username, tokenOrPassword), proxy())
            // moves the branch onto the remote commit and throws the local worktree state away
            repo.resetHard("refs/remotes/$DEFAULT_REMOTE/$branch")
        }
    }

    override suspend fun getConflictVersions(
        currentNotebookPath: String,
        conflictedFilePath: String,
    ): Result<Map<Int, ByteArray>> = runGit {
        GitRepository.open(currentNotebookPath).use { repo ->
            repo.conflictVersions(conflictedFilePath)
        }
    }

    override suspend fun stageFile(
        currentNotebookPath: String,
        filePath: String,
        content: ByteArray,
    ): Result<Unit> = runGit {
        GitRepository.open(currentNotebookPath).use { repo ->
            val file = Path(repo.workdir, filePath)
            file.parent?.let { SystemFileSystem.createDirectories(it) }
            SystemFileSystem.sink(file).buffered().use { it.writeByteArray(content) }
            // staging also resolves a conflict on the file, like `git add` does
            repo.stagePath(filePath)
        }
    }

    override suspend fun untrackFile(currentNotebookPath: String, relativePath: String): Result<Boolean> = runGit {
        GitRepository.open(currentNotebookPath).use { repo ->
            // removes the index entry only - the working-tree file stays on the device
            repo.untrackPath(relativePath)
        }
    }

    override suspend fun setRepoIdentity(currentNotebookPath: String): Result<Unit> = runGit {
        GitRepository.open(currentNotebookPath).use { repo ->
            repo.setConfig("user.name", VAULT_GIT_NAME)
            repo.setConfig("user.email", VAULT_GIT_EMAIL)
        }
    }

    override suspend fun commitStaged(currentNotebookPath: String, commitMessage: String): Result<Unit> = runGit {
        GitRepository.open(currentNotebookPath).use { repo ->
            // commit() makes MERGE_HEAD the second parent and clears the merge state
            repo.commit(commitMessage)
        }
    }

    override fun updateSyncStatus(remoteUrl: String?, syncStatus: GitSyncStatus?): Result<Unit> = runCatching {
        _syncStatus.tryEmit(
            syncStatus ?: if (remoteUrl == null) GitSyncStatus.GitSyncNotConfigured else GitSyncStatus.UpToDate
        )
    }

    private suspend fun <T> runGit(block: () -> T): Result<T> = withContext(GitDispatcher) {
        suspendRunCatching(block)
    }
}
