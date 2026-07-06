package com.calypsan.listenup.server.absimport

import com.calypsan.listenup.api.dto.imports.ImportEvent
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.logging.loggerFor
import kotlinx.coroutines.flow.MutableSharedFlow

private val logger = loggerFor<InterruptedImportResumer>()

/**
 * Boot-time healer for ABS imports whose apply was interrupted (server crash or failure mid-burst):
 * any import directory carrying an `.applying` marker without `.applied` is re-applied. Apply is
 * idempotent (stable session ids, last-played-wins positions, full-history stats recompute — see
 * [ImportApplier]), so re-running converges the partial state: rows complete, stats recompute,
 * `.applied` lands, and connected clients get the LibraryDataChanged nudge they were owed.
 *
 * Failures are logged and skipped — one attempt per boot, never fatal to startup. Progress events go
 * to the shared import event bus, mirroring
 * [com.calypsan.listenup.server.api.ImportServiceImpl.apply].
 */
class InterruptedImportResumer(
    private val store: ImportStore,
    private val applier: ImportApplier,
    private val eventBus: MutableSharedFlow<ImportEvent>,
) {
    /** Re-applies every interrupted import. Returns the ids it attempted. */
    suspend fun resumeAll(): List<String> {
        val interrupted = store.listInterruptedApplies()
        if (interrupted.isEmpty()) return emptyList()
        logger.warn { "found ${interrupted.size} interrupted import apply(s) — resuming" }
        return interrupted.map { id ->
            when (val result = applier.apply(id) { eventBus.tryEmit(it) }) {
                is AppResult.Success -> {
                    logger.info {
                        "resumed interrupted import ${id.value}: " +
                            "${result.data.importedCount} positions, ${result.data.sessionsImported} sessions"
                    }
                }

                is AppResult.Failure -> {
                    logger.error {
                        "resume of interrupted import ${id.value} failed (${result.error.code}) — will retry next boot"
                    }
                }
            }
            id.value
        }
    }
}
