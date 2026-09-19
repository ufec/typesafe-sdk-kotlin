# Usage

A longer walkthrough of the Kotlin SDK. For a minimal example, see the
[README](../README.md).

## Contents

- [Creating a client](#creating-a-client)
- [Asking questions](#asking-questions)
- [Reading answers](#reading-answers)
- [Structured state](#structured-state)
- [Retries and timeouts](#retries-and-timeouts)
- [Proxies](#proxies)
- [Logging](#logging)
- [Errors](#errors)
- [Running the example](#running-the-example)
- [Differences from the JavaScript SDK](#differences-from-the-javascript-sdk)

## Creating a client

```kotlin
val client = TypeSafeClient(
    TypeSafeConfig(
        apiKey = "ts_...",
        defaultModel = "jev-latest",
    ),
)
```

`TypeSafeClient` is `AutoCloseable` because it owns an HTTP engine. Call
`close()` when you are finished with it, or let a DI container manage its
lifetime.

The API key is required and is validated at construction time: a blank value
throws `TypeSafeException` immediately rather than failing on the first request.

## Asking questions

A question is a value, not a string. Build it once and reuse it:

```kotlin
val category = QuestionId(
    name = "category",
    question = choice(
        instructions = "Which category does this message belong to?",
        criteria = mapOf(
            "promo" to "Marketing content",
            "otp" to "A one-time passcode",
            "normal" to "Something the user expects",
        ),
    ),
)

val isOtp = QuestionId(
    name = "is_otp",
    question = noul(
        instructions = "Does this contain a one-time passcode?",
        criteria = noulCriteria(
            trueDescription = "A verification code the user is waiting for",
            falseDescription = "Anything else",
        ),
    ),
)

val urgency = QuestionId(
    name = "urgency",
    question = score(
        instructions = "How costly is it to interrupt the user with this?",
        criteria = listOf("Pure noise", "Can wait", "Needs to be seen immediately"),
    ),
)
```

Register them on a request:

```kotlin
val result = client.systemOne("[SALE] Limited-time offer, 50% off") {
    ask(category)
    ask(isOtp)
    ask(urgency)
    model = "jev-latest"   // optional, overrides the client default for this call
}
```

The three builders take `String` descriptions for the common case. Each also has
an overload accepting `JsonElement` when a question needs a structured
instruction.

`score` requires at least two buckets, and `criteria` on `choice` must be
non-empty; both are checked before the request is sent.

## Reading answers

```kotlin
val choice: ChoiceAnswer = result.answer(category)
val noul: NoulAnswer = result.answer(isOtp)
val scored: ScoreAnswer = result.answer(urgency)
```

The types are fixed by the `QuestionId`, so no cast is needed. Retrieving an
answer with the wrong handle, or for a question that was never asked, throws
`TypeSafeException` rather than returning something arbitrary.

`SystemOneResult` also carries:

- `model` -- the model that actually served the request
- `usage` -- `inputTokens` / `outputTokens`

To get the HTTP status and the request id alongside the data, use
`systemOneWithResponse`:

```kotlin
val response = client.systemOneWithResponse(request)
println("${response.status} ${response.requestId}")
```

Listing the models available to the account:

```kotlin
val models: List<ModelCard> = client.models.list()
```

## Structured state

`systemOne` accepts anything that is a `JsonElement`, so a state can be an object
or an array rather than plain text:

```kotlin
val state = buildJsonObject {
    put("sender", JsonPrimitive("AD-1069"))
    put("body", JsonPrimitive("[SALE] Limited-time offer, 50% off"))
}

val result = client.systemOne(state) { ask(category) }
```

The value is forwarded to the API verbatim.

## Retries and timeouts

Retries are on by default and configurable per client:

```kotlin
val client = TypeSafeClient(
    TypeSafeConfig(
        apiKey = "ts_...",
        timeoutMs = 15_000,
        retry = RetryPolicy(
            maxRetries = 3,
            backoffInitialMs = 500,
            backoffMaxMs = 10_000,
            backoffJitter = 0.25,
        ),
    ),
)
```

The defaults:

| Field | Default | Meaning |
| --- | --- | --- |
| `maxRetries` | `2` | Retries beyond the first attempt. |
| `backoffInitialMs` | `500` | First backoff delay. |
| `backoffMaxMs` | `5_000` | Ceiling for the exponential backoff. |
| `backoffJitter` | `0.25` | Fraction randomly subtracted from each delay. |
| `httpStatuses` | `408`, `429`, all `5xx` | Statuses that trigger a retry. |
| `respectRetryAfter` | `true` | Prefer the server's `Retry-After`. |
| `maxRetryAfterMs` | `60_000` | Ceiling on a server-supplied delay. |
| `apiConnectionError` | `true` | Retry connection failures. |
| `apiTimeoutError` | `true` | Retry timeouts. |

`timeoutMs` applies to a single attempt, not to the whole retry budget. A
`Retry-After` or `retry-after-ms` header wins over the backoff algorithm as long
as it does not exceed `maxRetryAfterMs`.

Jitter only ever *reduces* a delay, never increases it.

## Proxies

```kotlin
val client = TypeSafeClient(
    TypeSafeConfig(
        apiKey = "ts_...",
        proxy = ProxySpec(
            kind = ProxyKind.HTTP,
            host = "10.0.0.1",
            port = 8080,
            username = "user",
            password = "pass",
        ),
    ),
)
```

`ProxyKind.HTTP` handles both `http://` and `https://` destinations: an HTTP
proxy dials HTTPS targets through a `CONNECT` tunnel, which is standard
behaviour. There is no separate HTTPS proxy protocol, so there is no separate
kind.

`ProxyKind.SOCKS` selects SOCKS5.

Credentials are optional. When present, Ktor's `ProxyConfig` cannot carry them
(it is `java.net.Proxy` on the JVM), so the SDK registers an OkHttp
`proxyAuthenticator` that answers the proxy's `407` challenge with
`Proxy-Authorization`. If that header is already present and the proxy still
rejects the request, the authenticator gives up instead of looping.

## Logging

The SDK never writes anywhere unless you hand it a logger:

```kotlin
object AndroidLogger : TypeSafeLogger {
    private const val TAG = "TypeSafe"
    override fun debug(message: String) = Log.d(TAG, message)
    override fun info(message: String) = Log.i(TAG, message)
    override fun warn(message: String, throwable: Throwable?) = Log.w(TAG, message, throwable)
    override fun error(message: String, throwable: Throwable?) = Log.e(TAG, message, throwable)
}

val client = TypeSafeClient(
    TypeSafeConfig(apiKey = "ts_...", logLevel = LogLevel.INFO, logger = AndroidLogger),
)
```

The default is `NoOpLogger` at `LogLevel.WARN`. Setting `logLevel = LogLevel.OFF`
disables the sink entirely, regardless of what was passed as `logger`.

Credential headers (`authorization`, `proxy-authorization`, `x-api-key`) are
masked before a request is logged. The scheme is kept and, for secrets longer
than eight characters, so are the last four characters, which is usually enough
to tell two keys apart while debugging. `cookie` and `set-cookie` are masked
entirely.

## Errors

Every failure thrown by this SDK derives from `TypeSafeException`:

```
TypeSafeException
├── APIException
│   ├── BadRequestException            400
│   ├── AuthenticationException        401
│   ├── PermissionDeniedException      403
│   ├── NotFoundException              404
│   ├── UnprocessableEntityException   422
│   ├── RateLimitException             429   (+ retryAfterMs)
│   └── InternalServerException        5xx
└── APIConnectionException
    └── APITimeoutException
```

`APIException` exposes `status`, `body` (parsed JSON when possible), `requestId`
and the full `response`. When the body is not JSON at all, the message falls
back to the first 200 characters of the raw text, so a proxy's HTML error page
is still legible.

### Cancellation is not an error

Because this SDK targets coroutines rather than `AbortSignal`, a cancelled
request is **not** wrapped into a business exception. `CancellationException`
propagates untouched, which is what structured concurrency requires. Catch it
the way you normally would:

```kotlin
try {
    client.systemOne(state) { ask(category) }
} catch (e: CancellationException) {
    throw e                                  // never swallow this
} catch (e: TypeSafeException) {
    // handle a real failure
}
```

## Running the example

`examples/` is a small JVM application that uses this library the way a consumer
would. It is the counterpart of the upstream JavaScript SDK's `npm run demo`:

```sh
export TYPESAFE_API_KEY=ts_...
./gradlew :examples:run
```

It depends on the project in this repository rather than on a published
coordinate, so it always exercises the current source. The upstream demo imports
from `../src` for the same reason.

Because it is a JVM target, it runs with an ordinary JDK and needs no device or
emulator. It is also compiled by `./gradlew build`, so it cannot fall behind the
API without the build going red.

## Differences from the JavaScript SDK

| | JavaScript SDK | This SDK |
| --- | --- | --- |
| Configuration source | Falls back to `process.env` | Explicit values only |
| Cancellation | `APIUserAbortError` | `CancellationException` propagates |
| Extra request properties | Forwarded | Not forwarded; Kotlin has no equivalent |
| Answer typing | Inferred with TypeScript conditional types | Bound to a `QuestionId`, checked at runtime |
| Unexpected answer keys | Coerced into a record | Rejected with `TypeSafeException` |
| Proxy support | Not in the portable core | `ProxySpec` with HTTP and SOCKS5 |
| HTTP engine | Environment-dependent | OkHttp on Android and the JVM |
| `Retry-After` as an HTTP date | `Date.parse` | Hand-rolled RFC 1123 parser |

Anything not listed above keeps upstream's semantics on purpose, including the
status-code-to-exception mapping, so error handling behaves the same on both
platforms.
