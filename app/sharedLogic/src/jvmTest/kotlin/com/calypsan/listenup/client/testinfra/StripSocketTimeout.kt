package com.calypsan.listenup.client.testinfra

import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.HttpRequestBuilder

/**
 * Works around an upstream race in `ktor-server-test-host` that makes any multipart upload carrying a
 * **socket** timeout intermittently fail — the flake behind `ImportRpcE2ETest` and
 * `BackupUploadRestoreE2ETest` retrying in roughly half of all CI runs (ledger evidence: 4 of 8 runs
 * on 2026-08-19, `main` included), always as `NetworkUnavailable(debugInfo=null)`.
 *
 * The mechanism, in `io.ktor.server.testing.Utils.socketTimeoutKiller` (Ktor 3.5.2):
 *
 * ```kotlin
 * val killJob = launch {
 *     var cur = extract()                       // reads CountedByteWriteChannel.totalBytesWritten
 *     while (job.isActive) { delay(socketTimeoutMillis); ... }
 * }
 * job.invokeOnCompletion { killJob.cancel() }
 * ```
 *
 * `launch` dispatches rather than running inline, so `extract()` executes on a later turn. When the
 * upload finishes first — the common case for a small fixture on a contended runner — the body channel
 * is already closed and `extract()` throws `ClosedWriteChannelException`. It is an ordinary call, not a
 * suspension point, so the `invokeOnCompletion` cancellation cannot preempt it; and because the killer
 * is launched in the **request's** scope, its uncaught throw cancels the parent ("Parent job is
 * Cancelling") and fails the whole call. `ErrorMapper` then folds that `IOException` to
 * `TransportError.NetworkUnavailable` — faithfully, which is why the symptom looks like a network fault
 * in a test that has no network.
 *
 * Stripping the socket timeout removes nothing real. These specs do not install
 * [io.ktor.client.plugins.HttpTimeout], so the production `timeout { }` values are already inert for
 * behaviour; `TestHttpClientEngine` reads the capability directly and is the sole consumer. The request
 * timeout is deliberately preserved — it has no watchdog and no such race — so the production upload
 * path under test is otherwise unchanged.
 *
 * Delete this once the upstream race is fixed; [stripSocketTimeout] failing to find a capability to
 * strip is harmless, so a stale application is inert rather than misleading.
 */
val StripSocketTimeout =
    createClientPlugin("StripSocketTimeout") {
        onRequest { request, _ -> request.stripSocketTimeout() }
    }

/**
 * Replaces any per-request timeout capability with one whose `socketTimeoutMillis` is null, leaving the
 * request and connect budgets untouched. No-op when the request set no timeout at all.
 */
private fun HttpRequestBuilder.stripSocketTimeout() {
    val existing = getCapabilityOrNull(HttpTimeoutCapability) ?: return
    setCapability(
        HttpTimeoutCapability,
        HttpTimeoutConfig(
            requestTimeoutMillis = existing.requestTimeoutMillis,
            connectTimeoutMillis = existing.connectTimeoutMillis,
            socketTimeoutMillis = null,
        ),
    )
}
