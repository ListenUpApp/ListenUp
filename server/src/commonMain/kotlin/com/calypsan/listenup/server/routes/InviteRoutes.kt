package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.InviteService
import com.calypsan.listenup.api.InviteServicePublic
import com.calypsan.listenup.api.dto.invite.ClaimInviteRequest
import com.calypsan.listenup.api.dto.invite.CreateInviteRequest
import com.calypsan.listenup.api.dto.invite.InviteId
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.server.routes.resources.InvitePreviewResource
import com.calypsan.listenup.server.routes.resources.InviteResources
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.api.InviteServiceImpl
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.plugins.RateLimitBuckets
import com.calypsan.listenup.server.plugins.toHttpStatus
import com.calypsan.listenup.server.plugins.userPrincipalOrNull
import com.calypsan.listenup.server.plugins.withCorrelationId
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

/**
 * Admin REST surface for [InviteService] — the administrative invite lifecycle:
 *
 *  - `POST   /api/v1/admin/invites` (CreateInviteRequest)  — mint an invite
 *  - `GET    /api/v1/admin/invites`                        — list outstanding invites
 *  - `DELETE /api/v1/admin/invites/{id}`                   — revoke an unclaimed invite
 *
 * RPC is the first-class surface; these exist so the same operations are reachable over
 * plain REST. The admin gate (ROOT/ADMIN) lives *inside* the service — every handler binds
 * the request to the authenticated principal via [InviteServiceImpl.copyWith] and delegates;
 * a non-admin caller surfaces as `AuthError.PermissionDenied` (403), an absent principal as
 * 401 (an auth-wall regression, since these mount inside `authenticate`). Responds with bare
 * types (unwrapped from AppResult) per the third-party REST convention.
 *
 * Mount inside `authenticate(JWT_PROVIDER)`.
 */
fun Route.adminInviteRoutes(inviteService: InviteService) {
    post<InviteResources.List> {
        val service = call.scoped(inviteService) ?: return@post
        val body = call.receive<CreateInviteRequest>()
        call.respondResult(
            service.createInvite(body.email, body.displayName, body.role, body.expiresInDays),
        )
    }

    get<InviteResources.List> {
        val service = call.scoped(inviteService) ?: return@get
        call.respondResult(service.listInvites())
    }

    delete<InviteResources.Detail> { res ->
        val service = call.scoped(inviteService) ?: return@delete
        when (val result = service.revokeInvite(InviteId(res.id))) {
            is AppResult.Success -> call.respond(HttpStatusCode.NoContent)
            is AppResult.Failure -> call.respondInviteError(result.error)
        }
    }
}

/**
 * Public (anonymous) REST surface for [InviteServicePublic] — the landing-page
 * lookup and the single-use claim:
 *
 *  - `GET  /api/v1/invites/{code}`        — landing-page preview, no auth
 *  - `POST /api/v1/invites/{code}/claim` (ClaimInviteRequest) — create the ACTIVE
 *    account + issue a session, no auth
 *
 * No principal, no scoping — the invite code itself is the admission decision an admin
 * already made. Mount *outside* the auth block, next to the public auth surface.
 */
fun Route.publicInviteRoutes(inviteService: InviteServicePublic) {
    // Anonymous endpoints, so they carry their own rate-limit buckets (mirroring the public
    // auth surface): claim runs Argon2 + creates an account, lookup is an anonymous oracle.
    rateLimit(RateLimitBuckets.InviteLookup) {
        get<InvitePreviewResource> { res ->
            call.respondResult(inviteService.lookupInvite(res.code))
        }
    }

    rateLimit(RateLimitBuckets.InviteClaim) {
        post<InvitePreviewResource.Claim> { res ->
            val body = call.receive<ClaimInviteRequest>()
            call.respondResult(
                inviteService.claimInvite(res.parent.code, body.password, body.displayName, body.deviceInfo),
            )
        }
    }
}

/**
 * Returns [inviteService] scoped to this call's authenticated principal, or null after
 * responding 401 when no principal is present (an auth-wall regression — the admin routes
 * mount inside `authenticate(JWT_PROVIDER)`).
 */
private suspend fun ApplicationCall.scoped(inviteService: InviteService): InviteServiceImpl? {
    val principal = userPrincipalOrNull()
    if (principal == null) {
        respond(HttpStatusCode.Unauthorized)
        return null
    }
    return (inviteService as InviteServiceImpl).copyWith(PrincipalProvider { principal })
}

private suspend inline fun <reified T : Any> ApplicationCall.respondResult(result: AppResult<T>) {
    when (result) {
        is AppResult.Success -> respond(result.data)
        is AppResult.Failure -> respondInviteError(result.error)
    }
}

private suspend fun ApplicationCall.respondInviteError(error: AppError) {
    val typed = error.withCorrelationId(callId)
    respond(typed.toHttpStatus(), typed)
}
