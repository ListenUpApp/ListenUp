package com.calypsan.listenup.web.features.sync

import androidx.lifecycle.ViewModelStore
import com.calypsan.listenup.client.presentation.sync.PendingOperationUi
import com.calypsan.listenup.client.presentation.sync.SyncIndicatorUiEvent
import com.calypsan.listenup.client.presentation.sync.SyncIndicatorViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import org.koin.core.Koin

/**
 * The edits this browser made that the server never accepted, and the two things to do about them.
 *
 * ⛔ **Only the failed half.** `SyncIndicatorViewModel` also carries `isSyncing`, `pendingCount` and
 * a running description, and web deliberately shows none of it — the standing decision is that this
 * client carries no pending-operations panel, because in-flight sync is chrome and the reader has
 * nothing to decide about it.
 *
 * A dead letter is not chrome. It is an edit the reader made, which the server refused past its
 * retry budget, and which nothing will ever repair on its own: the op's unchanged `(id, revision)`
 * means no reconcile, catch-up or firehose echo touches it. Before this, web had no retry and no
 * dismiss, so that edit was silently and permanently lost. Dismissing is not a discard either —
 * `PendingOperationQueue.dismissOp` emits a heal request that re-fetches server truth over the
 * abandoned local value.
 */
class DeadLetterSession(
    val failed: StateFlow<List<PendingOperationUi>>,
    val onRetry: (String) -> Unit,
    val onDismiss: (String) -> Unit,
    val onRetryAll: () -> Unit,
    val onDismissAll: () -> Unit,
    val close: () -> Unit,
)

/** How the dead-letter surface gets its state. */
typealias OpenDeadLetters = () -> DeadLetterSession

/** The production source: the shared [SyncIndicatorViewModel], read for its failures only. */
fun graphDeadLetters(koin: Koin): OpenDeadLetters =
    {
        val viewModel = koin.get<SyncIndicatorViewModel>()
        val store = ViewModelStore().apply { put("deadLetters", viewModel) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        DeadLetterSession(
            failed =
                viewModel.state
                    .map { it.failedOperations }
                    .stateIn(scope, SharingStarted.Eagerly, emptyList()),
            onRetry = { id -> viewModel.onEvent(SyncIndicatorUiEvent.RetryOperation(id)) },
            onDismiss = { id -> viewModel.onEvent(SyncIndicatorUiEvent.DismissOperation(id)) },
            onRetryAll = { viewModel.onEvent(SyncIndicatorUiEvent.RetryAll) },
            onDismissAll = { viewModel.onEvent(SyncIndicatorUiEvent.DismissAll) },
            close = {
                scope.cancel()
                store.clear()
            },
        )
    }

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
