package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.RefreshRequest
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.RegisterResult
import com.calypsan.listenup.api.dto.auth.SessionSummary
import com.calypsan.listenup.api.dto.auth.User
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.resources.AuthResources
import com.calypsan.listenup.server.auth.AuthServiceImpl
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.plugins.JWT_PROVIDER
import com.calypsan.listenup.server.plugins.RateLimitBuckets
import com.calypsan.listenup.server.plugins.toHttpStatus
import com.calypsan.listenup.server.plugins.userPrincipalOrNull
import com.calypsan.listenup.server.plugins.withCorrelationId
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

/**
 * REST handlers mirroring the contract. Each handler delegates to
 * [AuthServiceImpl] and folds its [AppResult] return into an HTTP status +
 * JSON body via [respondAppResult].
 *
 * Authed routes pass the per-call principal into the service via a per-call
 * [copyWith][AuthServiceImpl.copyWith] — the service stays Ktor-free. When
 * the auth wall lets through an unprincipled request (a regression bug), the
 * service returns [AppResult.Failure] with [AuthError.SessionExpired]; tests
 * catch that.
 */
fun Route.authRoutes(svc: AuthServiceImpl) {
    rateLimit(RateLimitBuckets.Login) {
        post<AuthResources.Login> {
            call.respondAppResult<AuthSession>(svc.login(call.receive()))
        }
    }
    rateLimit(RateLimitBuckets.Register) {
        post<AuthResources.Register> {
            call.respondAppResult<RegisterResult>(svc.register(call.receive()))
        }
    }
    rateLimit(RateLimitBuckets.Setup) {
        post<AuthResources.Setup> {
            call.respondAppResult<AuthSession>(svc.setupRoot(call.receive<RegisterRequest>()))
        }
    }
    rateLimit(RateLimitBuckets.Refresh) {
        post<AuthResources.Refresh> {
            call.respondAppResult<AuthSession>(svc.refreshSession(call.receive<RefreshRequest>()))
        }
    }

    authenticate(JWT_PROVIDER) {
        post<AuthResources.Logout> {
            call.respondAppResult<Unit>(svc.asCaller(call).logout())
        }
        post<AuthResources.LogoutAll> {
            call.respondAppResult<Unit>(svc.asCaller(call).logoutAll())
        }
        get<AuthResources.CurrentUser> {
            call.respondAppResult<User>(svc.asCaller(call).currentUser())
        }
        get<AuthResources.Sessions> {
            call.respondAppResult<List<SessionSummary>>(svc.asCaller(call).listSessions())
        }
    }
}

/**
 * Bind the service's [PrincipalProvider] to whatever the JWT auth wall
 * attached on this call. A null principal here means the auth wall failed
 * (a regression); the service returns [AppResult.Failure] with
 * [AuthError.SessionExpired] in that case, surfaced through the wire.
 */
private fun AuthServiceImpl.asCaller(call: ApplicationCall): AuthServiceImpl =
    copyWith(PrincipalProvider { call.userPrincipalOrNull() })

/**
 * Fold an [AppResult] into a Ktor response. Success → 200 with the result
 * body; failure → the error's mapped HTTP status with the result body
 * (correlation id stamped on the error).
 *
 * The body is always the whole [AppResult] so the wire shape is uniform —
 * clients deserialize one type per endpoint regardless of outcome.
 *
 * The explicit `: AppResult<T>` annotation on the local `body` keeps the
 * static type at the [respond] call as the sealed parent so kotlinx.serialization
 * emits the polymorphic discriminator. Smart-casting to a concrete variant
 * would silently strip it.
 */
private suspend inline fun <reified T : Any> ApplicationCall.respondAppResult(result: AppResult<T>) {
    val status: HttpStatusCode
    val body: AppResult<T>
    when (result) {
        is AppResult.Success -> {
            status = HttpStatusCode.OK
            body = result
        }

        is AppResult.Failure -> {
            val typed = result.error.withCorrelationId(callId)
            status = typed.toHttpStatus()
            body = AppResult.Failure(typed)
        }
    }
    respond(status, body)
}
