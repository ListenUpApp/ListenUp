package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.AuthServiceAuthed
import com.calypsan.listenup.api.AuthServicePublic
import com.calypsan.listenup.api.PingService
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.server.auth.AuthServiceImpl
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.plugins.JWT_PROVIDER
import com.calypsan.listenup.server.plugins.userPrincipalOrNull
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.Route
import kotlinx.rpc.krpc.ktor.server.rpc
import kotlinx.rpc.krpc.serialization.json.json
import kotlinx.rpc.registerService

private class PingServiceImpl : PingService {
    override suspend fun ping(): String = "pong"
}

/**
 * Mounts kRPC at two endpoints:
 *  - `/api/rpc/public` — anonymous; routes [AuthServicePublic] (login/register/
 *    setupRoot/refresh) and [PingService].
 *  - `/api/rpc/authed` — gated behind [JWT_PROVIDER]; routes [AuthServiceAuthed]
 *    (logout/logoutAll/currentUser/listSessions/decide).
 *
 * The split mirrors the contract's trust boundary structurally — the URL
 * reflects which methods need a session, no fallback hacks needed.
 *
 * Known limitation (Phase F open question): kotlinx.rpc 0.10.x serializes
 * server-side exceptions as `SerializedException` (class name + message +
 * stacktrace), so typed `AuthError` values do not survive the RPC wire as
 * structured data. Phase 1 ships with REST as the primary error surface for
 * the client; RPC-side typed-error recovery awaits either a kotlinx.rpc
 * interceptor API or a migration to `AppResult<T>` return shapes.
 */
fun Route.rpcRoutes(authService: AuthServiceImpl) {
    rpc("/api/rpc/public") {
        rpcConfig { serialization { json(contractJson) } }
        registerService<PingService> { PingServiceImpl() }
        registerService<AuthServicePublic> { authService }
    }

    authenticate(JWT_PROVIDER) {
        rpc("/api/rpc/authed") {
            rpcConfig { serialization { json(contractJson) } }
            registerService<AuthServiceAuthed> {
                val p =
                    call.userPrincipalOrNull()
                        ?: error("authed RPC mount reached without a principal — auth wall regression")
                authService.copyWith(PrincipalProvider { p })
            }
        }
    }
}
