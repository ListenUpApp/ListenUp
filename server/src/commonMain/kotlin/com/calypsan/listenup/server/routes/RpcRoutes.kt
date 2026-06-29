package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.PingService
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.plugins.userPrincipalOrNull
import io.ktor.server.application.call
import io.ktor.server.routing.Route
import kotlinx.rpc.krpc.ktor.server.KrpcRoute
import kotlinx.rpc.registerService

internal class PingServiceImpl : PingService {
    override suspend fun ping(): AppResult<String> = AppResult.Success("pong")
}

private const val AUTH_WALL_REGRESSION_MSG =
    "authed RPC mount reached without a principal — auth wall regression"

/**
 * Registers a per-call principal-scoped service on this kRPC mount.
 *
 * Resolves the call's principal once per service resolution — an absent principal here
 * is an auth-wall regression (the mount is inside `authenticate(JWT_PROVIDER)`) — then
 * hands a [PrincipalProvider] to [scoped], which binds it onto the service via `copyWith`
 * and wraps the result with the generated `guard`. Collapses every authed registration to
 * a single line and keeps the `guard` call at the call site, where the concrete service
 * type selects the right `guard` overload.
 */
internal inline fun <reified T : Any> KrpcRoute.registerScoped(crossinline scoped: (PrincipalProvider) -> T) {
    registerService<T> {
        val principal = call.userPrincipalOrNull() ?: error(AUTH_WALL_REGRESSION_MSG)
        scoped(PrincipalProvider { principal })
    }
}

/**
 * Mounts kRPC at two endpoints:
 *  - `/api/rpc/public` — anonymous; routes [AuthServicePublic] (login/register/
 *    setupRoot/refresh) and [PingService].
 *  - `/api/rpc/authed` — gated behind [JWT_PROVIDER]; routes [AuthServiceAuthed]
 *    (logout/logoutAll/currentUser/listSessions/decide) and [BookService]
 *    (getBook/searchBooks).
 *
 * The split mirrors the contract's trust boundary structurally — the URL
 * reflects which methods need a session, no fallback hacks needed.
 *
 * The KSP-generated `<Service>Guarded` decorator wraps every `registerService`
 * factory below. Domain failures already cross the wire as typed `AppError`
 * via `AppResult<T>` returns; the guard catches anything that *escapes* a
 * service (bugs, infra faults), logs it server-side with the correlation id,
 * and returns a sanitized `InternalError`. Stacktraces never cross the wire.
 *
 * **Why `expect`/`actual` and not a single commonMain body:** the `guard` dispatcher and the
 * `<Service>Guarded` decorators are KSP-generated PER-TARGET into `:contract`'s jvm + linuxX64 +
 * linuxArm64 compilations (see `contract/build.gradle.kts` `kspJvm`/`kspLinuxX64`/`kspLinuxArm64`),
 * deliberately kept out of `:contract` commonMain to avoid forcing Apple actuals + Swift-export
 * pollution. `guard(...)` is therefore NOT resolvable from `:server` commonMain, so the body must live
 * in each `actual`. There are two: the jvm actual and the shared-native actual in `linuxMain` (one copy
 * for both Linux arches — `guard` is arch-agnostic). They are byte-identical and pinned that way by
 * `RpcRoutesActualsParityTest`; edit them together.
 */
expect fun Route.rpcRoutes(services: RpcServices)
