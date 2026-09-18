package me.ethanxu.typesafe.sdk

/**
 * The kind of proxy to route through.
 *
 * There are only two underlying implementations, which is not the same as the
 * three options a typical settings screen shows:
 *
 * - [HTTP]: an HTTP proxy. **HTTPS destinations go through it too**, tunnelled
 *   with `CONNECT`, which is the standard behaviour of an HTTP proxy. There is
 *   no separate "HTTPS proxy" protocol.
 * - [SOCKS]: a SOCKS5 proxy.
 *
 * Listing HTTP and HTTPS separately in a UI is just a nod to common naming;
 * both map onto the same implementation.
 */
enum class ProxyKind {
    HTTP,
    SOCKS,
}

/** A usable proxy configuration. A null [ProxySpec] means a direct connection. */
data class ProxySpec(
    val kind: ProxyKind,
    val host: String,
    val port: Int,
    /** Proxy username; blank means the proxy requires no authentication. */
    val username: String = "",
    val password: String = "",
) {
    val hasCredentials: Boolean get() = username.isNotBlank()
}
