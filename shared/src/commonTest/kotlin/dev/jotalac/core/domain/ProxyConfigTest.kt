package dev.jotalac.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProxyConfigTest {

    private val valid = ProxyConfig(
        enabled = true,
        protocol = ProxyProtocol.HTTP,
        host = "proxy.example.com",
        port = 8080,
    )

    @Test
    fun `an active config is enabled with a host and a valid port`() {
        assertTrue(valid.isActive())
    }

    @Test
    fun `a disabled config is not active`() {
        assertFalse(valid.copy(enabled = false).isActive())
    }

    @Test
    fun `a blank host is not active`() {
        assertFalse(valid.copy(host = " ").isActive())
    }

    @Test
    fun `port 0 means not set and is not active`() {
        assertFalse(valid.copy(port = 0).isActive())
    }

    @Test
    fun `a port above 65535 is not active`() {
        assertFalse(valid.copy(port = 65536).isActive())
    }

    @Test
    fun `the proxy url is http with the host and port`() {
        assertEquals("http://proxy.example.com:8080", valid.toProxyUrl())
    }
}
