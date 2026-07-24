package com.calypsan.listenup.client.data.remote

/**
 * Thrown when a server RPC/HTTP call is attempted before a server URL has been configured.
 *
 * This is an *expected* state, not a bug: on a fresh or signed-out install, background work
 * (preference sync, instance/server-info fetches) can fire before the user has connected to a
 * server. Making it a typed signal — rather than a stringly `error("Server URL not configured …")`
 * [IllegalStateException] — lets the boundary fold it into a quiet "not connected yet" path:
 * [com.calypsan.listenup.client.core.error.ErrorMapper] maps it to
 * [com.calypsan.listenup.api.error.TransportError.NetworkUnavailable], and callers log it at
 * `debug` instead of dumping a stacktrace for a non-failure.
 *
 * Extends [IllegalStateException] so existing catch-all transport boundaries keep treating it as a
 * thrown transport error; only the few sites that care about the not-connected case test for the
 * type.
 */
internal class ServerUrlNotConfiguredException :
    IllegalStateException("Server URL not configured — cannot open a server connection")
