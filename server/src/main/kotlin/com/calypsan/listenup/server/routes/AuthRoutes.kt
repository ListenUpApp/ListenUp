package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.dto.auth.PendingRegistrationDecision
import com.calypsan.listenup.api.dto.auth.RefreshRequest
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.resources.AuthResources
import com.calypsan.listenup.server.auth.AuthException
import com.calypsan.listenup.server.auth.AuthServiceImpl
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.plugins.JWT_PROVIDER
import com.calypsan.listenup.server.plugins.RateLimitBuckets
import com.calypsan.listenup.server.plugins.userPrincipalOrNull
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

/**
 * REST handlers mirroring the @Rpc auth contract. Each handler delegates to
 * [AuthServiceImpl], passing the per-call principal via [PrincipalProvider]
 * for authenticated routes — the service stays free of Ktor types.
 *
 * Failures throw [AuthException]; the StatusPages handler unwraps them into
 * typed [AuthError] JSON responses with mapped HTTP status.
 */
fun Route.authRoutes(svc: AuthServiceImpl) {
    rateLimit(RateLimitBuckets.Login) {
        post<AuthResources.Login> {
            val req = call.receive<LoginRequest>()
            call.respond(svc.login(req))
        }
    }
    rateLimit(RateLimitBuckets.Register) {
        post<AuthResources.Register> {
            val req = call.receive<RegisterRequest>()
            call.respond(svc.register(req))
        }
    }
    rateLimit(RateLimitBuckets.Setup) {
        post<AuthResources.Setup> {
            val req = call.receive<RegisterRequest>()
            call.respond(svc.setupRoot(req))
        }
    }
    rateLimit(RateLimitBuckets.Refresh) {
        post<AuthResources.Refresh> {
            val req = call.receive<RefreshRequest>()
            call.respond(svc.refreshSession(req))
        }
    }

    authenticate(JWT_PROVIDER) {
        post<AuthResources.Logout> {
            svc.asCaller(call).logout()
            call.respond(HttpStatusCode.NoContent)
        }
        post<AuthResources.LogoutAll> {
            svc.asCaller(call).logoutAll()
            call.respond(HttpStatusCode.NoContent)
        }
        get<AuthResources.CurrentUser> {
            call.respond(svc.asCaller(call).currentUser())
        }
        get<AuthResources.Sessions> {
            call.respond(svc.asCaller(call).listSessions())
        }
        post<AuthResources.DecidePendingRegistration> {
            val body = call.receive<PendingRegistrationDecision>()
            call.respond(svc.asCaller(call).decidePendingRegistration(body))
        }
    }
}

/**
 * Returns a copy of [this] bound to the [io.ktor.server.application.ApplicationCall]'s
 * authenticated principal. Throws [AuthException] with [AuthError.SessionExpired]
 * when the JWT auth wall let through a request without a principal — should never
 * happen on routes inside `authenticate(JWT_PROVIDER)`, but the typed error makes
 * the regression diagnosable.
 */
private fun AuthServiceImpl.asCaller(call: io.ktor.server.application.ApplicationCall): AuthServiceImpl {
    val p = call.userPrincipalOrNull() ?: throw AuthException(AuthError.SessionExpired())
    return copyWith(PrincipalProvider { p })
}
