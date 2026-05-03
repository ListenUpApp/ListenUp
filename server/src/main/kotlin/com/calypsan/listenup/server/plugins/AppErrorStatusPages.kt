package com.calypsan.listenup.server.plugins

import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.server.auth.AuthException
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
 * Catches typed [AuthException] and unwraps the inner [AuthError] into the wire
 * shape; everything else becomes [InternalError]. Stack traces stay on the server.
 *
 * REST-side only. The kotlinx.rpc transport has its own exception channel; that
 * gets wired in `RpcRoutes`.
 */
fun Application.installAppErrorStatusPages() {
    install(StatusPages) {
        exception<AuthException> { call, ex ->
            val correlationId = call.callId
            val typed = ex.error.withCorrelationId(correlationId)
            val (status, body) = typed.toHttpStatus() to typed
            logger.warn(
                "AuthException {} on {} correlationId={}",
                ex.error::class.simpleName,
                call.request.uri,
                correlationId,
            )
            call.respond(status, body)
        }
        exception<Throwable> { call, ex ->
            // Cancellation must always re-raise — never swallow it.
            if (ex is CancellationException) throw ex
            val correlationId = call.callId
            logger.error("unhandled exception on {} correlationId={}", call.request.uri, correlationId, ex)
            val body: AppError = InternalError(correlationId)
            call.respond(HttpStatusCode.InternalServerError, body)
        }
    }
}

/** Status mapping for typed [AppError]. Surface this to RPC handlers too. */
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
        is InternalError -> HttpStatusCode.InternalServerError
    }

/** Stamp the request's correlation id onto the typed wire value. */
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
        is InternalError -> copy(correlationId = id)
    }
