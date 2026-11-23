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
 * Failures are logged and skipped — one attempt per boot, never fatal to startup, and at most
 * [MAX_RESUME_ATTEMPTS] boots in all: an apply that keeps failing is failing for a reason a restart will
 * not fix, so after that it is logged and left for an admin's manual Apply (which resets the count when it
 * succeeds). Progress events go to the shared import event bus, mirroring
 * [com.calypsan.listenup.server.api.ImportServiceImpl.apply].
 */
class InterruptedImportResumer(
    private val store: ImportStore,
    private val applier: ImportApplier,
    private val eventBus: MutableSharedFlow<ImportEvent>,
) {
    /** Re-applies every interrupted import that has attempts left. Returns the ids it attempted. */
    suspend fun resumeAll(): List<String> {
        val interrupted = store.listInterruptedApplies()
        if (interrupted.isEmpty()) return emptyList()
        logger.warn { "found ${interrupted.size} interrupted import apply(s) — resuming" }
        return interrupted.mapNotNull { id ->
            if (store.applyAttempts(id) >= MAX_RESUME_ATTEMPTS) {
                logger.error {
                    "import ${id.value} failed to apply on $MAX_RESUME_ATTEMPTS boots — not retrying; apply it manually"
                }
                return@mapNotNull null
            }
            store.recordApplyAttempt(id)
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

/** Boots on which an interrupted apply is retried before the resumer gives up on it. */
private const val MAX_RESUME_ATTEMPTS = 3
