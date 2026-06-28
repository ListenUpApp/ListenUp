package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.plugins.toHttpStatus
import com.calypsan.listenup.server.plugins.withCorrelationId
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.callid.callId
import io.ktor.server.request.uri
import io.ktor.server.response.respond

private val logger = KotlinLogging.logger {}

/**
 * Renders an [AppResult] as the canonical wire response: `Success → 200`, `Failure →` the
 * error's mapped status. The explicit `: AppResult<T>` type on [body] keeps kotlinx.serialization's
 * polymorphic discriminator (smart-casting to a concrete variant would strip it).
 *
 * Every rendered failure is logged centrally: 5xx at ERROR, everything else at DEBUG, with the
 * error code, correlation id, and request path.
 *
 * Declared `internal` so the inline function can access the `internal` helpers
 * [toHttpStatus] and [withCorrelationId] from the `plugins` package (same module).
 */
internal suspend inline fun <reified T : Any> ApplicationCall.respondAppResult(result: AppResult<T>) {
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
            logAppErrorResponse(typed, status, request.uri)
        }
    }
    respond(status, body)
}

/** Logs a domain-error response: 5xx → ERROR, else DEBUG. Non-inline so it can hold the private logger. */
internal fun logAppErrorResponse(
    error: AppError,
    status: HttpStatusCode,
    path: String,
) {
    val msg = "domain error: code=${error.code} status=${status.value} path=$path correlationId=${error.correlationId}"
    if (status.value >= HTTP_SERVER_ERROR_FLOOR) {
        logger.error { msg }
    } else {
        logger.debug { msg }
    }
}

private const val HTTP_SERVER_ERROR_FLOOR = 500
