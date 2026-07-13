package com.calypsan.listenup.client.features.shell.components

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.calypsan.listenup.api.error.AppError
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
 * Retryable errors show an "Action" button; non-retryable errors auto-dismiss.
 *
 * @param snackbarHostState The snackbar host to show messages on
 * @param onRetry Optional callback when user taps retry on a retryable error
 */
@Composable
fun GlobalErrorSnackbar(
    snackbarHostState: SnackbarHostState,
    onRetry: ((AppError) -> Unit)? = null,
    errorBus: ErrorBus = koinInject(),
) {
    LaunchedEffect(errorBus) {
        errorBus.errors.collect { error ->
            logger.warn { error.diagnosticLogLine() }
            if (error.debugInfo != null) {
                logger.debug { "Debug: ${error.debugInfo}" }
            }

            val result =
                snackbarHostState.showSnackbar(
                    message = error.localizedString(),
                    actionLabel = if (error.isRetryable) getString(Res.string.common_retry) else null,
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
