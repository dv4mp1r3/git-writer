package dev.jotalac.feature.git_sync.data

import dev.jotalac.feature.git_sync.data.libgit2.GitCredentials
import dev.jotalac.feature.git_sync.data.libgit2.GitRepository
import dev.jotalac.feature.git_sync.data.libgit2.addRemote
import dev.jotalac.feature.git_sync.data.libgit2.commitAll
import dev.jotalac.feature.git_sync.data.libgit2.push
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString

class LibGit2SyncRepositoryTest : GitSyncRepositoryContractTest() {

    override val repository = LibGit2SyncRepositoryImpl()

    override fun createRemote(path: String) {
        createEmptyRemote(path)
        GitRepository.init("$path-seed", initialBranch = "main").use { seed ->
            SystemFileSystem.sink(Path(seed.workdir, "README.md")).buffered().use { it.writeString("README") }
            seed.commitAll("README")
            seed.addRemote("origin", path)
            seed.push("origin", GitCredentials("user", "token"))
        }
    }

    override fun createEmptyRemote(path: String) {
        GitRepository.init(path, initialBranch = "main", bare = true).close()
    }

    override fun commitWithoutPushing(path: String) {
        GitRepository.open(path).use { it.commitAll("not pushed") }
    }
}
