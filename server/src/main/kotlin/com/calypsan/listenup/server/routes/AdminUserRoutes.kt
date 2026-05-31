package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.AdminUserService
import com.calypsan.listenup.api.dto.auth.AdminUserPatch
import com.calypsan.listenup.api.dto.auth.PendingRegistrationDecision
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.resources.AdminUserResources
import com.calypsan.listenup.api.resources.RegistrationPolicyResource
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.api.AdminUserServiceImpl
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.plugins.toHttpStatus
import com.calypsan.listenup.server.plugins.userPrincipalOrNull
import com.calypsan.listenup.server.plugins.withCorrelationId
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.callid.callId
import io.ktor.server.request.receive
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.patch
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

/**
 * REST surface for [AdminUserService] — the administrative account lifecycle:
 *
 *  - `GET    /api/v1/admin/users`                          — full user roster
 *  - `GET    /api/v1/admin/users/pending`                  — approval queue
 *  - `GET    /api/v1/admin/users/search?q=…`               — match name/email
 *  - `GET    /api/v1/admin/users/{id}`                     — single user
 *  - `PATCH  /api/v1/admin/users/{id}` (AdminUserPatch)    — partial update
 *  - `DELETE /api/v1/admin/users/{id}`                     — soft-delete a user
 *  - `POST   /api/v1/admin/users/pending-decision` (PendingRegistrationDecision)
 *                                                          — approve/deny an applicant
 *  - `GET    /api/v1/admin/settings/registration`          — read the policy
 *  - `PUT    /api/v1/admin/settings/registration` (RegistrationPolicy) — set the policy
 *
 * RPC is the first-class surface; these exist so the same operations are reachable over
 * plain REST. The admin gate (ROOT/ADMIN) lives *inside* the service — every handler binds
 * the request to the authenticated principal via [AdminUserServiceImpl.copyWith] and
 * delegates; a non-admin caller surfaces as `AuthError.PermissionDenied` (403), an absent
 * principal as 401 (an auth-wall regression, since these mount inside `authenticate`).
 * Responds with bare types (unwrapped from AppResult) per the third-party REST convention.
 */
fun Route.adminUserRoutes(adminUserService: AdminUserService) {
    get<AdminUserResources.List> {
        val service = call.scoped(adminUserService) ?: return@get
        call.respondResult(service.listUsers())
    }

    get<AdminUserResources.Pending> {
        val service = call.scoped(adminUserService) ?: return@get
        call.respondResult(service.listPendingUsers())
    }

    get<AdminUserResources.Search> { res ->
        val service = call.scoped(adminUserService) ?: return@get
        call.respondResult(service.searchUsers(res.q))
    }

    get<AdminUserResources.Detail> { res ->
        val service = call.scoped(adminUserService) ?: return@get
        call.respondResult(service.getUser(UserId(res.id)))
    }

    patch<AdminUserResources.Detail> { res ->
        val service = call.scoped(adminUserService) ?: return@patch
        val patch = call.receive<AdminUserPatch>()
        call.respondResult(service.updateUser(UserId(res.id), patch))
    }

    delete<AdminUserResources.Detail> { res ->
        val service = call.scoped(adminUserService) ?: return@delete
        when (val result = service.deleteUser(UserId(res.id))) {
            is AppResult.Success -> call.respond(HttpStatusCode.NoContent)
            is AppResult.Failure -> call.respondAdminUserError(result.error)
        }
    }

    post<AdminUserResources.PendingDecision> {
        val service = call.scoped(adminUserService) ?: return@post
        val decision = call.receive<PendingRegistrationDecision>()
        call.respondResult(service.decidePendingRegistration(decision))
    }

    get<RegistrationPolicyResource> {
        val service = call.scoped(adminUserService) ?: return@get
        call.respondResult(service.getRegistrationPolicy())
    }

    put<RegistrationPolicyResource> {
        val service = call.scoped(adminUserService) ?: return@put
        val policy = call.receive<RegistrationPolicy>()
        when (val result = service.setRegistrationPolicy(policy)) {
            is AppResult.Success -> call.respond(HttpStatusCode.OK)
            is AppResult.Failure -> call.respondAdminUserError(result.error)
        }
    }
}

/**
 * Returns [adminUserService] scoped to this call's authenticated principal, or null after
 * responding 401 when no principal is present (an auth-wall regression — these routes mount
 * inside `authenticate(JWT_PROVIDER)`).
 */
private suspend fun ApplicationCall.scoped(adminUserService: AdminUserService): AdminUserServiceImpl? {
    val principal = userPrincipalOrNull()
    if (principal == null) {
        respond(HttpStatusCode.Unauthorized)
        return null
    }
    return (adminUserService as AdminUserServiceImpl).copyWith(PrincipalProvider { principal })
}

private suspend inline fun <reified T> ApplicationCall.respondResult(result: AppResult<T>) {
    when (result) {
        is AppResult.Success -> respond(result.data as Any)
        is AppResult.Failure -> respondAdminUserError(result.error)
    }
}

private suspend fun ApplicationCall.respondAdminUserError(error: AppError) {
    val typed = error.withCorrelationId(callId)
    respond(typed.toHttpStatus(), typed)
}
