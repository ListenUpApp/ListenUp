package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.AuthServiceAuthed
import com.calypsan.listenup.api.AuthServicePublic
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.client.domain.repository.ServerConfig
import io.ktor.client.HttpClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.serialization.json.json
import kotlinx.rpc.withService

/**
 * Mounts the two kotlinx.rpc service proxies the client uses to talk to
 * the auth contract:
 *  - [AuthServicePublic] over `/api/rpc/public` — anonymous; login, register,
 *    refresh, setupRoot.
 *  - [AuthServiceAuthed] over `/api/rpc/authed` — bearer-gated; logout,
 *    currentUser, listSessions, decidePendingRegistration.
 *
 * Each `rpc(url)` call returns a cold [kotlinx.rpc.krpc.ktor.client.KtorRpcClient]
 * that opens its WebSocket on the first message — so the proxies are cached
 * per mount and reused. [invalidate] drops both the cached proxies *and* the
 * RPC-flavored HttpClient that wraps them; the configured [ApiClientFactory]
 * calls [invalidate] whenever the underlying [HttpClient] is recycled (server
 * URL changed, manual reset).
 *
 * Wire serialization is the contract-layer [contractJson] — same instance
 * the REST surface and contract round-trip tests use. One wire format,
 * two transports.
 */
open class AuthRpcFactory(
    private val apiClientFactory: ApiClientFactory,
    private val serverConfig: ServerConfig,
) {
    private val mutex = Mutex()
    private var cachedRpcClient: HttpClient? = null
    private var cachedPublic: AuthServicePublic? = null
    private var cachedAuthed: AuthServiceAuthed? = null

    suspend fun publicService(): AuthServicePublic =
        mutex.withLock {
            cachedPublic ?: connectPublic().also { cachedPublic = it }
        }

    suspend fun authedService(): AuthServiceAuthed =
        mutex.withLock {
            cachedAuthed ?: connectAuthed().also { cachedAuthed = it }
        }

    /** Drop cached proxies and the RPC-flavored HttpClient. */
    suspend fun invalidate() {
        mutex.withLock {
            cachedPublic = null
            cachedAuthed = null
            cachedRpcClient = null
        }
    }

    private suspend fun connectPublic(): AuthServicePublic {
        val baseUrl = requireBaseUrl()
        return rpcClient().rpc("$baseUrl/api/rpc/public").withService<AuthServicePublic>()
    }

    private suspend fun connectAuthed(): AuthServiceAuthed {
        val baseUrl = requireBaseUrl()
        return rpcClient().rpc("$baseUrl/api/rpc/authed").withService<AuthServiceAuthed>()
    }

    /**
     * Open hook for [AuthEndToEndFixture]: F12 needs to substitute a clean
     * WebSocket-capable [HttpClient] because [ApiClientFactory]'s `HttpSend`
     * interceptor mangles the kotlinx.rpc upgrade handshake (URL gets rewritten
     * before the WebSocket upgrade headers are negotiated). Production fix
     * tracked separately; the override seam stays narrow on purpose.
     */
    protected open suspend fun rpcClient(): HttpClient =
        cachedRpcClient ?: apiClientFactory
            .getClient()
            .config {
                installKrpc {
                    serialization { json(contractJson) }
                }
            }.also { cachedRpcClient = it }

    /**
     * Open hook for [AuthEndToEndFixture]: the base URL used to build the
     * `rpc(url)` call. Production composes `serverConfig.getActiveUrl()`
     * (an `http://` URL) with `/api/rpc/public|authed`. F12 needs `ws://`
     * because kotlinx.rpc 0.10.x does not auto-upgrade scheme — see the
     * working pattern in `PluginSmokeTest`. Production fix tracked
     * separately.
     */
    protected open suspend fun requireBaseUrl(): String =
        serverConfig.getActiveUrl()?.value
            ?: error("Server URL not configured — cannot open RPC connection")
}
