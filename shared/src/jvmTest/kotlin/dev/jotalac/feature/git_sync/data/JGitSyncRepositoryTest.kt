package dev.jotalac.feature.git_sync.data

import dev.jotalac.feature.git_sync.domain.GitSyncStatus
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/*
* Runs against real git repositories in a temp folder: a bare "remote" and clones of it standing in for
* two devices. A local remote needs no credentials, the token is only passed through.
* */
class JGitSyncRepositoryTest {

    private val root = Files.createTempDirectory("git-sync-test").toFile()
    private val remote = File(root, "remote.git")
    private val repository = JGitSyncRepositoryImpl()
    private val openRepositories = mutableListOf<Git>()

    @BeforeTest
    fun createRemote() {
        // JGit cannot clone an empty repository, so the remote starts with one commit
        val seed = open(Git.init().setDirectory(File(root, "seed")).setInitialBranch("main").call())
        seed.commitFile("README.md")

        Git.cloneRepository()
            .setBare(true)
            .setURI(seed.repository.directory.toURI().toString())
            .setDirectory(remote)
            .call()
            .close()
    }

    @AfterTest
    fun cleanUp() {
        openRepositories.forEach { it.close() }
        root.deleteRecursively()
    }

    private fun open(git: Git): Git = git.also { openRepositories += it }

    private fun device(name: String): Git = open(
        Git.cloneRepository().setURI(remote.toURI().toString()).setDirectory(File(root, name)).call()
    )

    private val Git.path: String
        get() = repository.workTree.path

    private fun Git.writeFile(name: String) = File(repository.workTree, name).writeText(name)

    private fun Git.commitFile(name: String) {
        writeFile(name)
        add().addFilepattern(name).call()
        commit().setMessage(name).call()
    }

    private fun remoteHasFile(name: String): Boolean = File(device("check-${openRepositories.size}").path, name).exists()

    private fun sync(device: Git) = runBlocking { repository.syncNotes(device.path, "token", "user", "sync") }

    private fun push(device: Git) = runBlocking { repository.pushChanges(device.path, "token", "user") }

    @Test
    fun `sync uploads local notes and downloads the notes of other devices`() {
        val laptop = device("laptop")
        val phone = device("phone")
        phone.writeFile("from-phone.md")
        sync(phone).getOrThrow()

        // not committed - sync commits it
        laptop.writeFile("from-laptop.md")
        val result = sync(laptop)

        assertEquals(GitSyncStatus.UpToDate, result.getOrThrow())
        assertTrue(File(laptop.path, "from-phone.md").exists())
        assertTrue(remoteHasFile("from-laptop.md"))
    }

    @Test
    fun `sync with nothing new succeeds`() {
        assertEquals(GitSyncStatus.UpToDate, sync(device("laptop")).getOrThrow())
    }

    @Test
    fun `a push the remote rejects fails instead of reporting success`() {
        // the remote has a commit the laptop does not, so it refuses the laptop's push - JGit does not
        // throw for that, it only reports it in the push result
        val laptop = device("laptop")
        val phone = device("phone")
        phone.writeFile("from-phone.md")
        sync(phone).getOrThrow()
        laptop.commitFile("from-laptop.md")

        val result = push(laptop)

        assertTrue(result.isFailure)
        assertContains(result.exceptionOrNull()?.message.orEmpty(), "REJECTED_NONFASTFORWARD")
        assertFalse(remoteHasFile("from-laptop.md"))
    }

    @Test
    fun `pushing with nothing new succeeds`() {
        // an up to date branch is reported with its own status, which must not count as a rejection
        assertTrue(push(device("laptop")).isSuccess)
    }
}
