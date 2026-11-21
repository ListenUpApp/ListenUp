package com.calypsan.listenup.client.playback

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.RecordListeningEventRequest
import com.calypsan.listenup.client.device.DeviceInfoProvider
import com.calypsan.listenup.client.data.local.db.ListeningEventDao
import com.calypsan.listenup.client.data.local.db.ListeningEventEntity
import com.calypsan.listenup.client.data.local.db.TentativeSpanDao
import com.calypsan.listenup.client.data.local.db.TentativeSpanEntity
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.datetime.TimeZone

private const val LOGGER_NAME = "com.calypsan.listenup.client.playback.ListeningEventRecorder"

// Looked up per call rather than held: the recorder logs a handful of lines per listen, and a logger captured when
// this file first loads would miss a factory installed later (the iOS file tap, a spec).
private val logger: KLogger get() = KotlinLogging.logger(LOGGER_NAME)

/**
 * How many times one span's finalize may fail in a process before it is set aside. Past this the failure is not
 * transient, and holding every later play behind that one row would stop recording altogether.
 */
internal const val MAX_FINALIZE_ATTEMPTS = 3

/**
 * Drives the per-span recording state machine: open a [TentativeSpanEntity] on play,
 * extend it on each heartbeat, finalize it into a [ListeningEventEntity] (plus a pending
 * op) on pause / book-end / sleep-timer / speed-change / seek / cross-book transition.
 *
 * Only one span can be open at a time — a user listens to one book at a time.
 *
 * **A play that was never heard.** A heartbeat ([onPeriodicTick]) is the player saying audio is playing, so a
 * heartbeat with no span open opens one there. Android can report a resume from the notification, lock screen,
 * headset or car before it knows which book is playing; that play opened nothing, and without this the whole listen
 * went unrecorded while its positions kept saving.
 *
 * **A span that cannot be finalized.** A finalize that fails leaves its span in place as the recovery breadcrumb, and
 * the next play retries it rather than open a new span over it. After [MAX_FINALIZE_ATTEMPTS] failures in one
 * process the span is set aside: logged at ERROR, kept in `tentative_span` for [recoverOrphan] on the next launch,
 * and no longer in the way of the listening after it.
 *
 * **Orphan recovery.** On app start, if a [TentativeSpanEntity] row is found it means the
 * previous run ended without closing the span (crash / OS-kill / battery die). Call
 * [recoverOrphan] once before playback resumes to promote the orphan to a
 * [ListeningEventEntity]; loss is bounded to one heartbeat interval.
 *
 * **Heartbeat scheduling.** Heartbeat cadence is NOT this class's responsibility — the
 * platform playback layer calls [onPeriodicTick] from its existing periodic
 * position-update loop (the same tick that drives [ProgressTracker]).
 *
 * **Spans that heard nothing.** A span that took no time (play + pause within the same
 * millisecond) or covered no audio (it ends at or before the position it started from) is
 * dropped, not recorded. Each seek splits the span, so a resume's auto-rewind, a skip back, or
 * a pause reported at the position a seek landed on closes a span that heard nothing; and a
 * player that reports playing while the position stands still is not a listen either.
 *
 * **Enqueue indirection.** Rather than taking a [com.calypsan.listenup.client.data.sync.PendingOperationQueue]
 * directly, the recorder accepts a suspend lambda `enqueue` of just the facts it decides —
 * entity id, encoded payload, owning user. Which outbox channel and
 * [com.calypsan.listenup.client.data.sync.domains.OpKind] the write lands on is a DI wiring
 * concern, not a recorder concern: production Koin wiring closes over
 * [com.calypsan.listenup.client.data.sync.domains.OutboxChannels.ListeningEvents] and
 * [com.calypsan.listenup.client.data.sync.domains.OpKind.Upsert]. This keeps commonMain free
 * of a concrete class dependency and makes the class trivially testable: tests pass a
 * capturing lambda.
 *
 * @property listeningEventDao DAO for the `listening_events` table.
 * @property tentativeSpanDao DAO for the single-row `tentative_span` table.
 * @property transactionRunner Runs the finalize writes (event upsert + enqueue + tentative
 *   delete) in one all-or-nothing transaction, so a failed enqueue rolls back the delete and
 *   the tentative span survives as the recovery breadcrumb for [recoverOrphan].
 * @property enqueue Suspend function that persists a pending op for the finalized span. It runs
 *   INSIDE the finalize transaction and must NOT signal the drain itself (pass `signal = false` in
 *   the DI wiring) — the pre-commit signal would wake a drain that reads WAL state where the new row
 *   is not yet visible, stranding the op. [signalEnqueued] fires the drain post-commit instead.
 * @property signalEnqueued Ticks the outbox drain signal. Called only AFTER the finalize transaction
 *   commits, so the drain sees the freshly-committed op. On rollback it is never called.
 * @property currentUserId Returns the signed-in user's ID, or null if unauthenticated.
 *   A null result causes all write operations to no-op silently — no pending write without
 *   an owner.
 * @property deviceInfo Single source of the running device's identity; the persisted
 *   `device_label` is derived from it (user-facing name preferred, else hardware model).
 *   A null derived value is acceptable (the column is advisory).
 * @property processId Identity of the current app-process launch — an in-memory UUID minted
 *   once per process (the DI [single][org.koin.core.module.Module] is memoized, so the default
 *   [Uuid.random] is evaluated exactly once per running app). Every span this recorder opens is
 *   stamped with it, and [recoverOrphan] compares against it to tell the current process's live
 *   span apart from an orphan left by a prior process. Injected for deterministic testing.
 * @property clock Injected for deterministic testing. Defaults to [Clock.System].
 * @property timeZone Injected for deterministic testing. Defaults to
 *   [TimeZone.currentSystemDefault].
 */
