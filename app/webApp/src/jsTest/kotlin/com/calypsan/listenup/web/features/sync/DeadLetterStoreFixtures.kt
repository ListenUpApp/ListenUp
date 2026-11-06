package com.calypsan.listenup.web.features.sync

import com.calypsan.listenup.client.presentation.sync.PendingOperationUi
import kotlinx.coroutines.flow.MutableStateFlow

/** A session over a state that never changes — the shape specs pass in place of the graph. */
fun fixedDeadLetters(
    failed: List<PendingOperationUi> = emptyList(),
    onRetry: (String) -> Unit = {},
    onDismiss: (String) -> Unit = {},
    onRetryAll: () -> Unit = {},
    onDismissAll: () -> Unit = {},
): OpenDeadLetters =
    {
        DeadLetterSession(
            failed = MutableStateFlow(failed),
            onRetry = onRetry,
            onDismiss = onDismiss,
            onRetryAll = onRetryAll,
            onDismissAll = onDismissAll,
            close = {},
        )
    }
