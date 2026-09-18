package me.ethanxu.typesafe.sdk

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * Everything a caller might want to know about a failed request, gathered into a
 * single value instead of being scattered across the transport layer.
 */
data class APIErrorResponse(
    val status: Int,
    val body: JsonElement?,
    val rawBody: String?,
    /** Taken from `x-typesafe-request-id`; null when the header is absent. */
    val requestId: String?,
    /** Response headers, keys lowercased. Used to read `Retry-After`. */
    val headers: Map<String, String> = emptyMap(),
)

/** Base class of every exception this SDK throws. Mirrors upstream `TypeSafeError`. */
open class TypeSafeException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * The server returned a non-2xx response. Mirrors upstream `APIError`.
 *
 * The subclasses mirror upstream's status dispatch exactly:
 * 400 / 401 / 403 / 404 / 422 / 429 / 5xx.
 */
open class APIException(
    val response: APIErrorResponse,
    message: String,
) : TypeSafeException(message) {

    val status: Int get() = response.status
    val body: JsonElement? get() = response.body
    val requestId: String? get() = response.requestId

    companion object {
        /** Same ceiling as upstream `MAX_RAW_BODY_IN_MESSAGE`. */
        private const val MAX_RAW_BODY_IN_MESSAGE = 200

        /** Builds the exception subclass that corresponds to the status code. */
        fun fromResponse(response: APIErrorResponse): APIException {
            val message = describe(response)
            return when (response.status) {
                400 -> BadRequestException(response, message)
                401 -> AuthenticationException(response, message)
                403 -> PermissionDeniedException(response, message)
                404 -> NotFoundException(response, message)
                422 -> UnprocessableEntityException(response, message)
                429 -> RateLimitException(
                    response = response,
                    message = message,
                    retryAfterMs = parseRetryAfter(response.headers, System.currentTimeMillis()),
                )
                in 500..599 -> InternalServerException(response, message)
                else -> APIException(response, message)
            }
        }

        /**
         * Extracts whatever readable detail the body carries, consulting the same
         * locations and in the same order as upstream `extractMessage`.
         */
        private fun describe(response: APIErrorResponse): String {
            val detail = extractMessage(response.body)
            if (detail != null) return "${response.status} $detail"

            val raw = response.rawBody
            if (raw.isNullOrEmpty()) return "${response.status} status code (no body)"
            val shown = if (raw.length > MAX_RAW_BODY_IN_MESSAGE) {
                raw.take(MAX_RAW_BODY_IN_MESSAGE) + "…"
            } else {
                raw
            }
            return "${response.status} $shown"
        }

        private fun extractMessage(body: JsonElement?): String? {
            val primitive = (body as? JsonPrimitive)?.takeIf { it.isString }
            if (primitive != null) {
                val text = primitive.content
                return text.ifEmpty { null }
            }

            val obj = body as? JsonObject ?: return null
            (obj["error"] as? JsonPrimitive)?.takeIf { it.isString }?.let { return it.content }
            (obj["error"] as? JsonObject)?.get("message")
                ?.let { it as? JsonPrimitive }?.takeIf { it.isString }?.let { return it.content }
            (obj["message"] as? JsonPrimitive)?.takeIf { it.isString }?.let { return it.content }

            when (val detail = obj["detail"]) {
                is JsonPrimitive -> if (detail.isString) return detail.content
                is JsonObject -> (detail["message"] as? JsonPrimitive)
                    ?.takeIf { it.isString }?.let { return it.content }
                is JsonArray -> return describeValidationErrors(detail)
                else -> Unit
            }
            return null
        }

        /** Formats FastAPI-style validation errors as `path: message; path: message`. */
        private fun describeValidationErrors(errors: JsonArray): String? {
            val parts = errors.mapNotNull { entry ->
                val obj = entry as? JsonObject ?: return@mapNotNull null
                val msg = (obj["msg"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: return@mapNotNull null
                val loc = (obj["loc"] as? JsonArray)
                    ?.mapNotNull { it.jsonPrimitive.takeIf { p -> p.isString }?.content }
                    ?.filter { it != "body" }
                    ?.joinToString(".")
                    .orEmpty()
                if (loc.isEmpty()) msg else "$loc: $msg"
            }
            return parts.takeIf { it.isNotEmpty() }?.joinToString("; ")
        }
    }
}

/** HTTP 400: the request was malformed. */
class BadRequestException(response: APIErrorResponse, message: String) :
    APIException(response, message)

/** HTTP 401: authentication failed. */
class AuthenticationException(response: APIErrorResponse, message: String) :
    APIException(response, message)

/** HTTP 403: the caller is not allowed to perform this request. */
class PermissionDeniedException(response: APIErrorResponse, message: String) :
    APIException(response, message)

/** HTTP 404: the resource does not exist. */
class NotFoundException(response: APIErrorResponse, message: String) :
    APIException(response, message)

/** HTTP 422: the request body failed validation. */
class UnprocessableEntityException(response: APIErrorResponse, message: String) :
    APIException(response, message)

/** HTTP 429: rate limited. */
class RateLimitException(
    response: APIErrorResponse,
    message: String,
    /** Delay requested by the server; null when the header is missing or unusable. */
    val retryAfterMs: Long?,
) : APIException(response, message)

/** HTTP 5xx: the server failed to handle the request. */
class InternalServerException(response: APIErrorResponse, message: String) :
    APIException(response, message)

/**
 * The request or response body failed in transit (DNS, TLS, dropped connection).
 * Mirrors upstream `APIConnectionError`.
 */
open class APIConnectionException(
    message: String,
    cause: Throwable? = null,
) : TypeSafeException(message, cause)

/** The complete response did not arrive within the timeout. Mirrors upstream `APITimeoutError`. */
class APITimeoutException(
    /** The configured timeout, in milliseconds. */
    val timeoutMs: Long,
    cause: Throwable? = null,
) : APIConnectionException("Request timed out after ${timeoutMs}ms.", cause)

/*
 * One deliberate divergence from upstream: upstream has `APIUserAbortError` to
 * represent cancellation through an AbortSignal.
 *
 * In Kotlin coroutines, cancellation is control flow rather than an error.
 * `CancellationException` has to propagate untouched, otherwise structured
 * concurrency breaks (a parent scope can no longer tell that a child was
 * cancelled). This SDK therefore does not wrap cancellation into a business
 * exception; callers catch `kotlinx.coroutines.CancellationException` as usual.
 */