class ListeningEventRecorder internal constructor(
    private val listeningEventDao: ListeningEventDao,
    private val tentativeSpanDao: TentativeSpanDao,
    private val transactionRunner: TransactionRunner,
    private val enqueue: suspend (entityId: String, payload: String, ownerUserId: String) -> Unit,
    // Defaults to a no-op so unit tests that don't assert drain timing need no change; production DI
    // MUST wire it to `PendingOperationQueue.signalEnqueued` (see [signalEnqueued]).
    private val signalEnqueued: () -> Unit = {},
    private val currentUserId: suspend () -> String?,
    private val deviceInfo: DeviceInfoProvider,
    private val processId: String = Uuid.random().toString(),
    private val clock: Clock = Clock.System,
    private val timeZone: () -> TimeZone = { TimeZone.currentSystemDefault() },
) {
    /**
     * Holds each call for its whole read-then-write. The players report play, pause and ticks from separate
     * coroutines that suspend on every DAO call, so a quick pause-and-resume otherwise interleaves: the pause's
     * delete took the span the resume had just opened, and everything played after it went unrecorded. Waiters
     * run in the order they reach the lock, so a caller must reach it in event order: see
     * [PlaybackProgressReporter]'s `record`.
     */
    private val spanLock = Mutex()

    /** Failed finalizes per span id in this process, for the spans not yet set aside. Guarded by [spanLock]. */
    private val finalizeFailures = mutableMapOf<String, Int>()

    /** Ids of the spans set aside after [MAX_FINALIZE_ATTEMPTS] failed finalizes. Guarded by [spanLock]. */
    private val setAside = mutableSetOf<String>()

    /** The open span: the one row in `tentative_span` that has not been set aside. */
    private suspend fun currentSpan(): TentativeSpanEntity? = tentativeSpanDao.getAll().firstOrNull { it.id !in setAside }

    /**
     * Opens a new tentative span at [positionMs] with [playbackSpeed], stamped with this
     * process's [processId].
     *
     * **Never destroys a span already open.** A span left open is finalized FIRST, at the
     * position and time of its last heartbeat (the last moment it is known to have been playing),
     * then the new span is opened. It may come from a DIFFERENT process launch (a crash / OS-kill
     * left it behind and startup [recoverOrphan] has not run yet — e.g. the user hit play while
     * catch-up was still paging), or from this one, when a pause never reached the recorder (a
     * book switched without one, a player that reports playing again after a stall). The table's
     * key is the span id, so an upsert of the new span beside it would add a second row, leaving
     * two spans open at once. Only if the finalize could not clear the row (it rolled
     * back) do we bail without opening a new span, so the old span survives as the recovery
     * breadcrumb — until it has failed [MAX_FINALIZE_ATTEMPTS] times, when it is set aside and the
     * new span opens beside it.
     */
    suspend fun onPlay(
        bookId: String,
        positionMs: Long,
        playbackSpeed: Float,
    ) {
        spanLock.withLock { openSpan(bookId, positionMs, playbackSpeed) }
    }

    /** [onPlay]'s body, for the calls that already hold [spanLock]. Returns whether a span was opened. */
    private suspend fun openSpan(
        bookId: String,
        positionMs: Long,
        playbackSpeed: Float,
    ): Boolean {
        val userId =
            currentUserId() ?: run {
                logger.info { "[ListeningEventRecorder] onPlay skipped — no authenticated user" }
                return false
            }
        val existing = currentSpan()
        if (existing != null) {
            val origin = if (existing.processId == processId) "this process" else "a prior process"
            logger.info {
                "[ListeningEventRecorder] onPlay found a span still open from $origin " +
                    "(book=${existing.bookId}) — finalizing it before opening the new span"
            }
            val cleared =
                finalizeThenCheckCleared(
                    tentative = existing,
                    endPositionMs = existing.currentPositionMs,
                    endedAt = existing.lastHeartbeatAt,
                    caller = "onPlay",
                )
            if (!cleared) return false
        }
        val nowMs = clock.now().toEpochMilliseconds()
        tentativeSpanDao.upsert(
            TentativeSpanEntity(
                id = Uuid.random().toString(),
                userId = userId,
                bookId = bookId,
                startPositionMs = positionMs,
                currentPositionMs = positionMs,
                startedAt = nowMs,
                lastHeartbeatAt = nowMs,
                playbackSpeed = playbackSpeed,
                tz = timeZone().id,
                deviceLabel = deviceInfo.current().let { it.deviceName ?: it.deviceModel },
                processId = processId,
            ),
        )
        return true
    }

    /**
     * A heartbeat while audio is playing [bookId] at [positionMs] and [playbackSpeed]: advances
     * [TentativeSpanEntity.currentPositionMs] and [TentativeSpanEntity.lastHeartbeatAt] to the
     * current time. Call it only while audio is playing.
     *
     * With no span open, the play that should have opened one was missed, so the span opens here,
     * at this heartbeat's position and time, and the log says so. With another book's span open the
     * heartbeat is ignored: a position in one book never extends a listen to another.
     */
    suspend fun onPeriodicTick(
        bookId: String,
        positionMs: Long,
        playbackSpeed: Float,
    ) {
        spanLock.withLock {
            val existing = currentSpan()
            if (existing == null) {
                if (openSpan(bookId, positionMs, playbackSpeed)) {
                    logger.info {
                        "[ListeningEventRecorder] Opened span late: book=$bookId at position=$positionMs — " +
                            "audio was playing with no span open, so its play was never heard"
                    }
                }
                return
            }
            if (existing.bookId != bookId) {
                logger.warn {
                    "[ListeningEventRecorder] Tick for book=$bookId while book=${existing.bookId}'s span is open — ignored"
                }
                return
            }
            val nowMs = clock.now().toEpochMilliseconds()
            tentativeSpanDao.upsert(
                existing.copy(
                    currentPositionMs = positionMs,
                    lastHeartbeatAt = nowMs,
                ),
            )
        }
    }

    /**
     * Closes the current span at [positionMs] and finalizes it. No-op if no span is open.
     *
     * [positionMs] is a place in [bookId]. When the open span is another book's, that position
     * means nothing for it: the span is still closed now (its book stopped when this one took
     * over), but at its own last known position, never at a place in a different book.
     */
    suspend fun onPause(
        bookId: String,
        positionMs: Long,
    ) {
        spanLock.withLock {
            val existing = currentSpan() ?: return
            val endPositionMs =
                if (existing.bookId == bookId) {
                    positionMs
                } else {
                    logger.warn {
                        "[ListeningEventRecorder] Pause for book=$bookId while book=${existing.bookId}'s span is open " +
                            "— closing it at its own last known position"
                    }
                    existing.currentPositionMs
                }
            finalizeSpan(existing, endPositionMs = endPositionMs, endedAt = clock.now().toEpochMilliseconds())
        }
    }

    /**
     * Finalizes the current span at [positionMs] using the OLD [playbackSpeed], then opens a
     * new span at [positionMs] with [newSpeed]. No-op if no span is open.
     *
     * The finalized span's wire payload carries the old speed — the span covers audio played
     * at that speed from its [TentativeSpanEntity.startPositionMs] to [positionMs].
     *
     * If the finalize's enqueue fails, the tentative row survives as the [recoverOrphan] breadcrumb
     * (see [finalizeSpan]) — this does NOT open a new span on top of it (that would destroy
     * the breadcrumb and silently lose the whole original span). The player's next `onPlay` /
     * `onPeriodicTick` re-establishes recording, and [recoverOrphan] re-promotes the breadcrumb on
     * the next launch.
     */
    suspend fun onSpeedChange(
        positionMs: Long,
        newSpeed: Float,
    ) {
        spanLock.withLock {
            val existing = currentSpan() ?: return
            val nowMs = clock.now().toEpochMilliseconds()
            val cleared =
                finalizeThenCheckCleared(
                    tentative = existing,
                    endPositionMs = positionMs,
                    endedAt = nowMs,
                    caller = "onSpeedChange",
                )
            if (!cleared) return
            openSpan(existing.bookId, positionMs, newSpeed)
        }
    }

    /**
     * Splits the span on a seek: finalizes the current span at [positionBeforeSeek] (the
     * listener's last known position before the jump), then opens a fresh span at
     * [positionAfterSeek]. This is what keeps a jumped-over range out of the finalized span's
     * content coverage — without it a seek would silently inflate one span to span the jump
     * (e.g. 0:12:00 → 5:02:00 after a seek to 5:00:00), fabricating coverage that corrupts the
     * books-finished / coverage-derived stats. No-op if no span is open.
     *
     * A before-position at or behind the span's own start (a backward seek reported with a stale
     * before-position, or the auto-rewind right after a play) closes a span that heard nothing, which
     * [finalizeSpan] drops.
     *
     * If the finalize's enqueue fails, the tentative row survives as the [recoverOrphan] breadcrumb
     * (see [finalizeSpan]) — this does NOT open a new span on top of it (that would destroy
     * the breadcrumb and silently lose the whole original span). The player's next `onPlay` /
     * `onPeriodicTick` re-establishes recording, and [recoverOrphan] re-promotes the breadcrumb on
     * the next launch.
     */
    suspend fun onSeek(
        positionBeforeSeek: Long,
        positionAfterSeek: Long,
    ) {
        spanLock.withLock {
            val existing = currentSpan() ?: return
            val nowMs = clock.now().toEpochMilliseconds()
            val cleared =
                finalizeThenCheckCleared(
                    tentative = existing,
                    endPositionMs = positionBeforeSeek,
                    endedAt = nowMs,
                    caller = "onSeek",
                )
            if (!cleared) return
            openSpan(existing.bookId, positionAfterSeek, existing.playbackSpeed)
        }
    }

    /**
     * Finalizes the current span for the old book (using [TentativeSpanEntity.currentPositionMs]
     * as the end position), then opens a new span for [newBookId] at [newStartPositionMs].
     * No-op if no span is open.
     *
     * If the finalize's enqueue fails, the tentative row survives as the [recoverOrphan] breadcrumb
     * (see [finalizeSpan]) — this does NOT open a new span on top of it (that would destroy
     * the breadcrumb and silently lose the whole original span). The player's next `onPlay` /
     * `onPeriodicTick` re-establishes recording, and [recoverOrphan] re-promotes the breadcrumb on
     * the next launch.
     */
    suspend fun onMediaItemTransition(
        newBookId: String,
        newStartPositionMs: Long,
    ) {
        spanLock.withLock {
            val existing = currentSpan() ?: return
            val nowMs = clock.now().toEpochMilliseconds()
            val cleared =
                finalizeThenCheckCleared(
                    tentative = existing,
                    endPositionMs = existing.currentPositionMs,
                    endedAt = nowMs,
                    caller = "onMediaItemTransition",
                )
            if (!cleared) return
            openSpan(newBookId, newStartPositionMs, existing.playbackSpeed)
        }
    }

    /**
     * Detects every orphan [TentativeSpanEntity] on app start — the span left open, and any set
     * aside — and finalizes each using [TentativeSpanEntity.lastHeartbeatAt] as the end timestamp.
     * Loss is bounded to one heartbeat interval (30 s in normal operation). No-op if no orphan exists.
     *
     * **Never finalizes the current process's live span.** A span whose [TentativeSpanEntity.processId]
     * equals this process's [processId] belongs to the running session — finalizing it would truncate
     * a live listen at its opening heartbeat and delete the tentative row, so the rest of the session
     * would go unrecorded. Only a span from a DIFFERENT (prior) process launch is a real orphan.
     *
     * Call once at startup after the database is open and before playback resumes.
     */
    suspend fun recoverOrphan() {
        spanLock.withLock {
            val (live, orphans) = tentativeSpanDao.getAll().partition { it.processId == processId }
            live.forEach { span ->
                logger.debug {
                    "[ListeningEventRecorder] Skipping recovery — span for book=${span.bookId} belongs to " +
                        "the current process (it is live, not an orphan)"
                }
            }
            orphans.forEach { orphan ->
                logger.info {
                    "[ListeningEventRecorder] Recovering orphan span for book=${orphan.bookId}, " +
                        "startPos=${orphan.startPositionMs}, lastHeartbeat=${orphan.lastHeartbeatAt}"
                }
                finalizeSpan(orphan, endPositionMs = orphan.currentPositionMs, endedAt = orphan.lastHeartbeatAt)
            }
        }
    }

    // ── Private finalization ────────────────────────────────────────────────────

    /**
     * Finalizes [tentative], then reports whether the open-span slot actually cleared.
     *
     * [finalizeSpan] deliberately leaves the row in place when its enqueue fails — that
     * survivor is the ONLY breadcrumb [recoverOrphan] can re-promote from. Every
     * finalize-then-reopen flow ([onPlay], [onSpeedChange], [onSeek], [onMediaItemTransition])
     * must honor it — opening a new span
     * on top of a survivor would silently destroy the breadcrumb and lose the whole original span,
     * not just the failed write. Returns `false` (and logs a warning tagged with [caller]) when the
     * row survived as the open span, so the call site can bail without opening a new span. A span
     * set aside by this failure no longer holds the slot, so the new span opens beside it.
     */
    private suspend fun finalizeThenCheckCleared(
        tentative: TentativeSpanEntity,
        endPositionMs: Long,
        endedAt: Long,
        caller: String,
    ): Boolean {
        finalizeSpan(tentative, endPositionMs = endPositionMs, endedAt = endedAt)
        if (currentSpan() != null) {
            logger.warn {
                "[ListeningEventRecorder] Finalize did not clear the tentative span during $caller " +
                    "(book=${tentative.bookId}) — leaving it intact for recovery, new span not opened"
            }
            return false
        }
        return true
    }

    /**
     * Drops [tentative] if it heard nothing, otherwise atomically writes a [ListeningEventEntity] +
     * enqueues a pending op + deletes the tentative span.
     *
     * **Heard-nothing drop.** A span that took no wall-clock time (`endedAt == startedAt`) or covered
     * no audio (`endPositionMs <= startPositionMs`) is not a listen: the tentative is deleted and no
     * event is written (see the class KDoc for where such spans come from).
     *
     * **Atomicity.** The three writes (upsert event, enqueue op, delete tentative) run inside a
     * single [transactionRunner] transaction — they are all same-database Room DAO writes, so
     * they commit or roll back as a unit. A failed enqueue rolls back BOTH the event upsert and
     * the tentative delete, so the tentative span survives as the ONLY breadcrumb [recoverOrphan]
     * can re-promote from on the next launch. Without this, a swallowed enqueue failure would
     * delete that breadcrumb and strand the event at `revision = 0` locally forever. The event
     * write stays insert-if-absent so a re-promoted orphan never clobbers an already-synced or
     * tombstoned row. Serialization runs before the transaction to keep the SQLite write lock short.
     *
     * **Bounded retries.** Each failure is counted against the span; see [recordFinalizeFailure].
     */
    private suspend fun finalizeSpan(
        tentative: TentativeSpanEntity,
        endPositionMs: Long,
        endedAt: Long,
    ) {
        if (endedAt == tentative.startedAt || endPositionMs <= tentative.startPositionMs) {
            logger.debug { "[ListeningEventRecorder] Dropping a span that heard nothing for book=${tentative.bookId}" }
            tentativeSpanDao.deleteById(tentative.id)
            forget(tentative.id)
            return
        }

        val entity =
            ListeningEventEntity(
                id = tentative.id,
                userId = tentative.userId,
                bookId = tentative.bookId,
                startPositionMs = tentative.startPositionMs,
                endPositionMs = endPositionMs,
                startedAt = tentative.startedAt,
                endedAt = endedAt,
                playbackSpeed = tentative.playbackSpeed,
                tz = tentative.tz,
                deviceLabel = tentative.deviceLabel,
                revision = 0L,
                deletedAt = null,
            )

        val payload =
            contractJson.encodeToString(
                RecordListeningEventRequest.serializer(),
                RecordListeningEventRequest(
                    id = entity.id,
                    bookId = entity.bookId,
                    startPositionMs = entity.startPositionMs,
                    endPositionMs = entity.endPositionMs,
                    startedAt = entity.startedAt,
                    endedAt = entity.endedAt,
                    playbackSpeed = entity.playbackSpeed,
                    tz = entity.tz,
                    deviceLabel = entity.deviceLabel,
                ),
            )

        try {
            transactionRunner.atomically {
                // Insert-if-absent. A re-promoted orphan whose event row already exists must
                // NOT be clobbered: the row may have synced (revision advanced) and even been
                // tombstoned server-side since. Writing a fresh revision=0/deletedAt=null row
                // would resurrect/regress it.
                if (listeningEventDao.getById(entity.id) == null) {
                    listeningEventDao.upsert(entity)
                }
                enqueue(entity.id, payload, tentative.userId)
                tentativeSpanDao.deleteById(tentative.id)
            }
            forget(tentative.id)
            // Signal the drain only AFTER the transaction commits — a pre-commit signal (the
            // enqueue's default) wakes the drain against WAL state that can't yet see the new row,
            // stranding the op until an unrelated later trigger. Never runs on rollback (below).
            signalEnqueued()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            // The transaction rolled back: no event row, and the tentative span survives.
            // recoverOrphan() on the next launch re-promotes it, so the span is never lost.
            recordFinalizeFailure(tentative, endPositionMs, e)
        }
    }

    /**
     * Counts a failed finalize of [tentative]. Below [MAX_FINALIZE_ATTEMPTS] the span stays the open
     * one, so the next play retries it. At the bound it is set aside: it keeps its row for
     * [recoverOrphan], and everything needed to rebuild it by hand is in the ERROR line.
     */
    private fun recordFinalizeFailure(
        tentative: TentativeSpanEntity,
        endPositionMs: Long,
        cause: Exception,
    ) {
        val failures = (finalizeFailures[tentative.id] ?: 0) + 1
        if (failures < MAX_FINALIZE_ATTEMPTS) {
            finalizeFailures[tentative.id] = failures
            logger.warn(cause) {
                "[ListeningEventRecorder] Failed to finalize listening event ${tentative.id} " +
                    "(attempt $failures of $MAX_FINALIZE_ATTEMPTS) — tentative span preserved for recovery on next launch"
            }
            return
        }
        finalizeFailures.remove(tentative.id)
        setAside += tentative.id
        logger.error(cause) {
            "[ListeningEventRecorder] Finalize of listening event ${tentative.id} failed $failures times — set aside " +
                "so recording can go on; it stays in tentative_span for the next launch to recover " +
                "(book=${tentative.bookId}, position ${tentative.startPositionMs}→$endPositionMs, " +
                "startedAt=${tentative.startedAt}, lastHeartbeatAt=${tentative.lastHeartbeatAt})"
        }
    }

    /** Clears what this process counted against a span that has left the table. */
    private fun forget(spanId: String) {
        finalizeFailures.remove(spanId)
        setAside.remove(spanId)
    }
}
