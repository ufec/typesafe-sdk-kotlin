package me.ethanxu.typesafe.sdk

import io.ktor.client.HttpClient
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.engine.http
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import okhttp3.Credentials

internal actual fun defaultHttpClient(timeoutMs: Long, proxy: ProxySpec?): HttpClient =
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
