package com.calypsan.listenup.server.organize

import com.calypsan.listenup.api.error.surfacedLogLine
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.logging.loggerFor
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val logger = loggerFor<OrganizeOnEditRelocator>()

/** How long after the last edit notification a book's replan fires — coalesces an edit session's rapid saves. */
private const val DEFAULT_EDIT_DEBOUNCE_MS = 2_000L

/**
 * The metadata-edit hook (spec §5): when a title/author/series edit changes the canonical path of
 * a book that was ALREADY sitting at its canonical path, the book relocates through the same
 * identity-safe [MoveManifestExecutor] path as a full run. Debounced per book — a burst of saves
 * within one edit session coalesces into a single replan; a new edit resets the timer.
 *
 * One client Save is several RPCs milliseconds apart (`updateBook`, then `setBookContributors`,
 * then `setBookSeries`), and only the first of them sees the book as it was before the Save — the
 * later ones already carry the new title at the old path. So a debounce window keeps the **first**
 * pre-edit payload it was given, never the latest. And once a window has started relocating it is
 * never cancelled: a move is a journaled multi-step manifest, and an edit arriving mid-way waits
 * for it to finish and then re-plans.
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
    private val lock = SynchronizedObject()
    private val windows = mutableMapOf<String, EditWindow>()

    /**
     * Notifies the relocator that [bookId] was just edited, passing the book's state **as it was
     * before the edit**. Returns immediately; the replan runs debounced.
     *
     * [previous] is the load-bearing argument. The replan runs after a debounce, by which time the
     * stored book already carries the edit — so it cannot tell "this edit made the path stale" from
     * "this book was never organized in the first place". The pre-edit payload answers that, and
     * only the caller has it. Within one debounce window only the first call's [previous] counts.
     */
    fun onBookEdited(
        bookId: BookId,
        previous: BookSyncPayload,
    ) {
        synchronized(lock) {
            install(
                bookId,
                when (val window = windows[bookId.value]) {
                    null -> {
                        debounce(bookId, Placement.JudgedBy(previous))
                    }

                    is EditWindow.Debouncing -> {
                        window.job.cancel()
                        debounce(bookId, window.placement)
                    }

                    is EditWindow.Relocating -> {
                        window.copy(followUp = window.followUp ?: previous)
                    }
                },
            )
        }
    }

    /**
     * Records [window] as [bookId]'s current one and starts its job (a no-op for one already
     * running). The caller holds [lock]; starting only after the entry is in place is what lets the
     * job find itself there, whatever dispatcher runs it.
     */
    private fun install(
        bookId: BookId,
        window: EditWindow,
    ) {
        windows[bookId.value] = window
        window.job.start()
    }

    /** A new, not-yet-started debounce window for [bookId]; see [install]. */
    private fun debounce(
        bookId: BookId,
        placement: Placement,
    ): EditWindow.Debouncing =
        EditWindow.Debouncing(placement, scope.launch(start = CoroutineStart.LAZY) { runWindow(bookId) })

    /**
     * Waits out the debounce, claims the book for relocation (unless a newer edit replaced this
     * window meanwhile), relocates, and hands over to a follow-up window when edits arrived mid-move.
     */
    private suspend fun runWindow(bookId: BookId) {
        delay(debounceMs)
        val self = currentCoroutineContext()[Job]
        val placement =
            synchronized(lock) {
                val window = windows[bookId.value]
                // A newer edit replaced this window after its delay ended — that window owns the book now.
                if (window !is EditWindow.Debouncing || window.job !== self) return
                windows[bookId.value] = EditWindow.Relocating(job = window.job, followUp = null)
                window.placement
            }
        var underCare = false
        try {
            underCare = relocateIfMoved(bookId, placement)
        } finally {
            synchronized(lock) {
                val window = windows[bookId.value]
                // Identity-conditional: only this job's own entry is ever replaced or removed.
                if (window is EditWindow.Relocating && window.job === self) {
                    val followUp = window.followUp
                    if (followUp == null) {
                        windows.remove(bookId.value)
                    } else {
                        // The book's pre-edit state for the follow-up is whatever this pass left: a
                        // book it found under the organizer's care is still under it, even though
                        // the edit that arrived mid-move saw a half-moved book.
                        install(
                            bookId,
                            debounce(bookId, if (underCare) Placement.UnderCare else Placement.JudgedBy(followUp)),
                        )
                    }
                }
            }
        }
    }

    /**
     * Replans [bookId] under the current settings and executes the move when its canonical path
     * changed. Returns whether the book was under the organizer's care, which a follow-up window
     * inherits.
     */
    private suspend fun relocateIfMoved(
        bookId: BookId,
        placement: Placement,
    ): Boolean {
        try {
            val settings = settingsStore.get().toPlannerSettings()
            // Conformance is maintained, never imposed: only a book that was ALREADY at its
            // canonical path is kept there as its metadata changes. A book its owner filed
            // somewhere else stays filed there — moving it because they fixed a typo in the title
            // is exactly the behaviour that makes people distrust software with their files.
            val underCare =
                when (placement) {
                    Placement.UnderCare -> true
                    is Placement.JudgedBy -> planBuilder.isCanonical(placement.previous, settings)
                }
            if (!underCare) return false
            val entry = planBuilder.buildForBook(bookId, settings) ?: return true
            when (val result = executor.execute(entry)) {
                is AppResult.Success -> {
                    logger.info { "organizer relocated ${bookId.value} after edit → ${entry.toRootRelPath}" }
                }

                is AppResult.Failure -> {
                    logger.warn {
                        "organizer edit-relocation failed for ${bookId.value}: ${result.error.surfacedLogLine()}"
                    }
                }
            }
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "organizer edit-relocation crashed for ${bookId.value}" }
            return false
        }
    }

    /** How a window decides whether its book is the organizer's to move. */
    private sealed interface Placement {
        /** Judge by the book as it was before the window's first edit. */
        data class JudgedBy(
            val previous: BookSyncPayload,
        ) : Placement

        /** A relocation that just ran found the book under the organizer's care. */
        data object UnderCare : Placement
    }

    /** One book's pending or running replan. */
    private sealed interface EditWindow {
        val job: Job

        /** Waiting out the debounce; a new edit restarts it, keeping [placement]. */
        data class Debouncing(
            val placement: Placement,
            override val job: Job,
        ) : EditWindow

        /** Moving the book; never cancelled. [followUp] is the first edit that arrived meanwhile. */
        data class Relocating(
            override val job: Job,
            val followUp: BookSyncPayload?,
        ) : EditWindow
    }
}
