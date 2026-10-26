package dev.jotalac.core.proxy

import dev.jotalac.core.domain.ProxyConfig
import dev.jotalac.core.domain.ProxyProtocol
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GitProxySelectorTest {

    private val selector = GitProxySelector()

    private fun config(protocol: ProxyProtocol = ProxyProtocol.HTTP) = ProxyConfig(
        enabled = true,
        protocol = protocol,
        host = "proxy.example.com",
        port = 8080,
    )

    @Test
    fun `without a config everything connects directly`() {
        selector.config = null

        assertEquals(listOf(Proxy.NO_PROXY), selector.select(URI("https://github.com/octocat")))
    }

    @Test
    fun `a disabled config is ignored`() {
        selector.config = config().copy(enabled = false)

        assertEquals(listOf(Proxy.NO_PROXY), selector.select(URI("https://github.com/octocat")))
    }

    @Test
    fun `an http proxy is returned as a Proxy of type HTTP`() {
        selector.config = config(ProxyProtocol.HTTP)

        val proxies = selector.select(URI("https://github.com/octocat"))

        assertEquals(1, proxies.size)
        assertEquals(Proxy.Type.HTTP, proxies.first().type())
        assertEquals(InetSocketAddress.createUnresolved("proxy.example.com", 8080), proxies.first().address())
    }

    @Test
    fun `a socks5 proxy is returned as a Proxy of type SOCKS`() {
        selector.config = config(ProxyProtocol.SOCKS5)

        val proxies = selector.select(URI("https://github.com/octocat"))

        assertEquals(Proxy.Type.SOCKS, proxies.single().type())
    }

    @Test
    fun `the proxy address is not resolved eagerly`() {
        selector.config = config()

        val address = selector.select(URI("https://github.com/octocat")).single().address() as InetSocketAddress

        assertTrue(address.isUnresolved)
    }

    @Test
    fun `loopback targets bypass the proxy`() {
        selector.config = config()

        assertEquals(listOf(Proxy.NO_PROXY), selector.select(URI("http://localhost:1234/x")))
        assertEquals(listOf(Proxy.NO_PROXY), selector.select(URI("http://127.0.0.1:1234/x")))
        // URI reports IPv6 hosts bracketed
        assertEquals(listOf(Proxy.NO_PROXY), selector.select(URI("http://[::1]:1234/x")))
    }

    @Test
    fun `a connect failure is swallowed - the proxy must not fall back to a direct connection`() {
        // the caller (the JDK) reports the original failure itself; the selector must not throw
        selector.connectFailed(URI("https://github.com"), InetSocketAddress.createUnresolved("proxy.example.com", 8080), null)
    }
}
