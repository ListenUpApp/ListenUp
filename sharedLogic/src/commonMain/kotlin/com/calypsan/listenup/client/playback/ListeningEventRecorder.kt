@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.calypsan.listenup.client.playback

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.RecordListeningEventRequest
import com.calypsan.listenup.client.device.DeviceInfoProvider
import com.calypsan.listenup.client.data.local.db.ListeningEventDao
import com.calypsan.listenup.client.data.local.db.ListeningEventEntity
import com.calypsan.listenup.client.data.local.db.TentativeSpanDao
import com.calypsan.listenup.client.data.local.db.TentativeSpanEntity
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
 * directly, the recorder accepts a suspend lambda `enqueue` that matches the queue's
 * [com.calypsan.listenup.client.data.sync.PendingOperationQueue.enqueue] signature. This
 * keeps commonMain free of a concrete class dependency and makes the class trivially
 * testable: tests pass a capturing lambda; production Koin wiring passes
 * `pendingQueue::enqueue`.
 *
 * @property listeningEventDao DAO for the `listening_events` table.
 * @property tentativeSpanDao DAO for the single-row `tentative_span` table.
 * @property enqueue Suspend function that persists a pending op; signature matches
 *   [com.calypsan.listenup.client.data.sync.PendingOperationQueue.enqueue].
 * @property currentUserId Returns the signed-in user's ID, or null if unauthenticated.
 *   A null result causes all write operations to no-op silently — no pending write without
 *   an owner.
 * @property deviceInfo Single source of the running device's identity; the persisted
 *   `device_label` is derived from it (user-facing name preferred, else hardware model).
 *   A null derived value is acceptable (the column is advisory).
 * @property clock Injected for deterministic testing. Defaults to [Clock.System].
 * @property timeZone Injected for deterministic testing. Defaults to
 *   [TimeZone.currentSystemDefault].
 */
class ListeningEventRecorder(
    private val listeningEventDao: ListeningEventDao,
    private val tentativeSpanDao: TentativeSpanDao,
    private val enqueue: suspend (
        domainName: String,
        entityId: String,
        opType: String,
        payload: String,
        ownerUserId: String,
    ) -> Unit,
    private val currentUserId: suspend () -> String?,
    private val deviceInfo: DeviceInfoProvider,
    private val clock: Clock = Clock.System,
    private val timeZone: () -> TimeZone = { TimeZone.currentSystemDefault() },
) {
    /**
     * Opens a new tentative span at [positionMs] with [playbackSpeed]. If a span is already
     * open it is replaced — [onSpeedChange] / [onSeek] / [onMediaItemTransition] each
     * finalize the existing span before calling [onPlay], so a raw [onPlay] with an open span
     * means unexpected state; replacing is safe and keeps the table's single-row invariant.
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
     */
    suspend fun onSpeedChange(
        positionMs: Long,
        newSpeed: Float,
    ) {
        val existing = tentativeSpanDao.get() ?: return
        val nowMs = clock.now().toEpochMilliseconds()
        finalizeCurrentSpan(endPositionMs = positionMs, endedAt = nowMs)
        onPlay(existing.bookId, positionMs, newSpeed)
    }

    /**
     * Finalizes the current span at [positionBeforeSeek] (the listener's last known position
     * before the jump), then opens a new span at [positionAfterSeek]. No-op if no span is open.
     */
    suspend fun onSeek(
        positionBeforeSeek: Long,
        positionAfterSeek: Long,
    ) {
        val existing = tentativeSpanDao.get() ?: return
        val nowMs = clock.now().toEpochMilliseconds()
        finalizeCurrentSpan(endPositionMs = positionBeforeSeek, endedAt = nowMs)
        onPlay(existing.bookId, positionAfterSeek, existing.playbackSpeed)
    }

    /**
     * Finalizes the current span for the old book (using [TentativeSpanEntity.currentPositionMs]
     * as the end position), then opens a new span for [newBookId] at [newStartPositionMs].
     * No-op if no span is open.
     */
    suspend fun onMediaItemTransition(
        newBookId: String,
        newStartPositionMs: Long,
    ) {
        val existing = tentativeSpanDao.get() ?: return
        val nowMs = clock.now().toEpochMilliseconds()
        finalizeCurrentSpan(endPositionMs = existing.currentPositionMs, endedAt = nowMs)
        onPlay(newBookId, newStartPositionMs, existing.playbackSpeed)
    }

    /**
     * Detects an orphan [TentativeSpanEntity] on app start and finalizes it using
     * [TentativeSpanEntity.lastHeartbeatAt] as the end timestamp. Loss is bounded to one
     * heartbeat interval (30 s in normal operation). No-op if no orphan exists.
     *
     * Call once at startup after the database is open and before playback resumes.
     */
    suspend fun recoverOrphan() {
        val orphan = tentativeSpanDao.get() ?: return
        logger.info {
            "[ListeningEventRecorder] Recovering orphan span for book=${orphan.bookId}, " +
                "startPos=${orphan.startPositionMs}, lastHeartbeat=${orphan.lastHeartbeatAt}"
        }
        finalizeCurrentSpan(endPositionMs = orphan.currentPositionMs, endedAt = orphan.lastHeartbeatAt)
    }

    // ── Private finalization ────────────────────────────────────────────────────

    /**
     * Reads the current [TentativeSpanEntity], applies zero-duration / zero-position drop
     * rules, then atomically writes a [ListeningEventEntity] + enqueues a pending op + deletes
     * the tentative span.
     *
     * **Zero-duration drop.** If `endedAt == tentative.startedAt` the span covers zero wall-clock
     * time (play+pause within the same millisecond). The tentative is deleted and no event is
     * written — avoids polluting the events table with accidental double-taps.
     *
     * Note on atomicity: the three writes (upsert event, enqueue op, delete tentative) are NOT
     * wrapped in a Room transaction. An orphan recovery run on the next launch handles any of the
     * three intermediate states safely:
     * - If the event upsert succeeded but enqueue / delete failed, the orphan is re-promoted on
     *   the next launch. The event write is insert-if-absent (see [finalizeCurrentSpan]), so a
     *   re-promotion never clobbers an already-synced/tombstoned row; only the enqueue + delete
     *   are retried.
     * - If all three succeed, there is no orphan row to recover.
     * Full transactionality would require [com.calypsan.listenup.client.data.local.db.TransactionRunner]
     * involvement and cross-DAO scope — the recovery path is the designed safety net.
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
        // Insert-if-absent. A re-promoted orphan whose event row already exists must
        // NOT be clobbered: the row may have synced (revision advanced) and even been
        // tombstoned server-side since. Writing a fresh revision=0/deletedAt=null row
        // would resurrect/regress it. Only write the event the first time; the enqueue
        // + tentative-delete below still run so a stalled recovery completes.
        if (listeningEventDao.getById(entity.id) == null) {
            listeningEventDao.upsert(entity)
        }

        val request =
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
            )

        try {
            enqueue(
                "listening_events",
                entity.id,
                "upsert",
                contractJson.encodeToString(RecordListeningEventRequest.serializer(), request),
                tentative.userId,
            )
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Queue write failure is non-fatal: the event is already in Room. The orphan
            // recovery path on next launch will re-enqueue if needed.
            logger.warn(e) {
                "[ListeningEventRecorder] Failed to enqueue listening event ${entity.id} — " +
                    "event is persisted locally and will be re-enqueued on next launch"
            }
        }

        tentativeSpanDao.delete()
    }
}
