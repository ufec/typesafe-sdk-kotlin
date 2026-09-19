package me.ethanxu.typesafe.sdk

/** Log levels, ordered from most to least verbose. Mirrors upstream `LOG_LEVELS`. */
enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR,
    OFF,
}

/** Default log level. Mirrors upstream `DEFAULT_LOG_LEVEL`. */
val DEFAULT_LOG_LEVEL: LogLevel = LogLevel.WARN

/**
 * Sink for log output.
 *
 * Upstream writes straight to `console`. On Android the natural target is
 * `android.util.Log`, which this module has no opinion about, so only the
 * interface lives here and the host application supplies the implementation.
 */
interface TypeSafeLogger {
    fun debug(message: String)

    fun info(message: String)

    fun warn(message: String, throwable: Throwable? = null)

    fun error(message: String, throwable: Throwable? = null)
}

/** A logger that discards everything. The SDK default: silence beats an unexpected logcat line. */
object NoOpLogger : TypeSafeLogger {
    override fun debug(message: String) = Unit

    override fun info(message: String) = Unit

    override fun warn(message: String, throwable: Throwable?) = Unit

    override fun error(message: String, throwable: Throwable?) = Unit
}

/**
 * Wraps a logger so that calls below [level] become no-ops.
 * Mirrors upstream `withLevel`.
 */
internal fun TypeSafeLogger.atLevel(level: LogLevel): TypeSafeLogger {
    if (level == LogLevel.OFF) return NoOpLogger
    val rank = level.ordinal
    return object : TypeSafeLogger {
        override fun debug(message: String) {
            if (LogLevel.DEBUG.ordinal >= rank) this@atLevel.debug(message)
        }

        override fun info(message: String) {
            if (LogLevel.INFO.ordinal >= rank) this@atLevel.info(message)
        }

        override fun warn(message: String, throwable: Throwable?) {
            if (LogLevel.WARN.ordinal >= rank) this@atLevel.warn(message, throwable)
        }

        override fun error(message: String, throwable: Throwable?) {
            if (LogLevel.ERROR.ordinal >= rank) this@atLevel.error(message, throwable)
        }
    }
}

/** Headers whose tail is kept so the credential can still be identified. */
private val KEY_HEADERS: Set<String> = setOf("authorization", "proxy-authorization", "x-api-key")

/** Headers masked in full. */
private val OPAQUE_HEADERS: Set<String> = setOf("cookie", "set-cookie")

/**
 * Masks a credential value: the scheme is kept, and the last four characters
 * are kept when the secret is longer than eight characters.
 * Mirrors upstream `redactKey`.
 */
private fun redactKey(value: String): String {
    val parts = value.split(Regex("\\s+"), limit = 2)
    val scheme = if (parts.size == 2) parts[0] else null
    val secret = if (parts.size == 2) parts[1] else parts[0]
    val tail = if (secret.length > 8) secret.takeLast(4) else ""
    return (scheme?.let { "$it " } ?: "") + "***" + tail
}

/** Copies a header map, masking the known credential headers. Mirrors upstream `redactHeaders`. */
internal fun redactHeaders(headers: Map<String, String>): Map<String, String> =
    headers.mapValues { (name, value) ->
        when (name.lowercase()) {
            in KEY_HEADERS -> redactKey(value)
            in OPAQUE_HEADERS -> "***"
            else -> value
        }
    }
