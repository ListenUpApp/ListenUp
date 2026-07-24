package com.calypsan.listenup.client.features.shell.components

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.diagnosticLogLine
import com.calypsan.listenup.client.presentation.error.localizedString
import com.calypsan.listenup.core.error.ErrorBus
import io.github.oshai.kotlinlogging.KotlinLogging
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_retry
import org.jetbrains.compose.resources.getString
import org.koin.compose.koinInject

private val logger = KotlinLogging.logger {}

/**
 * Collects errors from [ErrorBus] and displays them as snackbars.
 *
 * Drop this into any screen that has a [SnackbarHostState] — but the
 * primary usage is in [AppShell] for global error display.
 *
 * The Retry action is shown ONLY when the caller both supplies [onRetry] and confirms via
 * [canRetry] that it can actually act on this specific error. An error's [AppError.isRetryable]
 * flag alone is not enough: it means "a retry middleware *could* re-fire the call," not "this
 * snackbar has a handler wired." Showing a Retry button that does nothing is a dishonest
 * affordance, so the button appears only when a real handler exists.
 *
 * @param snackbarHostState The snackbar host to show messages on
 * @param onRetry Invoked when the user taps Retry on an error [canRetry] accepted
 * @param canRetry Predicate naming which errors the caller can actually retry; defaults to none
 */
@Composable
fun GlobalErrorSnackbar(
    snackbarHostState: SnackbarHostState,
    onRetry: ((AppError) -> Unit)? = null,
    canRetry: (AppError) -> Boolean = { false },
    errorBus: ErrorBus = koinInject(),
) {
    LaunchedEffect(errorBus, onRetry, canRetry) {
        errorBus.errors.collect { error ->
            logger.warn { error.diagnosticLogLine() }
            if (error.debugInfo != null) {
                logger.debug { "Debug: ${error.debugInfo}" }
            }

            val offerRetry = onRetry != null && canRetry(error)
            val result =
                snackbarHostState.showSnackbar(
                    message = error.snackbarMessage(),
                    actionLabel = if (offerRetry) getString(Res.string.common_retry) else null,
                    duration = if (offerRetry) SnackbarDuration.Long else SnackbarDuration.Short,
                )

            if (result == SnackbarResult.ActionPerformed && offerRetry) {
                onRetry?.invoke(error)
            }
        }
    }
}

/**
 * The user-facing snackbar text for [this] error. For [AuthError.RateLimited] it surfaces the
 * server's `retryAfterSeconds` (otherwise unused despite its contract KDoc) so the user knows how
 * long to wait; every other error uses its localized string.
 */
internal suspend fun AppError.snackbarMessage(): String =
    if (this is AuthError.RateLimited) {
        rateLimitedSnackbarMessage(retryAfterSeconds)
    } else {
        localizedString()
    }

/** Pure, testable rate-limit message carrying the concrete wait time. */
internal fun rateLimitedSnackbarMessage(retryAfterSeconds: Int): String =
    "Too many attempts. Try again in ${retryAfterSeconds}s."
