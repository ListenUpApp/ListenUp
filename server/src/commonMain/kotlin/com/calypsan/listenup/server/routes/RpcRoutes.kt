package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.PingService
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.plugins.userPrincipalOrNull
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.call
import io.ktor.server.routing.Route
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.rpc.krpc.ktor.server.KrpcRoute
import kotlinx.rpc.registerService

internal class PingServiceImpl : PingService {
    override suspend fun ping(): AppResult<String> = AppResult.Success("pong")
}

private const val AUTH_WALL_REGRESSION_MSG =
    "authed RPC mount reached without a principal — auth wall regression"

private val rpcRoutesLogger = KotlinLogging.logger("com.calypsan.listenup.server.routes.RpcRoutes")

/**
 * Registers a per-call principal-scoped service on this kRPC mount.
 *
 * The mount is inside `authenticate(JWT_PROVIDER)`, so the principal is normally present. The
 * principal is resolved LAZILY inside the [PrincipalProvider] rather than eagerly in the factory:
 * an absent principal (an auth-wall regression) then fails CLOSED inside a guarded method — every
 * service treats a null principal as `PermissionDenied` — instead of throwing an unguarded error
 * from the registration factory, which kotlinx.rpc would serialize raw (message + stacktrace) to
 * the client. The whole construction is additionally wrapped in [guardedConstruction] so a DI
 * mis-wire (a failing `copyWith`/cast in [scoped]) is sanitized the same way. The `guard` call
 * stays at the call site, where the concrete service type selects the right overload.
 */
internal inline fun <reified T : Any> KrpcRoute.registerScoped(crossinline scoped: (PrincipalProvider) -> T) {
    registerService<T> {
        guardedConstruction {
            scoped(PrincipalProvider { call.userPrincipalOrNull() ?: authWallRegression() })
        }
    }
}

/** Loud server-side signal for an auth-wall regression; returns null so services fail closed. */
internal fun authWallRegression(): UserPrincipal? {
    rpcRoutesLogger.error { AUTH_WALL_REGRESSION_MSG }
    return null
}

/**
 * Runs a service-registration factory [block], converting any construction failure into a
 * sanitized throw. The registration factory executes OUTSIDE the generated `guard`, so a raw throw
 * here (a mis-wired DI cast, a `copyWith` fault) would reach kotlinx.rpc's default and ship the
 * exception class + message + stacktrace to the client. Instead we log the original server-side
 * keyed by a fresh correlation id and rethrow an [IllegalStateException] whose message carries only
 * that id — no class name, SQL, path, or hostname. [CancellationException] always re-raises.
 */
internal fun <T> guardedConstruction(block: () -> T): T =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        val cid = Uuid.random().toString()
        rpcRoutesLogger.error(e) { "RPC service construction failed [cid=$cid]" }
        throw IllegalStateException("Service unavailable [cid=$cid]")
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
