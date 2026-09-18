package me.ethanxu.typesafe.sdk

import io.ktor.client.HttpClient

/**
 * Builds the default [HttpClient].
 *
 * Declared `expect` because the HTTP engine is platform-specific: Android and
 * the JVM use the OkHttp engine, which `commonMain` cannot reference. Tests
 * inject a Ktor `MockEngine` through the constructor instead, so they never
 * reach this function.
 *
 * The implementation must set `expectSuccess = false` so that 4xx/5xx come back
 * as ordinary responses and [TypeSafeClient] can dispatch the exception type
 * itself, which is what reproduces the upstream status-code mapping.
 *
 * A null [proxy] means a direct connection.
 */
internal expect fun defaultHttpClient(timeoutMs: Long, proxy: ProxySpec?): HttpClient
