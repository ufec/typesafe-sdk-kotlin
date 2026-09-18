package me.ethanxu.typesafe.sdk

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.random.Random
import kotlinx.io.IOException

/**
 * Client for the TypeSafe AI API. A Kotlin port of `TypeSafeClient` from
 * `@typesafe-ai/sdk@0.6.0` (MIT).
 *
 * See the repository's NOTICE and LICENSE files for attribution. The LICENSE
 * retains the upstream copyright notice, which is an MIT compliance
 * requirement — do not remove or replace it.
 *
 * Three deliberate divergences from upstream, each also noted at the relevant
 * site below:
 * 1. The `process.env` fallback is replaced by explicit configuration only
 *    (see [TypeSafeConfig]).
 * 2. Cancellation is not wrapped into a business exception;
 *    `CancellationException` propagates untouched (see `Errors.kt`).
 * 3. Upstream forwards extra properties attached to the request object; Kotlin
 *    has no equivalent, so nothing is forwarded.
 *
 * @param config client configuration.
 * @param http injectable for tests (for example a Ktor `MockEngine`). Defaults to the OkHttp engine.
 */
class TypeSafeClient(
    private val config: TypeSafeConfig,
    private val http: HttpClient = defaultHttpClient(config.timeoutMs, config.proxy),
) : AutoCloseable {

    private val logger: TypeSafeLogger = config.logger.atLevel(config.logLevel)

    /**
     * Request counter, used only to tell concurrent requests apart in the log.
     * Mirrors upstream `#requestCount`.
     *
     * Deliberately not atomic: it only affects log readability, an occasional
     * duplicated number under concurrency changes no behaviour, and pulling in
     * the experimental `kotlin.concurrent.atomics` API for that is a bad trade.
     */
    private var requestCount = 0L

    /** The models available to the account. Mirrors upstream `client.models`. */
    val models: ModelsResource = ModelsResource(::listModels)

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Asks about a plain-text [state].
     *
     * This is the most common entry point, since a state is usually just a
     * message body. Use the overload taking an [EntryType] to pass structured
     * state (an object or an array).
     */
    suspend fun systemOne(
        state: String,
        build: SystemOneRequestBuilder.() -> Unit,
    ): SystemOneResult = systemOne(JsonPrimitive(state), build)

    /**
     * Asks about [state], registering questions in a lambda.
     *
     * ```
     * val category = QuestionId("category", choice("Which category?", mapOf("spam" to null, "normal" to null)))
     * val result = client.systemOne("[SALE] 50% off everything") { ask(category) }
     * println(result.answer(category).choice)
     * ```
     *
     * @throws TypeSafeException the question set is empty, or a score question has fewer than two buckets.
     */
    suspend fun systemOne(
        state: EntryType,
        build: SystemOneRequestBuilder.() -> Unit,
    ): SystemOneResult = systemOne(SystemOneRequestBuilder(state).apply(build).build())

    /**
     * Evaluates [request].
     *
     * @throws TypeSafeException the question set is invalid.
     * @throws APIException the server still returned a non-2xx after retrying.
     * @throws APIConnectionException connecting or timing out still failed after retrying.
     */
    suspend fun systemOne(request: SystemOneRequest): SystemOneResult =
        systemOneWithResponse(request).data

    /** Same as [systemOne], but also surfaces the HTTP status and request id. Mirrors upstream `withResponse()`. */
    suspend fun systemOneWithResponse(request: SystemOneRequest): TypeSafeResponse<SystemOneResult> {
        validateQuestions(request.questions)

        val model = request.model ?: config.defaultModel
        val payload = buildJsonObject {
            put("state", request.state ?: JsonNull)
            put("model", JsonPrimitive(model))
            put(
                "questions",
                buildJsonObject {
                    request.questions.forEach { (name, question) ->
                        put(name, question.toJson())
                    }
                },
            )
        }

        val raw = send(
            tag = "POST $SYSTEM_ONE_PATH",
            method = HttpMethod.Post,
            path = SYSTEM_ONE_PATH,
            body = payload.toString(),
        )
        return TypeSafeResponse(
            data = parseSystemOneResult(raw, request, model),
            status = raw.status,
            requestId = raw.requestId,
        )
    }

    /** Lists the models available to the account. Mirrors upstream `GET /v1/models`. */
    suspend fun listModels(): List<ModelCard> {
        val raw = send(
            tag = "GET $MODELS_PATH",
            method = HttpMethod.Get,
            path = MODELS_PATH,
            body = null,
        )
        val obj = raw.body as? JsonObject
            ?: throw TypeSafeException(
                "Unexpected response shape from GET $MODELS_PATH; expected { models: [...] }.",
            )
        val array = obj["models"] as? JsonArray
            ?: throw TypeSafeException(
                "Unexpected response shape from GET $MODELS_PATH; expected { models: [...] }.",
            )
        return array.mapNotNull { element ->
            val card = element as? JsonObject ?: return@mapNotNull null
            val name = card.stringOrNull("name") ?: return@mapNotNull null
            ModelCard(
                name = name,
                description = card.stringOrNull("description").orEmpty(),
                releaseDate = card.stringOrNull("release_date").orEmpty(),
            )
        }
    }

    /** Releases the underlying HTTP engine. */
    override fun close() {
        http.close()
    }

    // -----------------------------------------------------------------------
    // Transport
    // -----------------------------------------------------------------------

    /**
     * Sends one request, retrying according to [RetryPolicy].
     *
     * The retry loop is hand-rolled rather than delegated to Ktor's
     * `HttpRequestRetry` plugin because two upstream behaviours have to be
     * reproduced: the `X-TypeSafe-Retry-Count` header, and the ordering rule
     * that a `Retry-After` within the cap wins over exponential backoff.
     */
    private suspend fun send(
        tag: String,
        method: HttpMethod,
        path: String,
        body: String?,
    ): RawResult {
        val url = config.normalizedBaseUrl + path
        val retry = config.retry
        val tagWithId = "#${++requestCount} $tag"

        var attempt = 0
        while (true) {
            val retriesLeft = retry.maxRetries - attempt
            val headers = buildHeaders(hasBody = body != null, attempt = attempt)
            logger.debug("$tagWithId -> $url ${redactHeaders(headers)}")
            val startedAt = System.currentTimeMillis()

            try {
                val response = http.request(url) {
                    this.method = method
                    headers.forEach { (name, value) -> header(name, value) }
                    if (body != null) {
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }
                    timeout { requestTimeoutMillis = config.timeoutMs }
                }

                val status = response.status.value
                val requestId = response.headers[REQUEST_ID_HEADER]
                val text = response.bodyAsText()
                val parsed = parseBody(text)

                logger.info(
                    buildString {
                        append("$tagWithId <- $status in ")
                        append(System.currentTimeMillis() - startedAt)
                        append("ms")
                        if (requestId != null) append(" (request $requestId)")
                    },
                )

                if (status in 200..299) {
                    return RawResult(status = status, requestId = requestId, body = parsed)
                }

                logger.debug("$tagWithId <- error body ${parsed?.describeForLog()}")
                throw APIException.fromResponse(
                    APIErrorResponse(
                        status = status,
                        body = parsed,
                        rawBody = text,
                        requestId = requestId,
                        headers = response.headers.entries()
                            .associate { it.key.lowercase() to it.value.first() },
                    ),
                )
            } catch (e: CancellationException) {
                // Coroutine cancellation is a control-flow signal: never retried, never wrapped.
                throw e
            } catch (e: HttpRequestTimeoutException) {
                logger.info(
                    "$tagWithId timed out after ${System.currentTimeMillis() - startedAt}ms",
                )
                if (retriesLeft <= 0 || !retry.apiTimeoutError) {
                    throw APITimeoutException(config.timeoutMs, e)
                }
                backOff(tagWithId, attempt, retriesLeft, "timeout", null, retry)
            } catch (e: IOException) {
                // Covers Ktor's ConnectTimeoutException and SocketTimeoutException: in
                // the upstream semantics only an overall timeout counts as a timeout,
                // while a failure at the connection layer is a connection error.
                logger.info("$tagWithId connection error after ${e.message}")
                if (retriesLeft <= 0 || !retry.apiConnectionError) {
                    throw APIConnectionException("Connection error: ${e.message}", e)
                }
                backOff(tagWithId, attempt, retriesLeft, e.message ?: "connection error", null, retry)
            } catch (e: APIException) {
                if (retriesLeft <= 0 || !retry.isRetryableStatus(e.status)) throw e
                backOff(
                    tagWithId,
                    attempt,
                    retriesLeft,
                    e.status.toString(),
                    (e as? RateLimitException)?.retryAfterMs,
                    retry,
                )
            }

            attempt++
        }
    }

    /** Waits, then retries. Mirrors upstream `backOff`. Cancellation propagates straight out of [delay]. */
    private suspend fun backOff(
        tag: String,
        attempt: Int,
        retriesLeft: Int,
        reason: String,
        retryAfterMs: Long?,
        retry: RetryPolicy,
    ) {
        val delayMs = retryDelayMs(attempt, retryAfterMs, retry, Random::nextDouble)
        val nth = attempt + 1
        val total = attempt + retriesLeft
        logger.info("$tag retrying in ${delayMs}ms (retry $nth/$total) after $reason")
        delay(delayMs)
    }

    /** Assembles request headers. Caller-supplied [TypeSafeConfig.defaultHeaders] come first, and SDK values override them. */
    private fun buildHeaders(hasBody: Boolean, attempt: Int): Map<String, String> = buildMap {
        putAll(config.defaultHeaders)
        put(AUTHORIZATION_HEADER, "Bearer ${config.apiKey}")
        put(ACCEPT_HEADER, "application/json")
        put(USER_AGENT_HEADER, SDK_USER_AGENT)
        put(SDK_HEADER, SDK_USER_AGENT)
        put(RUNTIME_HEADER, config.runtimeDescriptor)
        if (hasBody) put(CONTENT_TYPE_HEADER, "application/json")
        if (attempt > 0) put(RETRY_COUNT_HEADER, attempt.toString())
    }

    /**
     * Parses the response body leniently: anything that is not valid JSON falls
     * back to the raw text. Mirrors the tolerant behaviour of upstream
     * `parseBody` (servers and proxies do not always set a content type).
     */
    private fun parseBody(text: String): JsonElement? {
        if (text.isEmpty()) return null
        return runCatching { wireJson.parseToJsonElement(text) }
            .getOrElse { JsonPrimitive(text) }
    }

    /**
     * Parses a `systemOne` response.
     *
     * This is stricter than upstream, which casts `answers` straight to
     * `Record<string, Answer>`. Here each question parses its own answer, and an
     * unexpected extra key is an error rather than something ignored, because a
     * silently wrong classification costs far more than a visible exception.
     */
    private fun parseSystemOneResult(
        raw: RawResult,
        request: SystemOneRequest,
        requestedModel: String,
    ): SystemOneResult {
        val obj = raw.body as? JsonObject
            ?: throw TypeSafeException(
                "Unexpected response from POST $SYSTEM_ONE_PATH; expected a JSON object.",
            )
        val answersObject = obj.objectOrNull("answers")
            ?: throw TypeSafeException("Response from POST $SYSTEM_ONE_PATH is missing \"answers\".")

        val answers = LinkedHashMap<String, Answer>(answersObject.size)
        answersObject.forEach { (name, element) ->
            val answerObject = element as? JsonObject
                ?: throw TypeSafeException("Answer \"$name\" is not a JSON object.")
            val question = request.questions[name]
                ?: throw TypeSafeException(
                    "Server returned an answer for unknown question \"$name\". " +
                        "Known questions: ${request.questions.keys}.",
                )
            answers[name] = question.parseAnswer(answerObject)
        }

        val usage = obj.objectOrNull("usage")
        return SystemOneResult(
            model = obj.stringOrNull("model") ?: requestedModel,
            answers = answers,
            usage = Usage(
                inputTokens = usage?.intOrNull("input_tokens") ?: 0,
                outputTokens = usage?.intOrNull("output_tokens") ?: 0,
            ),
        )
    }

    private data class RawResult(
        val status: Int,
        val requestId: String?,
        val body: JsonElement?,
    )
}

/** Lenient JSON configuration used for parsing responses. */
private val wireJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

private const val SYSTEM_ONE_PATH = "/v1/systemone"
private const val MODELS_PATH = "/v1/models"

private const val AUTHORIZATION_HEADER = "Authorization"
private const val ACCEPT_HEADER = "Accept"
private const val CONTENT_TYPE_HEADER = "Content-Type"
private const val USER_AGENT_HEADER = "User-Agent"
private const val SDK_HEADER = "X-TypeSafe-SDK"
private const val RUNTIME_HEADER = "X-TypeSafe-Runtime"
private const val RETRY_COUNT_HEADER = "X-TypeSafe-Retry-Count"

/** Response header carrying the request id, useful when reporting a problem. */
private const val REQUEST_ID_HEADER = "x-typesafe-request-id"
