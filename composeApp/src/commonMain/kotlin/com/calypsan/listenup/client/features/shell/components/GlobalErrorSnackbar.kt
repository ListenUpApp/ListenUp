package com.calypsan.listenup.client.features.shell.components

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.client.core.error.ErrorBus
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Collects errors from [ErrorBus] and displays them as snackbars.
 *
 * Drop this into any screen that has a [SnackbarHostState] — but the
 * primary usage is in [AppShell] for global error display.
 *
 * Retryable errors show an "Action" button; non-retryable errors auto-dismiss.
 *
 * @param snackbarHostState The snackbar host to show messages on
 * @param onRetry Optional callback when user taps retry on a retryable error
 */
@Composable
fun GlobalErrorSnackbar(
    snackbarHostState: SnackbarHostState,
    onRetry: ((AppError) -> Unit)? = null,
) {
    LaunchedEffect(Unit) {
        ErrorBus.errors.collect { error ->
            logger.warn { "[${error.code}] ${error.message}" }
            if (error.debugInfo != null) {
                logger.debug { "Debug: ${error.debugInfo}" }
            }

            val result =
                snackbarHostState.showSnackbar(
                    message = error.message,
                    actionLabel = if (error.isRetryable) "Retry" else null,
                    duration =
                        if (error.isRetryable) {
                            SnackbarDuration.Long
                        } else {
                            SnackbarDuration.Short
                        },
                )

            if (result == SnackbarResult.ActionPerformed && error.isRetryable) {
                onRetry?.invoke(error)
            }
        }
    }
}
