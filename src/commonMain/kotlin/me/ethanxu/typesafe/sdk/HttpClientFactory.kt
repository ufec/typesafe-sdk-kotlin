package me.ethanxu.typesafe.sdk

import io.ktor.client.HttpClient
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.engine.http
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import okhttp3.Credentials

/**
 * Builds the default [HttpClient] used when the caller does not inject one.
 *
 * This sits in `commonMain` rather than behind an `expect`/`actual` pair because
 * OkHttp is the engine on every target this library declares. That is worth
 * spelling out: it was originally split across targets, and when a JVM target
 * was added the obvious move was a shared intermediate source set. Kotlin does
 * not allow that -- the language reference states outright that sharing a source
 * set between JVM and Android targets is unsupported -- so the shared `actual`
 * was not merely awkward to wire up, it was impossible. Keeping one implementation
 * in `commonMain` is both simpler and honest about the situation.
 *
 * The cost is that adding a non-JVM target (iOS, say) breaks this file, because
 * `ktor-client-okhttp` has no variant for it. That failure is immediate and
 * points straight at the engine, which is the right moment to learn about it.
 *
 * The client must be built with `expectSuccess = false` so that 4xx/5xx come back
 * as ordinary responses and [TypeSafeClient] can dispatch the exception type
 * itself, which is what reproduces the upstream status-code mapping.
 *
 * A null [proxy] means a direct connection.
 */
internal fun defaultHttpClient(timeoutMs: Long, proxy: ProxySpec?): HttpClient =
    HttpClient(OkHttp) {
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = timeoutMs
            connectTimeoutMillis = timeoutMs
            socketTimeoutMillis = timeoutMs
        }
        if (proxy != null) {
            engine {
                // Ktor 3.x uses the ProxyBuilder factory rather than a chained
                // builder: HTTP goes through a URL, SOCKS through host/port.
                //
                // An HTTP proxy automatically switches to a CONNECT tunnel for
                // HTTPS destinations, so "HTTP proxy" and "HTTPS proxy" are the
                // same configuration (see the ProxySpec docs).
                this.proxy = when (proxy.kind) {
                    ProxyKind.HTTP -> ProxyBuilder.http("http://${proxy.host}:${proxy.port}")
                    ProxyKind.SOCKS -> ProxyBuilder.socks(proxy.host, proxy.port)
                }

                if (proxy.hasCredentials) {
                    config {
                        // Ktor's ProxyConfig carries no credentials (on the JVM it is
                        // simply java.net.Proxy), so authentication has to drop down to
                        // OkHttp: on a 407 response, add Proxy-Authorization and retry.
                        proxyAuthenticator { _, response ->
                            // The header already being present means a retry was made
                            // and was rejected again, so retrying once more would loop.
                            if (response.request.header("Proxy-Authorization") != null) {
                                null
                            } else {
                                response.request.newBuilder()
                                    .header(
                                        "Proxy-Authorization",
                                        Credentials.basic(proxy.username, proxy.password),
                                    )
                                    .build()
                            }
                        }
                    }
                }
            }
        }
    }
