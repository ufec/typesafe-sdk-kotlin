package me.ethanxu.typesafe.sdk

import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

/** Default timeout for a single attempt, in milliseconds. Mirrors upstream `DEFAULT_TIMEOUT_MS`. */
const val DEFAULT_TIMEOUT_MS: Long = 10_000

/** Default retryable statuses: 408, 429, and every 5xx. Mirrors the upstream defaults. */
val DEFAULT_RETRYABLE_STATUSES: Set<Int> = buildSet {
    add(408)
    add(429)
    addAll(500..599)
}

/**
 * Retry policy. Every field and default **matches upstream `DEFAULT_RETRY_POLICY`**.
 *
 * @throws TypeSafeException any field holds an invalid value.
 */
data class RetryPolicy(
    /** Maximum retries beyond the first attempt; 0 disables retrying. */
    val maxRetries: Int = 2,
    /** Initial backoff delay in milliseconds; doubles on each attempt up to [backoffMaxMs]. */
    val backoffInitialMs: Long = 500,
    /** Ceiling for the backoff delay, in milliseconds. */
    val backoffMaxMs: Long = 5_000,
    /** Fraction of each backoff randomly subtracted, 0-1. */
    val backoffJitter: Double = 0.25,
    /** HTTP status codes that trigger a retry. */
    val httpStatuses: Set<Int> = DEFAULT_RETRYABLE_STATUSES,
    /** Whether the server's `Retry-After` / `retry-after-ms` takes precedence. */
    val respectRetryAfter: Boolean = true,
    /** Ceiling for a server-supplied delay, in milliseconds; above it the backoff algorithm is used instead. */
    val maxRetryAfterMs: Long = 60_000,
    /** Whether to retry connection failures. */
    val apiConnectionError: Boolean = true,
    /** Whether to retry timeouts. */
    val apiTimeoutError: Boolean = true,
) {
    init {
        if (maxRetries < 0) {
            throw TypeSafeException("RetryPolicy.maxRetries must be non-negative, got $maxRetries.")
        }
        if (backoffInitialMs < 0) {
            throw TypeSafeException(
                "RetryPolicy.backoffInitialMs must be non-negative, got $backoffInitialMs.",
            )
        }
        if (backoffMaxMs < 0) {
            throw TypeSafeException(
                "RetryPolicy.backoffMaxMs must be non-negative, got $backoffMaxMs.",
            )
        }
        if (backoffJitter < 0 || backoffJitter > 1) {
            throw TypeSafeException(
                "RetryPolicy.backoffJitter must be between 0 and 1, got $backoffJitter.",
            )
        }
        if (maxRetryAfterMs < 0) {
            throw TypeSafeException(
                "RetryPolicy.maxRetryAfterMs must be non-negative, got $maxRetryAfterMs.",
            )
        }
        httpStatuses.forEach { status ->
            if (status < 100 || status > 999) {
                throw TypeSafeException(
                    "RetryPolicy.httpStatuses must contain HTTP status codes, got $status.",
                )
            }
        }
    }

    /** Whether this status code may be retried. */
    fun isRetryableStatus(status: Int): Boolean = status in httpStatuses
}

/**
 * Parses `retry-after-ms` or `Retry-After` into milliseconds, preferring
 * `retry-after-ms`.
 *
 * `Retry-After` is accepted in both forms: a number of seconds, or an HTTP date.
 * Returns null when neither can be parsed.
 */
internal fun parseRetryAfter(
    headers: Map<String, String>,
    nowMs: Long,
): Long? {
    headers["retry-after-ms"]?.let { raw ->
        val ms = raw.trim().toDoubleOrNull()
        if (ms != null && ms.isFinite() && ms >= 0) return ms.toLong()
    }

    val raw = headers["retry-after"]?.trim() ?: return null
    raw.toDoubleOrNull()?.let { seconds ->
        return if (seconds >= 0) (seconds * 1000).toLong() else null
    }
    return parseHttpDateToEpochMillis(raw)?.let { maxOf(0L, it - nowMs) }
}

// ---------------------------------------------------------------------------
// HTTP-date parsing
//
// Upstream handles `Retry-After: <HTTP-date>` with `Date.parse(raw)`. Kotlin's
// commonMain has no java.time, and pulling in kotlinx-datetime for this single,
// rarely-taken path is not worth it, so the RFC 1123 format is parsed by hand.
// The date-to-epoch-seconds conversion uses Howard Hinnant's days_from_civil
// algorithm, which is exact integer arithmetic with no leap-year special cases.
// ---------------------------------------------------------------------------

private val RFC_1123_PATTERN = Regex(
    """(?:Mon|Tue|Wed|Thu|Fri|Sat|Sun),\s*(\d{1,2})\s*([A-Za-z]{3})\s*(\d{4})\s*""" +
        """(\d{1,2}):(\d{2}):(\d{2})\s*GMT""",
    RegexOption.IGNORE_CASE,
)

private val MONTH_INDEX: Map<String, Int> = listOf(
    "jan", "feb", "mar", "apr", "may", "jun",
    "jul", "aug", "sep", "oct", "nov", "dec",
).withIndex().associate { (index, name) -> name to index + 1 }

/** Parses an RFC 1123 HTTP date (e.g. `Sun, 06 Nov 1994 08:49:37 GMT`) into epoch milliseconds; null when unparseable. */
internal fun parseHttpDateToEpochMillis(raw: String): Long? {
    val match = RFC_1123_PATTERN.matchEntire(raw) ?: return null
    val day = match.groupValues[1].toIntOrNull() ?: return null
    val month = MONTH_INDEX[match.groupValues[2].lowercase()] ?: return null
    val year = match.groupValues[3].toIntOrNull() ?: return null
    val hour = match.groupValues[4].toIntOrNull() ?: return null
    val minute = match.groupValues[5].toIntOrNull() ?: return null
    val second = match.groupValues[6].toIntOrNull() ?: return null

    if (day !in 1..31 || hour !in 0..23 || minute !in 0..59 || second !in 0..60) return null

    val days = daysFromCivil(year, month, day)
    val secondsOfDay = hour * 3600L + minute * 60L + second
    return (days * 86_400L + secondsOfDay) * 1000L
}

/** Howard Hinnant's days_from_civil: days since 1970-01-01, which may be negative. */
private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
    val y = if (month <= 2) year - 1 else year
    val era = (if (y >= 0) y else y - 399) / 400
    val yearOfEra = y - era * 400
    val dayOfYear = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
    val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
    return era.toLong() * 146_097L + dayOfEra.toLong() - 719_468L
}

/**
 * Computes how many milliseconds to wait before retry number [attempt] (zero-based).
 *
 * Matches upstream `retryDelayMs`: a server-supplied delay is used when present
 * and within the cap, otherwise capped exponential backoff with a random
 * subtrahend for jitter.
 */
internal fun retryDelayMs(
    attempt: Int,
    retryAfterMs: Long?,
    policy: RetryPolicy,
    random: () -> Double,
): Long {
    if (policy.respectRetryAfter && retryAfterMs != null && retryAfterMs <= policy.maxRetryAfterMs) {
        return retryAfterMs
    }
    val exponential = min(
        policy.backoffInitialMs * 2.0.pow(attempt),
        policy.backoffMaxMs.toDouble(),
    )
    return (exponential * (1 - random() * policy.backoffJitter)).roundToLong()
}
