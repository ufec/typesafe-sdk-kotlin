package me.ethanxu.typesafe.sdk

/** Default API root. Mirrors upstream `DEFAULT_BASE_URL`. */
const val DEFAULT_BASE_URL: String = "https://api.typesafe.ai"

/** Default model. Mirrors upstream `DEFAULT_MODEL`. */
const val DEFAULT_MODEL: String = "jev-latest"

/**
 * Version of this Kotlin SDK. The upstream version it ports is
 * `@typesafe-ai/sdk@0.6.0` (MIT).
 *
 * See the NOTICE and LICENSE files at the repository root for attribution.
 */
const val SDK_VERSION: String = "0.1.0"

internal const val SDK_USER_AGENT: String = "typesafe-sdk-kotlin/$SDK_VERSION"

/**
 * Default runtime descriptor reported with each request.
 *
 * `commonMain` cannot read a platform version (`System.getProperty` is
 * JVM-only), so the value stays neutral here. Callers on Android should pass
 * something like `android/<Build.VERSION.RELEASE>` through
 * [TypeSafeConfig.runtimeDescriptor] to make the telemetry more useful.
 */
internal const val DEFAULT_RUNTIME_DESCRIPTOR: String = "kotlin/unknown"

/**
 * Client configuration.
 *
 * One difference from upstream: upstream reads `TYPESAFE_API_KEY` and friends
 * from `process.env`, which has no equivalent on Android. This SDK therefore
 * **only accepts explicit values** and never reads the ambient environment.
 *
 * @throws TypeSafeException `apiKey` is blank, or `timeoutMs` is not positive.
 */
data class TypeSafeConfig(
    /** Required. Upstream falls back to an environment variable; this SDK does not. */
    val apiKey: String,
    val baseUrl: String = DEFAULT_BASE_URL,
    val defaultModel: String = DEFAULT_MODEL,
    /** Anything below this level is dropped. */
    val logLevel: LogLevel = DEFAULT_LOG_LEVEL,
    /** Log sink; silent by default. */
    val logger: TypeSafeLogger = NoOpLogger,
    val retry: RetryPolicy = RetryPolicy(),
    /** Timeout for a single attempt, in milliseconds. Excludes the overall retry budget. */
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    /** Extra request headers. The SDK's own headers win on conflict, matching upstream. */
    val defaultHeaders: Map<String, String> = emptyMap(),
    val runtimeDescriptor: String = DEFAULT_RUNTIME_DESCRIPTOR,
    /** Network proxy; null means a direct connection. */
    val proxy: ProxySpec? = null,
) {
    init {
        if (apiKey.isBlank()) {
            throw TypeSafeException(
                "No API key was provided. Pass `apiKey` to TypeSafeConfig.",
            )
        }
        if (timeoutMs <= 0) {
            throw TypeSafeException("TypeSafeConfig.timeoutMs must be positive, got $timeoutMs.")
        }
    }

    /** Trailing slashes removed so path concatenation cannot produce a double slash. Mirrors upstream `stripTrailingSlashes`. */
    internal val normalizedBaseUrl: String = baseUrl.trimEnd('/')
}
