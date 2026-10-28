package dev.jotalac.core.domain

enum class ProxyProtocol {
    HTTP,
    SOCKS5
}

data class ProxyConfig(
    val enabled: Boolean = false,
    val protocol: ProxyProtocol = ProxyProtocol.HTTP,
    val host: String = "",
    val port: Int = 0,
    val username: String = "",
    val password: String = "",
) {
    /** Only a complete configuration is applied - half-typed fields must not break the sync. */
    fun isActive(): Boolean = enabled && host.isNotBlank() && port in 1..65535

    /** The URL libgit2 expects in git_proxy_options.url (the JDK path passes host/port separately). */
    fun toProxyUrl(): String = "http://$host:$port"
}
