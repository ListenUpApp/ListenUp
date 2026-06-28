package com.calypsan.listenup.server.rpcguard

/**
 * rpc-guard is server-only infrastructure. Android is a client platform that never registers a
 * guarded service, so these `actual`s exist only to satisfy `expect`/`actual` for the Android
 * target and are never invoked — there is no correlation id to propagate.
 */
internal actual suspend fun currentCorrelationId(): String? = null

internal actual suspend fun <R> withMdc(
    vararg pairs: Pair<String, String>,
    block: suspend () -> R,
): R = block()
