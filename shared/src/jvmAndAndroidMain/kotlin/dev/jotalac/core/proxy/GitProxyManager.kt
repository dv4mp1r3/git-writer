package dev.jotalac.core.proxy

import dev.jotalac.core.data.UserSettings
import dev.jotalac.core.domain.ProxyConfig
import dev.jotalac.core.domain.ProxyProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * Routes the app's network traffic through the user-configured proxy.
 *
 * JGit talks over JDK HttpURLConnection, which consults the default ProxySelector per connection, and
 * OkHttp (ktor client, image loading) picks the same selector up when its client is built. So installing
 * the selector once and swapping the config inside it is enough - the git code itself stays untouched.
 *
 * Created eagerly by Koin before the first HttpClient is built: OkHttp captures whatever selector is the
 * default at that moment and keeps it forever, so the identity of the selector must never change.
 */
class GitProxyManager(settings: UserSettings) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val selector = GitProxySelector()

    init {
        ProxySelector.setDefault(selector)
        Authenticator.setDefault(GitProxyAuthenticator(selector))

        scope.launch {
            // the first read can run before FileKit init (desktop) or without a DataStore file at all
            // (Koin graph test) - retry until the settings flow starts producing
            while (true) {
                try {
                    settings.userSettingsStateFlow.collect { state ->
                        selector.config = state.proxy.takeIf { it.isActive() }
                    }
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    delay(PROXY_SETTINGS_RETRY_MS)
                }
            }
        }
    }

    private companion object {
        const val PROXY_SETTINGS_RETRY_MS = 5_000L
    }
}

internal class GitProxySelector : ProxySelector() {
    @Volatile
    var config: ProxyConfig? = null

    override fun select(uri: URI?): List<Proxy> {
        val config = config?.takeIf { it.isActive() } ?: return listOf(Proxy.NO_PROXY)
        val host = uri?.host ?: return listOf(Proxy.NO_PROXY)
        // URI reports IPv6 hosts bracketed
        if (host == LOCALHOST || host == IPV4_LOOPBACK || host == IPV6_LOOPBACK || host == IPV6_LOOPBACK_BRACKETED) {
            return listOf(Proxy.NO_PROXY)
        }

        val address = InetSocketAddress.createUnresolved(config.host, config.port) // no DNS on the calling thread
        val type = if (config.protocol == ProxyProtocol.HTTP) Proxy.Type.HTTP else Proxy.Type.SOCKS
        return listOf(Proxy(type, address))
    }

    // a failing proxy must not silently fall back to a direct connection - the user asked for the proxy
    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit

    private companion object {
        const val LOCALHOST = "localhost"
        const val IPV4_LOOPBACK = "127.0.0.1"
        const val IPV6_LOOPBACK = "::1"
        const val IPV6_LOOPBACK_BRACKETED = "[::1]"
    }
}

internal class GitProxyAuthenticator(private val selector: GitProxySelector) : Authenticator() {
    override fun getPasswordAuthentication(): PasswordAuthentication? {
        // answering for the server itself would interfere with JGit's own credentials handling
        if (requestorType != RequestorType.PROXY) return null

        val config = selector.config ?: return null
        if (config.username.isBlank() && config.password.isBlank()) return null

        return PasswordAuthentication(
            config.username.ifBlank { config.password },
            config.password.toCharArray()
        )
    }
}
