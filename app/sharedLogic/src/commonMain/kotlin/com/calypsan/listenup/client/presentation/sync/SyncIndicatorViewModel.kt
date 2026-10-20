package com.calypsan.listenup.client.presentation.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.client.domain.model.PendingOperation
import com.calypsan.listenup.client.domain.model.PendingOperationStatus
import com.calypsan.listenup.client.domain.model.PendingOperationType
import com.calypsan.listenup.client.domain.model.SyncState
import com.calypsan.listenup.client.domain.repository.PendingOperationRepository
import com.calypsan.listenup.client.domain.repository.SyncRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.calypsan.listenup.client.presentation.WhileSubscribedForUi
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.error.ErrorBus
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * A single pending operation for UI display.
 */
data class PendingOperationUi(
    val id: String,
    val description: String,
    val isFailed: Boolean,
    val error: String?,
) {
    /** [description] for the Swift Export boundary: a member named `description` collides with `NSObject.description` and is never exported. */
    val descriptionText: String get() = description
}

/**
 * UI state for sync indicator.
 */
data class SyncIndicatorUiState(
    /**
     * Whether a sync operation is currently in progress.
     */
    val isSyncing: Boolean = false,
    /**
     * Description of current operation (if syncing).
     */
    val currentOperationDescription: String? = null,
    /**
     * Number of pending operations waiting to sync.
     * Excludes silent operations (listening events, playback position, preferences).
     */
    val pendingCount: Int = 0,
    /**
     * Failed operations that need user attention.
     */
    val failedOperations: List<PendingOperationUi> = emptyList(),
    /**
     * Whether there are any failed operations.
     */
    val hasErrors: Boolean = false,
    /**
     * When the library last finished syncing, in epoch ms, or null before the first sync this session. Kept through
     * the next sync, so "Synced 2 min ago" does not blink while a refresh runs.
     */
    val lastSyncedAtMs: Long? = null,
    /**
     * The last "sync now" the person asked for failed. Cleared when they ask again. The failure itself went to the
     * error bus; this is what the indicator keeps saying until then, so "Synced just now" never covers for it.
     */
    val refreshFailed: Boolean = false,
)

/**
 * Events from the sync indicator UI.
 */
sealed interface SyncIndicatorUiEvent {
    /**
     * User requested to retry a failed operation.
     */
    data class RetryOperation(
        val operationId: String,
    ) : SyncIndicatorUiEvent

    /**
     * User requested to dismiss a failed operation.
     * This discards local changes and marks the entity for re-sync.
     */
    data class DismissOperation(
        val operationId: String,
    ) : SyncIndicatorUiEvent

    /**
     * User requested to retry all failed operations.
     */
    data object RetryAll : SyncIndicatorUiEvent

    /**
     * User requested to dismiss all failed operations.
     */
    data object DismissAll : SyncIndicatorUiEvent

    /** User asked to sync now ("Refresh now"). */
    data object RefreshRequested : SyncIndicatorUiEvent
}

/**
 * ViewModel for sync indicator component.
 *
 * Provides reactive state for displaying sync status:
 * - Whether sync is in progress
 * - Count of pending operations
 * - List of failed operations with retry/dismiss actions
 *
 * The sync indicator typically appears in:
 * - App bar or navigation
 * - Floating indicator when operations are pending
 * - Error sheet when operations fail
 */
