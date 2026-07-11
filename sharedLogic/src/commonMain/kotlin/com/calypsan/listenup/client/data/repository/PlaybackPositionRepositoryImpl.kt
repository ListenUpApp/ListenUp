package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.RecordPositionRequest
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.currentEpochMilliseconds
import com.calypsan.listenup.client.core.suspendRunCatching
import com.calypsan.listenup.client.data.local.db.PlaybackPositionDao
import com.calypsan.listenup.client.data.local.db.PlaybackPositionEntity
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.sync.PendingOperationQueue
import com.calypsan.listenup.client.data.sync.domains.OpKind
import com.calypsan.listenup.client.data.sync.domains.OutboxChannels
import com.calypsan.listenup.client.domain.model.PlaybackPosition
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.LastPlayedInfo
import com.calypsan.listenup.client.domain.repository.PlaybackPositionRepository
import com.calypsan.listenup.client.domain.repository.PlaybackUpdate
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.max
import kotlin.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Implementation of PlaybackPositionRepository using Room.
 *
 * Wraps PlaybackPositionDao and converts entities to domain models.
 * Position operations are instant and local-first.
 *
 * After each position-moving write, enqueues a [RecordPositionRequest] onto the
 * pending-operation queue so the sync engine pushes the updated position to the server.
 * Enqueues with coalescing on: rapid successive writes for one book replace the
 * still-queued op for that `(domain, entityId, opType)` slot instead of piling up, so
 * only the latest position is ever pushed — no flooding.
 *
 * The [savePlaybackState] entry point owns per-book Mutex serialization
 * plus per-call transactional dispatch over the 11-variant [PlaybackUpdate]
 * sealed hierarchy. Every variant handler runs inside [TransactionRunner.atomically]
 * so each fetch-then-save pair is rollback-safe. Concurrent writes for the same
 * book serialize on a per-book Mutex; different books proceed in parallel.
 *
 * @property dao Room DAO for position operations
 * @property transactionRunner Runs each variant handler inside a write transaction
 * @property pendingQueue Outbox queue for server-side position writes
 * @property authSession Source of the current user ID for queue ownership
 */
