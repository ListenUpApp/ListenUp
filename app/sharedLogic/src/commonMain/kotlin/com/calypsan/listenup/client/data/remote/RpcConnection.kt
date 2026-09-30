package com.calypsan.listenup.client.data.remote

import kotlinx.rpc.krpc.ktor.client.KtorRpcClient

/**
 * One kotlinx.rpc client — and so one WebSocket — paired with the service [proxy] it produced.
 *
 * `client.rpc(url)` opens its OWN WebSocket session, and closing the `HttpClient` it came from does
 * not close that session: Ktor's `HttpClient.close()` only completes the client's job, which waits
 * for (never cancels) the sessions riding it. So whoever drops a proxy must close the RPC client
 * behind it, or the socket outlives every reference to it. [RpcProxyCache] owns that lifecycle; this
 * type is how a factory hands it the means to [close].
 */
internal class RpcConnection<out T>(
    val proxy: T,
    private val closeClient: () -> Unit,
) {
    /** Close the RPC client behind [proxy], and with it its WebSocket. Safe to call more than once. */
    fun close() = closeClient()
}

/** Bind a service proxy to this RPC client, keeping the client's [KtorRpcClient.close] with it. */
internal inline fun <T> KtorRpcClient.asConnection(bind: KtorRpcClient.() -> T): RpcConnection<T> =
    RpcConnection(bind()) { close() }
