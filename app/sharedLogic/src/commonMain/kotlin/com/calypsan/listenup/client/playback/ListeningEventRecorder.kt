package com.calypsan.listenup.client.playback

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.RecordListeningEventRequest
import com.calypsan.listenup.client.device.DeviceInfoProvider
import com.calypsan.listenup.client.data.local.db.ListeningEventDao
import com.calypsan.listenup.client.data.local.db.ListeningEventEntity
import com.calypsan.listenup.client.data.local.db.TentativeSpanDao
import com.calypsan.listenup.client.data.local.db.TentativeSpanEntity
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.datetime.TimeZone

private val logger = KotlinLogging.logger {}

/**
 * Drives the per-span recording state machine: open a [TentativeSpanEntity] on play,
 * extend it on each heartbeat, finalize it into a [ListeningEventEntity] (plus a pending
 * op) on pause / book-end / sleep-timer / speed-change / seek / cross-book transition.
 *
 * Only one span can be open at a time — a user listens to one book at a time. The
 * single-row `tentative_span` table enforces this by design.
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
 * **Zero-duration / zero-position spans.** A span where `endedAt == startedAt` (play +
 * pause within the same millisecond) is silently dropped. Stats pollution from accidental
 * double-taps is avoided without surfacing noise to the user.
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
 *   stamped with it, and [recoverOrphan] / [onPlay] compare against it to tell the current
 *   process's live span apart from an orphan left by a prior process. Injected for deterministic
 *   testing.
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
     * Opens a new tentative span at [positionMs] with [playbackSpeed], stamped with this
     * process's [processId].
     *
     * **Never destroys a prior process's orphan.** If a span from a DIFFERENT process launch is
     * still open (a crash / OS-kill left it behind and startup [recoverOrphan] has not run yet —
     * e.g. the user hit play while catch-up was still paging), it is recovered (finalized) FIRST,
     * then the new span is opened. Only if that recovery could not clear the row (its finalize
     * rolled back) do we bail without opening a new span, so the orphan survives as the recovery
     * breadcrumb rather than being clobbered by the single-row table's replace semantics.
     *
     * A span from the SAME process is unexpected on a raw [onPlay] ([onSpeedChange] / [onSeek] /
     * [onMediaItemTransition] each finalize first), so replacing it is safe and keeps the
     * single-row invariant.
     */
    suspend fun onPlay(
        bookId: String,
        positionMs: Long,
        playbackSpeed: Float,
    ) {
        val userId =
            currentUserId() ?: run {
                logger.debug { "[ListeningEventRecorder] onPlay skipped — no authenticated user" }
                return
            }
        val existing = tentativeSpanDao.get()
        if (existing != null && existing.processId != processId) {
            logger.info {
                "[ListeningEventRecorder] onPlay found an orphan span from a prior process " +
                    "(book=${existing.bookId}) — recovering it before opening the new span"
            }
            finalizeCurrentSpan(endPositionMs = existing.currentPositionMs, endedAt = existing.lastHeartbeatAt)
            // If the orphan's finalize rolled back (e.g. a failed enqueue), the row survives.
            // Never overwrite it — bail so startup recoverOrphan re-promotes it next launch.
            if (tentativeSpanDao.get() != null) {
                logger.warn {
                    "[ListeningEventRecorder] Prior-process orphan did not clear during onPlay — " +
                        "leaving it intact for recovery, new span not opened"
                }
                return
            }
        }
        val nowMs = clock.now().toEpochMilliseconds()
        tentativeSpanDao.upsertSingleton(
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
    }

    /**
     * Advances [TentativeSpanEntity.currentPositionMs] and [TentativeSpanEntity.lastHeartbeatAt]
     * to the current time. No-op if no span is open.
     */
    suspend fun onPeriodicTick(positionMs: Long) {
        val existing = tentativeSpanDao.get() ?: return
        val nowMs = clock.now().toEpochMilliseconds()
        tentativeSpanDao.upsertSingleton(
            existing.copy(
                currentPositionMs = positionMs,
                lastHeartbeatAt = nowMs,
            ),
        )
    }

    /**
     * Closes the current span at [positionMs] and finalizes it. No-op if no span is open.
     */
    suspend fun onPause(positionMs: Long) {
        val nowMs = clock.now().toEpochMilliseconds()
        finalizeCurrentSpan(endPositionMs = positionMs, endedAt = nowMs)
    }

    /**
     * Finalizes the current span at [positionMs] using the OLD [playbackSpeed], then opens a
     * new span at [positionMs] with [newSpeed]. No-op if no span is open.
     *
     * The finalized span's wire payload carries the old speed — the span covers audio played
     * at that speed from its [TentativeSpanEntity.startPositionMs] to [positionMs].
     *
     * If the finalize's enqueue fails, the tentative row survives as the [recoverOrphan] breadcrumb
     * (see [finalizeCurrentSpan]) — this does NOT open a new span on top of it (that would destroy
     * the breadcrumb and silently lose the whole original span). The player's next `onPlay` /
     * `onPeriodicTick` re-establishes recording, and [recoverOrphan] re-promotes the breadcrumb on
     * the next launch.
     */
    suspend fun onSpeedChange(
        positionMs: Long,
        newSpeed: Float,
    ) {
        val existing = tentativeSpanDao.get() ?: return
        val nowMs = clock.now().toEpochMilliseconds()
        val cleared =
            finalizeThenCheckCleared(
                endPositionMs = positionMs,
                endedAt = nowMs,
                bookId = existing.bookId,
                caller = "onSpeedChange",
            )
        if (!cleared) return
        onPlay(existing.bookId, positionMs, newSpeed)
    }

    /**
     * Splits the span on a seek: finalizes the current span at [positionBeforeSeek] (the
     * listener's last known position before the jump), then opens a fresh span at
     * [positionAfterSeek]. This is what keeps a jumped-over range out of the finalized span's
     * content coverage — without it a seek would silently inflate one span to span the jump
     * (e.g. 0:12:00 → 5:02:00 after a seek to 5:00:00), fabricating coverage that corrupts the
     * books-finished / coverage-derived stats. No-op if no span is open.
     *
     * The pre-seek end is clamped to the span's own start so a backward seek reported with a
     * stale before-position can never produce an inverted span (`endPositionMs < startPositionMs`),
     * which the server has no defined handling for.
     *
     * If the finalize's enqueue fails, the tentative row survives as the [recoverOrphan] breadcrumb
     * (see [finalizeCurrentSpan]) — this does NOT open a new span on top of it (that would destroy
     * the breadcrumb and silently lose the whole original span). The player's next `onPlay` /
     * `onPeriodicTick` re-establishes recording, and [recoverOrphan] re-promotes the breadcrumb on
     * the next launch.
     */
    suspend fun onSeek(
        positionBeforeSeek: Long,
        positionAfterSeek: Long,
    ) {
        val existing = tentativeSpanDao.get() ?: return
        val nowMs = clock.now().toEpochMilliseconds()
        val endPositionMs = maxOf(positionBeforeSeek, existing.startPositionMs)
        val cleared =
            finalizeThenCheckCleared(
                endPositionMs = endPositionMs,
                endedAt = nowMs,
                bookId = existing.bookId,
                caller = "onSeek",
            )
        if (!cleared) return
        onPlay(existing.bookId, positionAfterSeek, existing.playbackSpeed)
    }

    /**
     * Finalizes the current span for the old book (using [TentativeSpanEntity.currentPositionMs]
     * as the end position), then opens a new span for [newBookId] at [newStartPositionMs].
     * No-op if no span is open.
     *
     * If the finalize's enqueue fails, the tentative row survives as the [recoverOrphan] breadcrumb
     * (see [finalizeCurrentSpan]) — this does NOT open a new span on top of it (that would destroy
     * the breadcrumb and silently lose the whole original span). The player's next `onPlay` /
     * `onPeriodicTick` re-establishes recording, and [recoverOrphan] re-promotes the breadcrumb on
     * the next launch.
     */
    suspend fun onMediaItemTransition(
        newBookId: String,
        newStartPositionMs: Long,
    ) {
        val existing = tentativeSpanDao.get() ?: return
        val nowMs = clock.now().toEpochMilliseconds()
        val cleared =
            finalizeThenCheckCleared(
                endPositionMs = existing.currentPositionMs,
                endedAt = nowMs,
                bookId = existing.bookId,
                caller = "onMediaItemTransition",
            )
        if (!cleared) return
        onPlay(newBookId, newStartPositionMs, existing.playbackSpeed)
    }

    /**
     * Detects an orphan [TentativeSpanEntity] on app start and finalizes it using
     * [TentativeSpanEntity.lastHeartbeatAt] as the end timestamp. Loss is bounded to one
     * heartbeat interval (30 s in normal operation). No-op if no orphan exists.
     *
     * **Never finalizes the current process's live span.** A span whose [TentativeSpanEntity.processId]
     * equals this process's [processId] belongs to the running session — finalizing it would truncate
     * a live listen at its opening heartbeat and delete the tentative row, so the rest of the session
     * would go unrecorded. Only a span from a DIFFERENT (prior) process launch is a real orphan.
     *
     * Call once at startup after the database is open and before playback resumes.
     */
    suspend fun recoverOrphan() {
        val orphan = tentativeSpanDao.get() ?: return
        if (orphan.processId == processId) {
            logger.debug {
                "[ListeningEventRecorder] Skipping recovery — span for book=${orphan.bookId} belongs to " +
                    "the current process (it is live, not an orphan)"
            }
            return
        }
        logger.info {
            "[ListeningEventRecorder] Recovering orphan span for book=${orphan.bookId}, " +
                "startPos=${orphan.startPositionMs}, lastHeartbeat=${orphan.lastHeartbeatAt}"
        }
        finalizeCurrentSpan(endPositionMs = orphan.currentPositionMs, endedAt = orphan.lastHeartbeatAt)
    }

    // ── Private finalization ────────────────────────────────────────────────────

    /**
     * Finalizes the current span, then reports whether the tentative row actually cleared.
     *
     * [finalizeCurrentSpan] deliberately leaves the row in place when its enqueue fails — that
     * survivor is the ONLY breadcrumb [recoverOrphan] can re-promote from. [onPlay]'s cross-process
     * branch already honors that breadcrumb; every SAME-process finalize-then-reopen flow
     * ([onSpeedChange], [onSeek], [onMediaItemTransition]) must honor it too — opening a new span
     * on top of a survivor would silently destroy the breadcrumb and lose the whole original span,
     * not just the failed write. Returns `false` (and logs a warning tagged with [caller]) when the
     * row survived, so the call site can bail without opening a new span.
     */
    private suspend fun finalizeThenCheckCleared(
        endPositionMs: Long,
        endedAt: Long,
        bookId: String,
        caller: String,
    ): Boolean {
        finalizeCurrentSpan(endPositionMs = endPositionMs, endedAt = endedAt)
        if (tentativeSpanDao.get() != null) {
            logger.warn {
                "[ListeningEventRecorder] Finalize did not clear the tentative span during $caller " +
                    "(book=$bookId) — leaving it intact for recovery, new span not opened"
            }
            return false
        }
        return true
    }

    /**
     * Reads the current [TentativeSpanEntity], applies zero-duration / zero-position drop
     * rules, then atomically writes a [ListeningEventEntity] + enqueues a pending op + deletes
     * the tentative span.
     *
     * **Zero-duration drop.** If `endedAt == tentative.startedAt` the span covers zero wall-clock
     * time (play+pause within the same millisecond). The tentative is deleted and no event is
     * written — avoids polluting the events table with accidental double-taps.
     *
     * **Atomicity.** The three writes (upsert event, enqueue op, delete tentative) run inside a
     * single [transactionRunner] transaction — they are all same-database Room DAO writes, so
     * they commit or roll back as a unit. A failed enqueue rolls back BOTH the event upsert and
     * the tentative delete, so the tentative span survives as the ONLY breadcrumb [recoverOrphan]
     * can re-promote from on the next launch. Without this, a swallowed enqueue failure would
     * delete that breadcrumb and strand the event at `revision = 0` locally forever. The event
     * write stays insert-if-absent so a re-promoted orphan never clobbers an already-synced or
     * tombstoned row. Serialization runs before the transaction to keep the SQLite write lock short.
     */
    private suspend fun finalizeCurrentSpan(
        endPositionMs: Long,
        endedAt: Long,
    ) {
        val tentative = tentativeSpanDao.get() ?: return

        // Drop zero-duration spans (play+pause within the same millisecond).
        if (endedAt == tentative.startedAt) {
            logger.debug { "[ListeningEventRecorder] Dropping zero-duration span for book=${tentative.bookId}" }
            tentativeSpanDao.delete()
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
                tentativeSpanDao.delete()
            }
            // Signal the drain only AFTER the transaction commits — a pre-commit signal (the
            // enqueue's default) wakes the drain against WAL state that can't yet see the new row,
            // stranding the op until an unrelated later trigger. Never runs on rollback (below).
            signalEnqueued()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            // The transaction rolled back: no event row, and the tentative span survives.
            // recoverOrphan() on the next launch re-promotes it, so the span is never lost.
            logger.warn(e) {
                "[ListeningEventRecorder] Failed to finalize listening event ${entity.id} — " +
                    "tentative span preserved for recovery on next launch"
            }
        }
    }
}
