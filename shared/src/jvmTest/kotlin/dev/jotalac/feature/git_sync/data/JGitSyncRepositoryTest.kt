package dev.jotalac.feature.git_sync.data

import org.eclipse.jgit.api.Git
import java.io.File

class JGitSyncRepositoryTest : GitSyncRepositoryContractTest() {

    override val repository = JGitSyncRepositoryImpl()

    override fun createRemote(path: String) {
        // JGit cannot clone an empty repository, so the remote is cloned from a seed with one commit
        val seed = File("$path-seed")
        Git.init().setDirectory(seed).setInitialBranch("main").call().use { git ->
            File(seed, "README.md").writeText("README")
            git.add().addFilepattern(".").call()
            git.commit().setMessage("README").call()
        }
        Git.cloneRepository().setBare(true).setURI(seed.toURI().toString()).setDirectory(File(path)).call().close()
    }

    override fun createEmptyRemote(path: String) {
        Git.init().setBare(true).setDirectory(File(path)).setInitialBranch("main").call().close()
    }

    override fun commitWithoutPushing(path: String) {
        Git.open(File(path)).use { git ->
            git.add().addFilepattern(".").call()
            git.commit().setMessage("not pushed").call()
        }
    }
}
