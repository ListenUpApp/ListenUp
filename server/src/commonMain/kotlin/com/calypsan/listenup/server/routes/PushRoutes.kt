package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.PushService
import com.calypsan.listenup.api.dto.RegisterPushTokenBody
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.api.PushServiceImpl
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.plugins.RateLimitBuckets
import com.calypsan.listenup.server.plugins.toHttpStatus
import com.calypsan.listenup.server.plugins.userPrincipalOrNull
import com.calypsan.listenup.server.plugins.withCorrelationId
import com.calypsan.listenup.server.routes.resources.PushResources
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.resources.delete
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

/**
 * REST surface for [PushService]. Three endpoints, all requiring JWT authentication (mounted
 * inside the authenticate block in Application.kt):
 *
 *  - `POST /api/v1/push/tokens` (body: [RegisterPushTokenBody]) — registers/re-binds the
 *    caller's device token.
 *  - `DELETE /api/v1/push/tokens?token=...` — unregisters a device token.
 *  - `POST /api/v1/push/test` — sends a TestNotification to the caller's own devices.
 *    Rate-limited.
 *
 * Responds with bare types (unwrapped from AppResult) per the third-party REST surface
 * convention.
 */
fun Route.pushRoutes(pushService: PushService) {
    post<PushResources.Tokens> {
        val p = call.userPrincipalOrNull() ?: return@post call.respond(HttpStatusCode.Unauthorized)
        val body = call.receive<RegisterPushTokenBody>()
        val scoped = (pushService as PushServiceImpl).copyWith(PrincipalProvider { p })
        when (val result = scoped.registerToken(body.token, body.platform)) {
            is AppResult.Success -> call.respond(HttpStatusCode.NoContent)
            is AppResult.Failure -> call.respondBarePushError(result.error)
        }
    }

    delete<PushResources.Tokens> { resource ->
        val p = call.userPrincipalOrNull() ?: return@delete call.respond(HttpStatusCode.Unauthorized)
        val token = resource.token
        if (token.isNullOrBlank()) {
            return@delete call.respondBarePushError(ValidationError(message = "token query param is required."))
        }
        val scoped = (pushService as PushServiceImpl).copyWith(PrincipalProvider { p })
        when (val result = scoped.unregisterToken(token)) {
            is AppResult.Success -> call.respond(HttpStatusCode.NoContent)
            is AppResult.Failure -> call.respondBarePushError(result.error)
        }
    }

    rateLimit(RateLimitBuckets.PushTest) {
        post<PushResources.Test> {
            val p = call.userPrincipalOrNull() ?: return@post call.respond(HttpStatusCode.Unauthorized)
            val scoped = (pushService as PushServiceImpl).copyWith(PrincipalProvider { p })
            when (val result = scoped.sendTestNotification()) {
                is AppResult.Success -> call.respond(HttpStatusCode.NoContent)
                is AppResult.Failure -> call.respondBarePushError(result.error)
            }
        }
    }
}

private suspend fun ApplicationCall.respondBarePushError(error: AppError) {
    val typed = error.withCorrelationId(callId)
    respond(typed.toHttpStatus(), typed)
}
