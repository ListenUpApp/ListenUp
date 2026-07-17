package com.calypsan.listenup.client.data.remote

/**
 * Thrown when a configured server URL carries a scheme the RPC transport can't upgrade to a
 * WebSocket one (anything that isn't `http`, `https`, `ws` or `wss`).
 *
 * The twin of [ServerUrlNotConfiguredException], and typed for the same reason: a stringly
 * `error("Server URL has unsupported scheme …")` [IllegalStateException] escapes the data layer as
 * an untyped fault, breaking the `AppResult` contract — and on Kotlin/Native an uncaught coroutine
 * throwable terminates the process. Typing it lets
 * [com.calypsan.listenup.client.core.error.ErrorMapper] fold it into
 * [com.calypsan.listenup.api.error.ServerConnectError.InvalidUrl], which tells the user their URL
 * is wrong instead of claiming the network is down.
 *
 * Reaching this means a URL got past the connect-flow's validation and was persisted, so it is
 * also a signal that the boundary check has a hole — but the user still gets an honest, actionable
 * message rather than a crash.
 *
 * Extends [IllegalStateException] so existing catch-all transport boundaries keep treating it as a
 * thrown transport error; only the sites that care test for the type.
 */
internal class ServerUrlSchemeUnsupportedException(
    val url: String,
) : IllegalStateException("Server URL has unsupported scheme: $url")