internal class PlaybackPositionRepositoryImpl(
    private val dao: PlaybackPositionDao,
    private val transactionRunner: TransactionRunner,
    private val pendingQueue: PendingOperationQueue,
    private val authSession: AuthSession,
) : PlaybackPositionRepository {
    // ----- Per-book Mutex map ---------------------------------------------------------------

    private val mutexMapLock = Mutex()
    private val mutexes = mutableMapOf<BookId, Mutex>()

    /**
     * Returns the [Mutex] guarding writes for [bookId]. Creates and stores a new
     * Mutex on first access; subsequent accesses for the same book return the same
     * instance. The map grows monotonically; eviction is a future optimization.
     *
     * Holds [mutexMapLock] only for the duration of [getOrPut] so other books'
     * Mutex creations don't block on per-book write durations.
     */
    private suspend fun mutexFor(bookId: BookId): Mutex = mutexMapLock.withLock { mutexes.getOrPut(bookId) { Mutex() } }

    // ----- Read paths -----------------------------------------------------------------------

    override suspend fun get(bookId: BookId): AppResult<PlaybackPosition?> =
        suspendRunCatching {
            dao.get(bookId)?.toDomain()
        }

    override fun observeAll(): Flow<Map<BookId, PlaybackPosition>> =
        dao.observeAll().map { positions ->
            positions.associate { it.bookId to it.toDomain() }
        }

    override fun observe(bookId: BookId): Flow<PlaybackPosition?> = dao.observe(bookId).map { it?.toDomain() }

    override suspend fun getLastPlayedBook(): AppResult<LastPlayedInfo?> =
        suspendRunCatching {
            val positions = dao.getRecentPositions(1)
            positions.firstOrNull()?.let { position ->
                LastPlayedInfo(
                    bookId = position.bookId,
                    positionMs = position.positionMs,
                    playbackSpeed = position.playbackSpeed,
                )
            }
        }

    // ----- Write paths -----------------------------------------------------------------------

    override suspend fun delete(bookId: BookId): AppResult<Unit> =
        suspendRunCatching {
            dao.delete(bookId)
        }

    override suspend fun markComplete(
        bookId: BookId,
        startedAt: Long?,
        finishedAt: Long?,
    ): AppResult<Unit> = savePlaybackState(bookId, PlaybackUpdate.MarkComplete(startedAt, finishedAt))

    override suspend fun discardProgress(bookId: BookId): AppResult<Unit> =
        savePlaybackState(bookId, PlaybackUpdate.DiscardProgress)

    override suspend fun restartBook(bookId: BookId): AppResult<Unit> =
        savePlaybackState(bookId, PlaybackUpdate.Restart)

    // ----- Canonical entry point ------------------------------------------------------------

    override suspend fun savePlaybackState(
        bookId: BookId,
        update: PlaybackUpdate,
    ): AppResult<Unit> =
        suspendRunCatching {
            mutexFor(bookId).withLock {
                transactionRunner.atomically {
                    handle(bookId, update)
                }
                enqueueIfPositionMoving(bookId, update)
            }
        }

    /**
     * Exhaustive dispatcher over the 11-variant [PlaybackUpdate] hierarchy.
     *
     * Adding a new variant produces a `when` exhaustiveness compile error here —
     * the sealed-hierarchy contract every consumer must satisfy.
     */
    private suspend fun handle(
        bookId: BookId,
        update: PlaybackUpdate,
    ) {
        when (update) {
            is PlaybackUpdate.Position -> handlePosition(bookId, update)
            is PlaybackUpdate.Speed -> handleSpeed(bookId, update)
            is PlaybackUpdate.SpeedReset -> handleSpeedReset(bookId, update)
            is PlaybackUpdate.PlaybackStarted -> handlePlaybackStarted(bookId, update)
            is PlaybackUpdate.PlaybackPaused -> handlePlaybackPaused(bookId, update)
            is PlaybackUpdate.PeriodicUpdate -> handlePeriodicUpdate(bookId, update)
            is PlaybackUpdate.BookFinished -> handleBookFinished(bookId, update)
            is PlaybackUpdate.CrossDeviceSync -> handleCrossDeviceSync(bookId, update)
            is PlaybackUpdate.MarkComplete -> handleMarkComplete(bookId, update)
            PlaybackUpdate.DiscardProgress -> handleDiscardProgress(bookId)
            PlaybackUpdate.Restart -> handleRestart(bookId)
        }
    }

    // ----- Outbox enqueue -------------------------------------------------------------------

    /**
     * Enqueues a [RecordPositionRequest] for the position-moving [PlaybackUpdate] variants.
     *
     * [CrossDeviceSync] is excluded: it is incoming server state, so pushing it back would
     * create an echo loop.
     *
     * No sign-in user means no server to push to — silently skipped.
     *
     * Non-terminal variants carry the row's current `isFinished` onto the wire — a
     * periodic/seek write must never un-finish a finished book on the server. Unfinishing
     * is explicit: only [PlaybackUpdate.DiscardProgress]/[PlaybackUpdate.Restart] unfinish.
     */
    private suspend fun enqueueIfPositionMoving(
        bookId: BookId,
        update: PlaybackUpdate,
    ) {
        val userId = authSession.getUserId() ?: return
        val now = currentEpochMilliseconds()
        // Post-transaction snapshot of the row handle() just wrote. Non-terminal variants
        // carry its isFinished onto the wire — a periodic/seek write must never un-finish a
        // finished book on the server. Unfinishing is explicit: only DiscardProgress/Restart
        // send finished=false by design.
        val entity = dao.get(bookId)
        // The post-write row's current high-water — carried on every request the same
        // protective way `finished` is: the value handle() already computed/preserved,
        // never re-derived here, so the outbox can never enqueue a lower max than the
        // local row holds.
        val maxPositionMs = entity?.maxPositionMs ?: 0
        val request = buildPositionRequest(bookId, update, entity, maxPositionMs, now) ?: return

        try {
            pendingQueue.enqueue(
                channel = OutboxChannels.Positions,
                entityId = bookId.value,
                op = OpKind.Upsert,
                payload = contractJson.encodeToString(RecordPositionRequest.serializer(), request),
                ownerUserId = userId,
                coalesce = true,
            )
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Queue write failure is non-fatal: the position is already saved locally.
            // The next position write will enqueue, and the missing op will be reconciled
            // by the catch-up on the next connection.
            logger.warn(e) { "Failed to enqueue position write for ${bookId.value} — will retry on next write" }
        }
    }

    // Builds the wire request for a variant, or null when nothing should be enqueued
    // (inbound CrossDeviceSync would echo-loop; a reset with no local row has nothing to send).
    // [maxPositionMs] is the post-write high-water the caller already read, carried verbatim so
    // the outbox can never enqueue a lower max than the local row holds.
    private fun buildPositionRequest(
        bookId: BookId,
        update: PlaybackUpdate,
        entity: PlaybackPositionEntity?,
        maxPositionMs: Long,
        now: Long,
    ): RecordPositionRequest? =
        when (update) {
            is PlaybackUpdate.Position -> {
                positionRequest(
                    bookId,
                    update.positionMs,
                    now,
                    entity?.isFinished ?: false,
                    update.speed,
                    maxPositionMs,
                )
            }

            is PlaybackUpdate.Speed -> {
                positionRequest(
                    bookId,
                    update.positionMs,
                    now,
                    entity?.isFinished ?: false,
                    update.speed,
                    maxPositionMs,
                )
            }

            is PlaybackUpdate.SpeedReset -> {
                positionRequest(
                    bookId,
                    update.positionMs,
                    now,
                    entity?.isFinished ?: false,
                    update.defaultSpeed,
                    maxPositionMs,
                )
            }

            is PlaybackUpdate.PlaybackStarted -> {
                positionRequest(
                    bookId,
                    update.positionMs,
                    now,
                    entity?.isFinished ?: false,
                    update.speed,
                    maxPositionMs,
                )
            }

            is PlaybackUpdate.PlaybackPaused -> {
                positionRequest(
                    bookId,
                    update.positionMs,
                    now,
                    entity?.isFinished ?: false,
                    update.speed,
                    maxPositionMs,
                )
            }

            is PlaybackUpdate.PeriodicUpdate -> {
                positionRequest(
                    bookId,
                    update.positionMs,
                    now,
                    entity?.isFinished ?: false,
                    update.speed,
                    maxPositionMs,
                )
            }

            is PlaybackUpdate.BookFinished -> {
                positionRequest(
                    bookId,
                    update.finalPositionMs,
                    now,
                    finished = true,
                    entity?.playbackSpeed ?: 1.0f,
                    maxPositionMs,
                )
            }

            is PlaybackUpdate.MarkComplete -> {
                positionRequest(
                    bookId,
                    entity?.positionMs ?: 0L,
                    now,
                    finished = true,
                    entity?.playbackSpeed ?: 1.0f,
                    maxPositionMs,
                )
            }

            // Inbound reconciliation only — pushing it back would create an echo loop.
            is PlaybackUpdate.CrossDeviceSync -> {
                null
            }

            // User-command resets: enqueue the post-reset row so the discard/restart reaches the
            // server immediately (NewerWins on lastPlayedAt lets it beat stale positions from other
            // devices). coalesce=true supersedes any queued periodic write for this book. The
            // startedAt reset stays local-only: RecordPositionRequest carries no startedAt.
            PlaybackUpdate.DiscardProgress,
            PlaybackUpdate.Restart,
            -> {
                entity?.let {
                    positionRequest(
                        bookId,
                        it.positionMs,
                        it.lastPlayedAt ?: now,
                        finished = false,
                        it.playbackSpeed,
                        maxPositionMs,
                    )
                }
            }
        }

    private fun positionRequest(
        bookId: BookId,
        positionMs: Long,
        lastPlayedAt: Long,
        finished: Boolean,
        playbackSpeed: Float,
        maxPositionMs: Long,
    ): RecordPositionRequest =
        RecordPositionRequest(
            bookId = bookId.value,
            positionMs = positionMs,
            lastPlayedAt = lastPlayedAt,
            finished = finished,
            playbackSpeed = playbackSpeed,
            currentChapterId = null,
            maxPositionMs = maxPositionMs,
        )

    // ----- Per-variant handlers -------------------------------------------------------------

    private suspend fun handlePosition(
        bookId: BookId,
        u: PlaybackUpdate.Position,
    ) {
        // Periodic position save during playback. Use updatePositionOnly to preserve
        // hasCustomSpeed + playbackSpeed against concurrent speed-change writers
        // (PlaybackPositionDao.updatePositionOnly contract).
        val now = currentEpochMilliseconds()
        dao.updatePositionOnly(bookId, u.positionMs, updatedAt = now, lastPlayedAt = now)
    }

    private suspend fun handleSpeed(
        bookId: BookId,
        u: PlaybackUpdate.Speed,
    ) {
        val existing = dao.get(bookId)
        val now = currentEpochMilliseconds()
        val newMax = max(existing?.maxPositionMs ?: 0, u.positionMs)
        val merged =
            existing?.copy(
                positionMs = u.positionMs,
                playbackSpeed = u.speed,
                hasCustomSpeed = u.custom,
                updatedAt = now,
                lastPlayedAt = now,
                syncedAt = null,
                maxPositionMs = newMax,
            ) ?: blank(bookId, now).copy(
                positionMs = u.positionMs,
                playbackSpeed = u.speed,
                hasCustomSpeed = u.custom,
                maxPositionMs = newMax,
            )
        dao.save(merged)
    }

    private suspend fun handleSpeedReset(
        bookId: BookId,
        u: PlaybackUpdate.SpeedReset,
    ) {
        val existing = dao.get(bookId)
        val now = currentEpochMilliseconds()
        val newMax = max(existing?.maxPositionMs ?: 0, u.positionMs)
        val merged =
            existing?.copy(
                positionMs = u.positionMs,
                playbackSpeed = u.defaultSpeed,
                hasCustomSpeed = false,
                updatedAt = now,
                lastPlayedAt = now,
                syncedAt = null,
                maxPositionMs = newMax,
            ) ?: blank(bookId, now).copy(
                positionMs = u.positionMs,
                playbackSpeed = u.defaultSpeed,
                hasCustomSpeed = false,
                maxPositionMs = newMax,
            )
        dao.save(merged)
    }

    private suspend fun handlePlaybackStarted(
        bookId: BookId,
        u: PlaybackUpdate.PlaybackStarted,
    ) {
        val existing = dao.get(bookId)
        val now = currentEpochMilliseconds()
        val newMax = max(existing?.maxPositionMs ?: 0, u.positionMs)
        val merged =
            existing?.copy(
                positionMs = u.positionMs,
                playbackSpeed = u.speed,
                startedAt = existing.startedAt ?: now, // preserve original startedAt if set
                lastPlayedAt = now,
                updatedAt = now,
                syncedAt = null,
                maxPositionMs = newMax,
            ) ?: blank(bookId, now).copy(
                positionMs = u.positionMs,
                playbackSpeed = u.speed,
                startedAt = now,
                maxPositionMs = newMax,
            )
        dao.save(merged)
    }

    private suspend fun handlePlaybackPaused(
        bookId: BookId,
        u: PlaybackUpdate.PlaybackPaused,
    ) {
        // Same shape as Position — periodic position flush; speed preserved via
        // updatePositionOnly (per dao contract).
        val now = currentEpochMilliseconds()
        dao.updatePositionOnly(bookId, u.positionMs, updatedAt = now, lastPlayedAt = now)
    }

    private suspend fun handlePeriodicUpdate(
        bookId: BookId,
        u: PlaybackUpdate.PeriodicUpdate,
    ) {
        val now = currentEpochMilliseconds()
        dao.updatePositionOnly(bookId, u.positionMs, updatedAt = now, lastPlayedAt = now)
    }

    private suspend fun handleBookFinished(
        bookId: BookId,
        u: PlaybackUpdate.BookFinished,
    ) {
        val existing = dao.get(bookId)
        val now = currentEpochMilliseconds()
        // Preserve original finishedAt on re-finish — first-completion timestamp is sticky.
        val finishedAt = existing?.finishedAt ?: now
        val startedAt = existing?.startedAt ?: now
        val newMax = max(existing?.maxPositionMs ?: 0, u.finalPositionMs)
        val merged =
            existing?.copy(
                positionMs = u.finalPositionMs,
                isFinished = true,
                finishedAt = finishedAt,
                startedAt = startedAt,
                updatedAt = now,
                lastPlayedAt = now,
                syncedAt = null,
                maxPositionMs = newMax,
            ) ?: blank(bookId, now).copy(
                positionMs = u.finalPositionMs,
                isFinished = true,
                finishedAt = finishedAt,
                startedAt = startedAt,
                maxPositionMs = newMax,
            )
        dao.save(merged)
    }

    private suspend fun handleCrossDeviceSync(
        bookId: BookId,
        u: PlaybackUpdate.CrossDeviceSync,
    ) {
        // Reconcile-with-stored: only apply if server progress is newer than stored.
        // Canonical cross-device merge lives in the repository (Phase B's
        // single-writer goal). The handler's caller is responsible for "is this book locally
        // playing? skip" — that's a higher-level policy, not a per-row write rule.
        val payload = u.progress
        val lastPlayedAtMs = parseIsoOrNull(payload.lastPlayedAt) ?: return
        val finishedAtMs = payload.finishedAt?.let { parseIsoOrNull(it) }
        val startedAtMs = payload.startedAt?.let { parseIsoOrNull(it) }

        val existing = dao.get(bookId)
        if (existing != null && (existing.lastPlayedAt ?: 0L) >= lastPlayedAtMs) {
            // Local is newer — nothing to do.
            return
        }

        val newMax = max(existing?.maxPositionMs ?: 0, payload.currentPositionMs)
        val merged =
            existing?.copy(
                positionMs = payload.currentPositionMs,
                isFinished = payload.isFinished,
                lastPlayedAt = lastPlayedAtMs,
                updatedAt = lastPlayedAtMs,
                syncedAt = lastPlayedAtMs,
                // Server omits null timestamps; wire-absence means "no change".
                finishedAt = finishedAtMs ?: existing.finishedAt,
                startedAt = startedAtMs ?: existing.startedAt,
                maxPositionMs = newMax,
                // playbackSpeed and hasCustomSpeed preserved implicitly via .copy().
            ) ?: PlaybackPositionEntity(
                bookId = bookId,
                positionMs = payload.currentPositionMs,
                playbackSpeed = 1.0f,
                hasCustomSpeed = false,
                isFinished = payload.isFinished,
                lastPlayedAt = lastPlayedAtMs,
                updatedAt = lastPlayedAtMs,
                syncedAt = lastPlayedAtMs,
                finishedAt = finishedAtMs,
                startedAt = startedAtMs,
                maxPositionMs = newMax,
            )
        dao.save(merged)
    }

    private suspend fun handleMarkComplete(
        bookId: BookId,
        u: PlaybackUpdate.MarkComplete,
    ) {
        val existing = dao.get(bookId)
        val now = currentEpochMilliseconds()
        val effectiveFinishedAt = u.finishedAt ?: now
        val effectiveStartedAt = u.startedAt ?: existing?.startedAt ?: now
        val merged =
            existing?.copy(
                isFinished = true,
                finishedAt = effectiveFinishedAt,
                startedAt = effectiveStartedAt,
                updatedAt = now,
                syncedAt = null,
            ) ?: PlaybackPositionEntity(
                bookId = bookId,
                positionMs = 0L,
                playbackSpeed = 1.0f,
                hasCustomSpeed = false,
                updatedAt = now,
                lastPlayedAt = now,
                isFinished = true,
                finishedAt = effectiveFinishedAt,
                startedAt = effectiveStartedAt,
            )
        dao.save(merged)
    }

    private suspend fun handleDiscardProgress(bookId: BookId) {
        val existing = dao.get(bookId) ?: return // no-op if no row
        val now = currentEpochMilliseconds()
        dao.save(
            existing.copy(
                positionMs = 0L,
                isFinished = false,
                finishedAt = null,
                updatedAt = now,
                lastPlayedAt = now,
                syncedAt = null,
            ),
        )
    }

    private suspend fun handleRestart(bookId: BookId) {
        val existing = dao.get(bookId) ?: return // no-op if no row
        val now = currentEpochMilliseconds()
        dao.save(
            existing.copy(
                positionMs = 0L,
                isFinished = false,
                finishedAt = null,
                startedAt = now, // new reading session starts now (matches existing facade)
                updatedAt = now,
                lastPlayedAt = now,
                syncedAt = null,
            ),
        )
    }

    /**
     * Construct a fresh blank entity for [bookId] anchored at [now].
     * Used when a variant handler must materialize a row that doesn't yet exist.
     */
    private fun blank(
        bookId: BookId,
        now: Long,
    ): PlaybackPositionEntity =
        PlaybackPositionEntity(
            bookId = bookId,
            positionMs = 0L,
            playbackSpeed = 1.0f,
            hasCustomSpeed = false,
            updatedAt = now,
            syncedAt = null,
            lastPlayedAt = now,
            isFinished = false,
            finishedAt = null,
            startedAt = now,
        )
}

/**
 * Convert PlaybackPositionEntity to PlaybackPosition domain model.
 */
private fun PlaybackPositionEntity.toDomain(): PlaybackPosition =
    PlaybackPosition(
        bookId = bookId.value,
        positionMs = positionMs,
        playbackSpeed = playbackSpeed,
        hasCustomSpeed = hasCustomSpeed,
        updatedAtMs = updatedAt,
        syncedAtMs = syncedAt,
        lastPlayedAtMs = lastPlayedAt,
        isFinished = isFinished,
        finishedAtMs = finishedAt,
        startedAtMs = startedAt,
    )

/**
 * Parse ISO 8601 to epoch ms; returns null on malformed input.
 *
 * Used by [PlaybackUpdate.CrossDeviceSync] to skip rows whose timestamps the
 * server malformed, leaving the next sync to reconcile.
 */
@Suppress("SwallowedException", "TooGenericExceptionCaught")
private fun parseIsoOrNull(iso: String): Long? =
    try {
        Instant.parse(iso).toEpochMilliseconds()
    } catch (_: Exception) {
        null
    }
