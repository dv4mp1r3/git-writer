package dev.jotalac.feature.vault.data

import dev.jotalac.feature.git_sync.data.JGitSyncRepositoryImpl
import org.eclipse.jgit.api.Git
import java.io.File

/** The vault sync contract on JGit - the desktop and Android git implementation. */
class JGitVaultSyncTest : VaultSyncContractTest() {

    override val git = JGitSyncRepositoryImpl()

    override fun createEmptyRemote(path: String) {
        Git.init().setBare(true).setDirectory(File(path)).setInitialBranch("main").call().close()
    }
}
