package com.calypsan.listenup.client.presentation.sync

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.repository.SyncRepository
import com.calypsan.listenup.core.error.ErrorBus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

private val logger = KotlinLogging.logger {}

/** A "sync now" the person asked for: whether it is running, and whether the last one failed. */
data class ManualRefresh(
    val running: Boolean = false,
    val failed: Boolean = false,
)

/**
 * The one "sync now" every surface shares: the sync indicator's Refresh and the Library's pull-to-refresh.
 *
 * A failure goes to the error bus and is kept in [state] until the next ask, so the sync readout never says
 * "Synced just now" over a refresh that did not land. One instance for the app, so a pull in the Library is
 * what the shell's indicator reports.
 */
class ManualSyncRefresh(
    private val syncRepository: SyncRepository,
    private val errorBus: ErrorBus,
) {
    /** Running, or how the last refresh ended. */
    val state: StateFlow<ManualRefresh>
        field = MutableStateFlow(ManualRefresh())

    /** Syncs now. Returns at once if a refresh is already running. */
    suspend fun refresh() {
        if (state.value.running) return
        state.value = ManualRefresh(running = true)
        try {
            when (val result = syncRepository.sync()) {
                is AppResult.Success -> {
                    state.value = ManualRefresh()
                }

                is AppResult.Failure -> {
                    logger.warn { "Refresh failed: ${result.error.code} ${result.error.debugInfo.orEmpty()}" }
                    errorBus.emit(result.error)
                    state.value = ManualRefresh(failed = true)
                }
            }
        } finally {
            // A cancelled refresh (its screen went away) must not leave the shared state "running" for good.
            state.update { if (it.running) ManualRefresh() else it }
        }
    }
}
