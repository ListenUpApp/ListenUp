package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.RefreshRequest
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.RegisterResult
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.SessionSummary
import com.calypsan.listenup.api.dto.auth.User
import com.calypsan.listenup.server.routes.resources.AuthResources
import com.calypsan.listenup.server.auth.AuthServiceImpl
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.plugins.JWT_PROVIDER
import com.calypsan.listenup.server.plugins.RateLimitBuckets
import com.calypsan.listenup.server.plugins.userPrincipalOrNull
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.post
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
            call.respondAppResult<AuthSession>(svc.withUa(call).login(call.receive()))
        }
    }
    rateLimit(RateLimitBuckets.Register) {
        post<AuthResources.Register> {
            call.respondAppResult<RegisterResult>(svc.withUa(call).register(call.receive()))
        }
    }
    rateLimit(RateLimitBuckets.Setup) {
        post<AuthResources.Setup> {
            call.respondAppResult<AuthSession>(svc.withUa(call).setupRoot(call.receive<RegisterRequest>()))
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
        delete<AuthResources.Sessions.ById> { resource ->
            call.respondAppResult<Unit>(svc.asCaller(call).revokeSession(SessionId(resource.id)))
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
 * Capture the calling client's `User-Agent` header so the service can stamp it
 * onto the session it mints. The public auth routes are unauthenticated, so the
 * UA is the only device signal available beyond the optional `DeviceInfo` body.
 */
private fun AuthServiceImpl.withUa(call: ApplicationCall): AuthServiceImpl =
    withUserAgent(call.request.headers[HttpHeaders.UserAgent])