class SyncIndicatorViewModel(
    private val pendingOperationRepository: PendingOperationRepository,
    private val syncRepository: SyncRepository,
    private val errorBus: ErrorBus,
) : ViewModel() {
    /** A "sync now" this indicator started: running, or how the last one ended. */
    private val manualRefresh = MutableStateFlow(ManualRefresh())

    /** The last successful sync's time, remembered across the syncs that follow it. */
    private val lastSyncedAtMs: Flow<Long?> =
        syncRepository.syncState
            .scan(
                null as Long?,
            ) { last, state -> if (state is SyncState.Success) state.timestamp.epochMillis else last }
            .distinctUntilChanged()

    val expanded: StateFlow<Boolean>
        field = MutableStateFlow(false)

    val state: StateFlow<SyncIndicatorUiState> =
        combine(
            flow = pendingOperationRepository.observeVisibleOperations(),
            flow2 = pendingOperationRepository.observeInProgressOperation(),
            flow3 = pendingOperationRepository.observeFailedOperations(),
            flow4 = syncRepository.syncState,
            flow5 = combine(lastSyncedAtMs, manualRefresh, ::Pair),
        ) { visibleOps, inProgress, failedOps, pullSyncState, (lastSynced, refresh) ->
            val pendingCount =
                visibleOps.count {
                    it.status == PendingOperationStatus.PENDING
                }
            val failedUi =
                failedOps.map { op ->
                    PendingOperationUi(
                        id = op.id,
                        description = describeOperation(op),
                        isFailed = true,
                        error = op.lastError,
                    )
                }

            // Pull sync is active when library sync is in progress
            val isPullSyncing =
                pullSyncState is SyncState.Syncing ||
                    pullSyncState is SyncState.Progress ||
                    pullSyncState is SyncState.Retrying

            val pullSyncDescription =
                when (pullSyncState) {
                    is SyncState.Progress -> pullSyncState.message

                    is SyncState.Syncing -> "Syncing library…"

                    is SyncState.Retrying -> "Retrying sync (${pullSyncState.attempt}/${pullSyncState.maxAttempts})…"

                    SyncState.Idle,
                    is SyncState.Success,
                    is SyncState.Error,
                    is SyncState.LibraryMismatch,
                    -> null
                }

            SyncIndicatorUiState(
                // A refresh the person asked for says so from the tap, whether or not the pull reports a state.
                isSyncing = inProgress != null || isPullSyncing || refresh.running,
                currentOperationDescription =
                    pullSyncDescription
                        ?: inProgress?.let { describeOperation(it) },
                pendingCount = pendingCount,
                failedOperations = failedUi,
                hasErrors = failedOps.isNotEmpty(),
                lastSyncedAtMs = lastSynced,
                refreshFailed = refresh.failed,
            )
        }.stateIn(
            scope = viewModelScope,
            started = WhileSubscribedForUi,
            initialValue = SyncIndicatorUiState(),
        )

    fun onEvent(event: SyncIndicatorUiEvent) {
        when (event) {
            is SyncIndicatorUiEvent.RetryOperation -> retryOperation(event.operationId)
            is SyncIndicatorUiEvent.DismissOperation -> dismissOperation(event.operationId)
            is SyncIndicatorUiEvent.RetryAll -> retryAllFailed()
            is SyncIndicatorUiEvent.DismissAll -> dismissAllFailed()
            is SyncIndicatorUiEvent.RefreshRequested -> refresh()
        }
    }

    fun toggleExpanded() {
        expanded.value = !expanded.value
    }

    private fun refresh() {
        if (manualRefresh.value.running) return
        manualRefresh.value = ManualRefresh(running = true)
        viewModelScope.launch {
            when (val result = syncRepository.sync()) {
                is AppResult.Success -> manualRefresh.value = ManualRefresh()
                is AppResult.Failure -> {
                    logger.warn { "Refresh failed: ${result.error.code} ${result.error.debugInfo}" }
                    errorBus.emit(result.error)
                    manualRefresh.value = ManualRefresh(failed = true)
                }
            }
        }
    }

    private fun retryOperation(id: String) {
        viewModelScope.launch {
            pendingOperationRepository.retry(id)
        }
    }

    private fun dismissOperation(id: String) {
        viewModelScope.launch {
            pendingOperationRepository.dismiss(id)
        }
    }

    private fun retryAllFailed() {
        viewModelScope.launch {
            state.value.failedOperations.forEach { op ->
                pendingOperationRepository.retry(op.id)
            }
        }
    }

    private fun dismissAllFailed() {
        viewModelScope.launch {
            state.value.failedOperations.forEach { op ->
                pendingOperationRepository.dismiss(op.id)
            }
        }
    }

    /**
     * Generate a human-readable description of an operation.
     */
    private fun describeOperation(operation: PendingOperation): String {
        val entityName = operation.entityId?.take(8) ?: "item"
        return operation.operationType.describe(entityName)
    }
}

/** A "sync now" started from the indicator: whether it is running, and whether the last one failed. */
private data class ManualRefresh(
    val running: Boolean = false,
    val failed: Boolean = false,
)

internal sealed interface OpDescription {
    /** Operation describes work on a specific entity; [template] is invoked with the entity name. */
    data class Entity(
        val template: (String) -> String,
    ) : OpDescription

    /** Operation has no per-entity context; [text] is the fixed description shown to the user. */
    data class Global(
        val text: String,
    ) : OpDescription
}

internal val PendingOperationType.description: OpDescription
    get() =
        when (this) {
            PendingOperationType.BOOK_UPDATE -> OpDescription.Entity { name -> "Updating book $name" }
            PendingOperationType.SERIES_UPDATE -> OpDescription.Entity { name -> "Updating series $name" }
            PendingOperationType.CONTRIBUTOR_UPDATE -> OpDescription.Entity { name -> "Updating contributor $name" }
            PendingOperationType.PROFILE_UPDATE -> OpDescription.Global("Updating profile")
            PendingOperationType.USER_PREFERENCES -> OpDescription.Global("Syncing preferences")
            PendingOperationType.PLAYBACK_POSITION -> OpDescription.Global("Syncing playback position")
            PendingOperationType.LISTENING_EVENT -> OpDescription.Global("Syncing listening data")
            PendingOperationType.OTHER -> OpDescription.Global("Syncing changes")
        }

internal fun PendingOperationType.describe(entityName: String): String =
    when (val d = description) {
        is OpDescription.Entity -> d.template(entityName)
        is OpDescription.Global -> d.text
    }
