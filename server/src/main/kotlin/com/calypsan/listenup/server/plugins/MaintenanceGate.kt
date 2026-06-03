package com.calypsan.listenup.server.plugins

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.BackupError
import com.calypsan.listenup.server.backup.MaintenanceState
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.path
import io.ktor.server.response.respondText
import kotlinx.serialization.encodeToString

/**
 * Path prefixes that remain reachable during a restore. The RPC mount must stay open
 * so that the progress stream (`observeProgress`) and the restore call itself continue
 * to function while the gate is raised.
 */
private val ALLOW_DURING_RESTORE = listOf("/api/rpc")

/**
 * Installs a pipeline interceptor that returns HTTP 503 with a typed
 * [BackupError.RestoreInProgress] body for every request that is not on the
 * [ALLOW_DURING_RESTORE] allowlist while a restore is in progress.
 *
 * **Why `intercept` and not `createApplicationPlugin { onCall { ... } }`:**
 * The `onCall` hook in Ktor's plugin API runs "even if the call is already handled by
 * another handler" — responding inside it does not stop the route handler from executing
 * (which would cause a double-response error). Direct `intercept` on
 * [ApplicationCallPipeline.Plugins] followed by [io.ktor.util.pipeline.PipelineContext.finish]
 * genuinely halts the pipeline so the route handler is never invoked.
 *
 * **Not wired into `Application.module()` here.** This function is registered by the Koin
 * module in Task 6 after the [MaintenanceState] singleton is constructed.
 */
fun Application.installMaintenanceGate(state: MaintenanceState) {
    intercept(ApplicationCallPipeline.Plugins) {
        val path = call.request.path()
        if (state.isActive && ALLOW_DURING_RESTORE.none { path.startsWith(it) }) {
            call.respondText(
                text = contractJson.encodeToString<AppError>(BackupError.RestoreInProgress()),
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.ServiceUnavailable,
            )
            finish()
        }
    }
}
