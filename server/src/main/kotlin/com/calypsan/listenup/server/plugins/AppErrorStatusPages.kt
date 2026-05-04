package com.calypsan.listenup.server.plugins

import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.api.error.ScanError
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.api.result.AppResult
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("AppErrorStatusPages")

/**
 * Surfaces unexpected throwables — genuine bugs, framework errors, OOM —
 * as a wire-shaped [InternalError] body with HTTP 500. Domain failures don't
 * get here: services return [AppResult.Failure] in-band, route handlers fold
 * them through [respondAppResult].
 *
 * Also handles 404s with a small JSON body so unknown paths don't return a
 * Ktor default page.
 */
fun Application.installAppErrorStatusPages() {
    install(StatusPages) {
        exception<Throwable> { call, ex ->
            // Cancellation must always re-raise — never swallow it.
            if (ex is CancellationException) throw ex
            val correlationId = call.callId
            logger.error("unhandled exception on {} correlationId={}", call.request.uri, correlationId, ex)
            val body: AppError = InternalError(correlationId)
            call.respond(HttpStatusCode.InternalServerError, body)
        }
        status(HttpStatusCode.NotFound) { call, status ->
            call.respond(status, mapOf("error" to "not_found", "path" to call.request.uri))
        }
    }
}

/** Status mapping for typed [AppError]. Used by both REST handlers and tests. */
internal fun AppError.toHttpStatus(): HttpStatusCode =
    when (this) {
        is AuthError.InvalidCredentials -> HttpStatusCode.Unauthorized
        is AuthError.EmailAlreadyExists -> HttpStatusCode.Conflict
        is AuthError.RegistrationDisabled -> HttpStatusCode.Forbidden
        is AuthError.SetupRequired -> HttpStatusCode.Conflict
        is AuthError.SetupAlreadyComplete -> HttpStatusCode.Conflict
        is AuthError.PendingApproval -> HttpStatusCode.Forbidden
        is AuthError.AccountDenied -> HttpStatusCode.Forbidden
        is AuthError.SessionExpired -> HttpStatusCode.Unauthorized
        is AuthError.SessionNotFound -> HttpStatusCode.Unauthorized
        is AuthError.InvalidRefreshToken -> HttpStatusCode.Unauthorized
        is AuthError.RateLimited -> HttpStatusCode.TooManyRequests
        is AuthError.WeakPassword -> HttpStatusCode.BadRequest
        is AuthError.PermissionDenied -> HttpStatusCode.Forbidden
        is ValidationError -> HttpStatusCode.BadRequest
        is InternalError -> HttpStatusCode.InternalServerError
        is ScanError.AlreadyRunning -> HttpStatusCode.Conflict
        is ScanError.LibraryPathNotConfigured -> HttpStatusCode.ServiceUnavailable
        is ScanError.LibraryPathNotFound -> HttpStatusCode.ServiceUnavailable
        is ScanError.FileUnreadable -> HttpStatusCode.InternalServerError
        is ScanError.MetadataParseError -> HttpStatusCode.InternalServerError
        is ScanError.TitleInferenceError -> HttpStatusCode.InternalServerError
    }

/** Stamp the request's correlation id onto a typed wire error. */
internal fun AppError.withCorrelationId(id: String?): AppError =
    when (this) {
        is AuthError.InvalidCredentials -> copy(correlationId = id)
        is AuthError.EmailAlreadyExists -> copy(correlationId = id)
        is AuthError.RegistrationDisabled -> copy(correlationId = id)
        is AuthError.SetupRequired -> copy(correlationId = id)
        is AuthError.SetupAlreadyComplete -> copy(correlationId = id)
        is AuthError.PendingApproval -> copy(correlationId = id)
        is AuthError.AccountDenied -> copy(correlationId = id)
        is AuthError.SessionExpired -> copy(correlationId = id)
        is AuthError.SessionNotFound -> copy(correlationId = id)
        is AuthError.InvalidRefreshToken -> copy(correlationId = id)
        is AuthError.RateLimited -> copy(correlationId = id)
        is AuthError.WeakPassword -> copy(correlationId = id)
        is AuthError.PermissionDenied -> copy(correlationId = id)
        is ValidationError -> copy(correlationId = id)
        is InternalError -> copy(correlationId = id)
        is ScanError.AlreadyRunning -> copy(correlationId = id)
        is ScanError.LibraryPathNotConfigured -> copy(correlationId = id)
        is ScanError.LibraryPathNotFound -> copy(correlationId = id)
        is ScanError.FileUnreadable -> copy(correlationId = id)
        is ScanError.MetadataParseError -> copy(correlationId = id)
        is ScanError.TitleInferenceError -> copy(correlationId = id)
    }
