package dev.jotalac.core.utils

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteStatusMonitorTest {

    private val remote = "https://github.com/me/notes.git"

    private val remoteUrl = MutableStateFlow<String?>(remote)
    /** what the OS says - null on desktop, where it cannot tell */
    private val network = MutableStateFlow<Boolean?>(true)
    private var serverAnswers = true
    private val checkedUrls = mutableListOf<String>()

    private val monitor = RemoteStatusMonitor(
        reachability = { url -> checkedUrls += url; serverAnswers },
        networkMonitor = { network },
    )

    /** The latest status, kept up to date like the editor collects it. */
    private fun TestScope.watch(): () -> RemoteStatus? {
        var latest: RemoteStatus? = null
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            monitor.status(remoteUrl).collect { latest = it }
        }
        return { latest }
    }

    @Test
    fun `a remote that answers is reachable`() = runTest {
        val status = watch()

        assertEquals(RemoteStatus.REACHABLE, status())
        assertEquals(listOf(remote), checkedUrls)
    }

    @Test
    fun `a remote that does not answer while the network is up is unreachable`() = runTest {
        serverAnswers = false

        assertEquals(RemoteStatus.UNREACHABLE, watch()())
    }

    @Test
    fun `without a network the remote is offline and no request is made`() = runTest {
        network.value = false

        assertEquals(RemoteStatus.OFFLINE, watch()())
        assertTrue(checkedUrls.isEmpty())
    }

    @Test
    fun `when the network comes back the remote is checked again`() = runTest {
        network.value = false
        val status = watch()

        network.value = true

        assertEquals(RemoteStatus.REACHABLE, status())
        assertEquals(listOf(remote), checkedUrls)
    }

    @Test
    fun `on desktop where the OS cannot tell the remote is still checked`() = runTest {
        network.value = null

        assertEquals(RemoteStatus.REACHABLE, watch()())
    }

    @Test
    fun `checking again picks up a server that came back`() = runTest {
        serverAnswers = false
        val status = watch()

        serverAnswers = true
        monitor.checkNow()

        assertEquals(RemoteStatus.REACHABLE, status())
    }

    @Test
    fun `nothing is checked for a notebook without a remote`() = runTest {
        remoteUrl.value = null

        assertNull(watch()())
        assertTrue(checkedUrls.isEmpty())
    }

    @Test
    fun `opening a notebook with another remote checks that one`() = runTest {
        val status = watch()

        serverAnswers = false
        remoteUrl.value = "https://gitlab.example.com/me/other.git"

        assertEquals(RemoteStatus.UNREACHABLE, status())
        assertEquals(listOf(remote, "https://gitlab.example.com/me/other.git"), checkedUrls)
    }
}
