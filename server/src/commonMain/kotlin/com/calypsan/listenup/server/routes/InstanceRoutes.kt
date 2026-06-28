package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.InstanceService
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.plugins.toHttpStatus
import com.calypsan.listenup.server.plugins.withCorrelationId
import io.ktor.resources.Resource
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.callid.callId
import io.ktor.server.resources.get
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

/** REST resource for the anonymous instance-info endpoint (OpenAPI / non-Kotlin clients). */
@Resource("/api/v1/instance")
class InstanceResource

/**
 * Third-party REST mirror of [InstanceService.getServerInfo]. First-party Kotlin
 * clients use the kotlinx.rpc proxy on `/api/rpc/public`; this anonymous GET
 * exists for the OpenAPI surface and non-Kotlin integrations. Same DTO, same
 * source — the route just unwraps the [AppResult] into an HTTP response.
 */
fun Route.instanceRoutes(instanceService: InstanceService) {
    get<InstanceResource> {
        when (val result = instanceService.getServerInfo()) {
            is AppResult.Success -> call.respond(result.data)
            is AppResult.Failure -> call.respondBareAppError(result.error)
        }
    }
}

private suspend fun ApplicationCall.respondBareAppError(error: AppError) {
    val status = error.toHttpStatus()
    val correlated = error.withCorrelationId(callId)
    respond(status, correlated)
}
