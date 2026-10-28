package dev.jotalac.feature.vault.data

import dev.jotalac.feature.git_sync.data.LibGit2SyncRepositoryImpl
import dev.jotalac.feature.git_sync.data.libgit2.GitRepository

/** The vault sync contract on libgit2 - the iOS git implementation. */
class LibGit2VaultSyncTest : VaultSyncContractTest() {

    override val git = LibGit2SyncRepositoryImpl()

    override fun createEmptyRemote(path: String) {
        GitRepository.init(path, initialBranch = "main", bare = true).close()
    }
}
