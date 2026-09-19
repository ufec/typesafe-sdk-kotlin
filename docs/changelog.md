# Changelog

## v0.2.0 (2026-09-19)

### Added

- a JVM target, so the library runs on a server as well as inside an app
- `examples/`, a runnable demonstration of the library and the counterpart of the
  upstream JavaScript SDK's `npm run demo`

### Changed

- `defaultHttpClient` moved into `commonMain` and lost its `expect`/`actual`
  split. Kotlin does not support sharing a source set between JVM and Android
  targets, and OkHttp serves both, so the split had nowhere valid to put its
  actual. Adding a non-JVM target now fails in that file, which is the right
  moment to find out.

## v0.1.0 (2026-09-19)

The initial release: a Kotlin port of
[`@typesafe-ai/sdk`](https://github.com/typesafe-ai/typesafe-sdk-js) 0.6.0.

Ported from upstream: `TypeSafeClient`, `TypeSafeConfig`, the request and
response types, `RetryPolicy` and its backoff logic, the exception hierarchy, and
log-level filtering with credential redaction.

Added for Kotlin: `ProxySpec` for HTTP and SOCKS5 proxying, including
authentication, which Ktor's `ProxyConfig` cannot express on the JVM; and runtime
type checking of answers through `QuestionId`.
