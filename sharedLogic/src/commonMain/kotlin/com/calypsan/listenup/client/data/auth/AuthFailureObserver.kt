package com.calypsan.listenup.client.data.auth

import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.core.error.ErrorBus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/**
 * Watches the app-wide [ErrorBus] and turns a session-invalidating [AuthError]
 * into a soft logout, so a stale/invalid session lands the user on the login
 * screen instead of looping a generic error snackbar.
 *
 * This is the single, transport-agnostic place that reacts to auth failure:
 * a 401 from any surface (REST, RPC, SSE) is typed as an [AuthError] at the
 * `ErrorMapper` boundary, emitted to the bus by its consumer, and resolved here.
 * The token-refresh flow remains the first line of defence; this catches the
 * 401s that survive a failed (or absent) refresh.
 *
 * Only fires while currently [AuthState.Authenticated] — the pre-auth connect
 * and login flows produce their own auth errors that must not be misread as a
 * logout.
 */
internal class AuthFailureObserver(
    errorBus: ErrorBus,
    private val authSession: AuthSession,
    scope: CoroutineScope,
) {
    init {
        scope.launch {
            errorBus.errors.collect { error ->
                // Guard per item so a transient failure (e.g. clearAuthTokens hitting a locked
                // Keychain) logs and the observer KEEPS COLLECTING — instead of the collector dying
                // (permanently breaking soft-logout) and, on Kotlin/Native, killing the process.
                // Re-throw CancellationException so structured cancellation still works.
                try {
                    if (error.invalidatesSession() && authSession.authState.value is AuthState.Authenticated) {
                        if (error is AuthError.ServerInstanceChanged) {
                            // A DIFFERENT server ⇒ the cached library's provenance is broken —
                            // full sign-out wall is correct (spec §14/Q2).
                            logger.info { "Server instance changed (${error.code}); full sign-out → login" }
                            authSession.clearAuthTokens()
                        } else {
                            // Same-server expiry/refresh-death: soft-lapse. Local content stays
                            // usable; the shell banner offers sign-in (M2/M3).
                            logger.info {
                                "Session-invalidating auth error (${error.code}); soft-lapse → SessionLapsed"
                            }
                            authSession.clearSessionCredentials()
                        }
                    }
                } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn(e) { "Soft-logout handling failed for auth error ${error.code}; observer continues" }
                }
            }
        }
    }
}

/**
 * True for auth errors that mean the current session can no longer be used and
 * the user must sign in again. Permission-style auth errors (e.g. [AuthError.PermissionDenied])
 * are deliberately excluded — they mean "not allowed", not "logged out".
 */
internal fun AppError.invalidatesSession(): Boolean =
    when (this) {
        is AuthError.SessionExpired,
        is AuthError.SessionNotFound,
        is AuthError.InvalidRefreshToken,
        is AuthError.ServerInstanceChanged,
        -> true

        else -> false
    }
