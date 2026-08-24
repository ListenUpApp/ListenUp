package com.calypsan.listenup.server.organize

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.logging.loggerFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val logger = loggerFor<OrganizeOnEditRelocator>()

/** How long after the last edit notification a book's replan fires — coalesces an edit session's rapid saves. */
private const val DEFAULT_EDIT_DEBOUNCE_MS = 2_000L

/**
 * The metadata-edit hook (spec §5): when a title/author/series edit changes the canonical path of
 * a book that was ALREADY sitting at its canonical path, the book relocates through the same
 * identity-safe [MoveManifestExecutor] path as a full run. Debounced per book — a burst of saves
 * within one edit session coalesces into a single replan; a new edit resets the timer.
 *
 * *Conformance is maintained, never imposed.* A book its owner filed somewhere of their own
 * choosing is left exactly where they put it; nothing here relocates a book the organizer did not
 * place. That is what makes always-on organization safe.
 *
 * Fire-and-forget by design: [onBookEdited] never blocks the edit response, and a failed
 * relocation only logs — the edit itself already succeeded, and the next full organize run
 * (or the next edit) picks the book up again.
 */
class OrganizeOnEditRelocator(
    private val settingsStore: OrganizerSettingsStore,
    private val planBuilder: OrganizePlanBuilder,
    private val executor: MoveManifestExecutor,
    private val scope: CoroutineScope,
    private val debounceMs: Long = DEFAULT_EDIT_DEBOUNCE_MS,
) {
    private val mutex = Mutex()
    private val pending = mutableMapOf<String, Job>()

    /**
     * Notifies the relocator that [bookId] was just edited, passing the book's state **as it was
     * before the edit**. Returns immediately; the replan runs debounced.
     *
     * [previous] is the load-bearing argument. The replan runs after a debounce, by which time the
     * stored book already carries the edit — so it cannot tell "this edit made the path stale" from
     * "this book was never organized in the first place". The pre-edit payload answers that, and
     * only the caller has it.
     */
    fun onBookEdited(
        bookId: BookId,
        previous: BookSyncPayload,
    ) {
        scope.launch {
            val job =
                scope.launch(start = CoroutineStart.LAZY) {
                    delay(debounceMs)
                    relocateIfMoved(bookId, previous)
                    mutex.withLock { pending.remove(bookId.value) }
                }
            mutex.withLock {
                pending.remove(bookId.value)?.cancel()
                pending[bookId.value] = job
            }
            job.start()
        }
    }

    /** Replans [bookId] under the current settings and executes the move when its canonical path changed. */
    private suspend fun relocateIfMoved(
        bookId: BookId,
        previous: BookSyncPayload,
    ) {
        try {
            val settings = settingsStore.get()
            // Conformance is maintained, never imposed: only a book that was ALREADY at its
            // canonical path is kept there as its metadata changes. A book its owner filed
            // somewhere else stays filed there — moving it because they fixed a typo in the title
            // is exactly the behaviour that makes people distrust software with their files.
            if (!planBuilder.isCanonical(previous, settings.toPlannerSettings())) return
            val entry = planBuilder.buildForBook(bookId, settings.toPlannerSettings()) ?: return
            when (val result = executor.execute(entry)) {
                is AppResult.Success -> {
                    logger.info { "organizer relocated ${bookId.value} after edit → ${entry.toRootRelPath}" }
                }

                is AppResult.Failure -> {
                    logger.warn { "organizer edit-relocation failed for ${bookId.value}: ${result.error.debugInfo}" }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "organizer edit-relocation crashed for ${bookId.value}" }
        }
    }
}
