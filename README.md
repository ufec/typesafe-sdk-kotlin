# TypeSafe AI Kotlin SDK

Kotlin SDK for [TypeSafe AI](https://typesafe.ai). Ask typed questions about a
piece of text and get typed answers back.

This is a Kotlin port of [`@typesafe-ai/sdk`](https://github.com/typesafe-ai/typesafe-sdk-js)
0.6.0. See [NOTICE](NOTICE) for the full attribution and the list of deliberate
divergences.

## Install

The library is built on demand by [JitPack](https://jitpack.io). Add the
repository first:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // JitPack serves this project under com.github.ufec.typesafe-sdk-kotlin,
        // which is a *subgroup* of com.github.ufec. An exact includeGroup filter
        // on com.github.ufec would exclude the group that actually carries the
        // Android variant, and the dependency would fail to resolve.
        maven("https://jitpack.io") {
            content { includeGroupByRegex("com\\.github\\.ufec.*") }
        }
    }
}
```

Then depend on a tag:

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.github.ufec.typesafe-sdk-kotlin:typesafe-sdk-kotlin:v0.2.0")
}
```

JitPack names artifacts `com.github.<user>.<repo>:<module>`, so the group repeats
the repository name. The Android variant
(`com.github.ufec.typesafe-sdk-kotlin:typesafe-sdk-kotlin-android`) comes along
transitively through Gradle module metadata, so there is nothing else to
declare. A `-jvm` variant is published too, and a plain JVM project resolves it
from the same coordinate.

Targets Android (`minSdk` 29) and the JVM. Both need a JDK 17 toolchain.

## Quickstart

```kotlin
val client = TypeSafeClient(TypeSafeConfig(apiKey = "ts_..."))

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

val result = client.systemOne("[SALE] Limited-time offer, 50% off") { ask(category) }

val answer: ChoiceAnswer = result.answer(category)
println(answer.choice)       // promo
println(answer.confidence)   // 0.97
```

Answer types are derived from the questions, so `result.answer(category)` comes
back as a `ChoiceAnswer` without a cast. The client is `AutoCloseable`; call
`close()` when you are done with it.

## Example

There is a runnable demo in [`examples/`](examples), the counterpart of the
upstream JavaScript SDK's `npm run demo`:

```sh
export TYPESAFE_API_KEY=ts_...
./gradlew :examples:run
```

It lists the available models, then classifies a support ticket with one noul,
one choice, and two score questions. Letting the exception escape would bury the
one useful line under a Gradle failure block, so a bad key prints
`API error 401 (request <id>): ...` and the build still succeeds.

The demo is compiled by `./gradlew build`, so it cannot silently rot.

## Question types

| Builder | Answer | Shape |
| --- | --- | --- |
| `noul(instructions)` | `NoulAnswer` | A probability in `0..1`. No confidence, because the value already is one. |
| `choice(instructions, criteria)` | `ChoiceAnswer` | The selected key, its confidence, and the full distribution. |
| `score(instructions, criteria)` | `ScoreAnswer` | A weighted score, the bucket legend, and the distribution. |

`choice` reports an error at request time if `criteria` is empty, and `score`
requires at least two buckets.

## Configuration

```kotlin
val client = TypeSafeClient(
    TypeSafeConfig(
        apiKey = "ts_...",
        baseUrl = DEFAULT_BASE_URL,
        defaultModel = "jev-latest",
        timeoutMs = 10_000,
        retry = RetryPolicy(maxRetries = 2),
        proxy = ProxySpec(ProxyKind.HTTP, "10.0.0.1", 8080, "user", "pass"),
        logLevel = LogLevel.INFO,
        logger = AndroidLogger(),
    ),
)
```

Unlike upstream, this SDK never reads the ambient environment. There is no
`process.env` equivalent on Android, so the API key must be passed explicitly.

## Errors

Every failure derives from `TypeSafeException`:

| Exception | Raised when |
| --- | --- |
| `BadRequestException` (400) | The request was malformed. |
| `AuthenticationException` (401) | The API key was rejected. |
| `PermissionDeniedException` (403) | The account may not make this request. |
| `NotFoundException` (404) | The resource does not exist. |
| `UnprocessableEntityException` (422) | The request body failed validation. |
| `RateLimitException` (429) | Rate limited; carries `retryAfterMs`. |
| `InternalServerException` (5xx) | The server failed to handle the request. |
| `APIConnectionException` | DNS, TLS, or connection failure. |
| `APITimeoutException` | No complete response within `timeoutMs`. |

Retrying is on by default: `408`, `429`, and every `5xx`, up to `maxRetries`
attempts, with exponential backoff and jitter. A server-supplied `Retry-After`
or `retry-after-ms` wins when it is within `maxRetryAfterMs`.

Coroutine cancellation is **not** wrapped into any of these.
`CancellationException` propagates untouched so that structured concurrency
keeps working.

## Documentation

- [docs/usage.md](docs/usage.md) -- a longer walkthrough of questions, answers,
  retries, proxies, and logging
- [docs/changelog.md](docs/changelog.md) -- release notes
- [NOTICE](NOTICE) -- upstream attribution and the divergences from the
  JavaScript SDK
- [TypeSafe docs](https://docs.typesafe.ai/) -- what TypeSafe itself can do

## Development

```sh
./gradlew build          # compiles everything, runs ktlint, runs the tests
./gradlew ktlintCheck    # style only
./gradlew ktlintFormat   # style only, rewriting files
```

Style lives in [.editorconfig](.editorconfig), which is ktlint's own
configuration file -- the counterpart of the upstream JavaScript SDK's
`biome.json`. The base is ktlint's `ktlint_official` style with its line-breaking
rules turned off; the file records which ones and why.

## License

MIT. This port retains the upstream TypeSafe copyright as required by the MIT
license; see [LICENSE](LICENSE) and [NOTICE](NOTICE).
