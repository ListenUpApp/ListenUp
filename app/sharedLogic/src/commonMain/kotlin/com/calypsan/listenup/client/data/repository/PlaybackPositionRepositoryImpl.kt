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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
                // The Room merge AND the outbox enqueue commit in ONE transaction — all-or-nothing.
                // A crash between them can no longer leave the newest position local-only (catch-up
                // is inbound-only and NewerWins shields the local row, so a lost enqueue would never
                // reach the server). The drain SIGNAL is deferred to after commit: ticking it inside
                // the transaction races the drain collector against pre-commit state (see OfflineEditor).
                val enqueued =
                    transactionRunner.atomically {
                        handle(bookId, update)
                        enqueueIfPositionMoving(bookId, update)
                    }
                if (enqueued) pendingQueue.signalEnqueued()
            }
        }

    /**
     * Exhaustive dispatcher over the 10-variant [PlaybackUpdate] hierarchy.
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
            is PlaybackUpdate.MarkComplete -> handleMarkComplete(bookId, update)
            PlaybackUpdate.DiscardProgress -> handleDiscardProgress(bookId)
            PlaybackUpdate.Restart -> handleRestart(bookId)
        }
    }

    // ----- Outbox enqueue -------------------------------------------------------------------

    /**
     * Enqueues a [RecordPositionRequest] for the position-moving [PlaybackUpdate] variants,
     * returning `true` when an op was enqueued so the caller can tick the drain signal once the
     * enclosing transaction commits.
     *
     * Runs INSIDE the caller's transaction (atomic with the Room merge). The row snapshot it reads
     * (`dao.get`) therefore sees the just-written state within the same transaction, and a failed
     * enqueue rolls the whole save back rather than being swallowed — the OfflineEditor pattern.
     * The next position tick re-saves and re-enqueues, so a rolled-back save is never a lost update.
     *
     * No sign-in user means no server to push to — silently skipped (returns `false`).
     *
     * Non-terminal variants carry the row's current `isFinished` onto the wire — a
     * periodic/seek write must never un-finish a finished book on the server. Unfinishing
     * is explicit: only [PlaybackUpdate.DiscardProgress]/[PlaybackUpdate.Restart] unfinish.
     */
    private suspend fun enqueueIfPositionMoving(
        bookId: BookId,
        update: PlaybackUpdate,
    ): Boolean {
        val userId = authSession.getUserId() ?: return false
        val now = currentEpochMilliseconds()
        // Post-transaction snapshot of the row handle() just wrote. Non-terminal variants
        // carry its isFinished onto the wire — a periodic/seek write must never un-finish a
        // finished book on the server. Unfinishing is explicit: only DiscardProgress/Restart
        // send finished=false by design.
        val entity = dao.get(bookId)
        val request: RecordPositionRequest =
            when (update) {
                is PlaybackUpdate.Position -> {
                    RecordPositionRequest(
                        bookId = bookId.value,
                        positionMs = update.positionMs,
                        lastPlayedAt = now,
                        finished = entity?.isFinished ?: false,
                        playbackSpeed = update.speed,
                        currentChapterId = null,
                    )
                }

                is PlaybackUpdate.Speed -> {
                    RecordPositionRequest(
                        bookId = bookId.value,
                        positionMs = update.positionMs,
                        lastPlayedAt = now,
                        finished = entity?.isFinished ?: false,
                        playbackSpeed = update.speed,
                        currentChapterId = null,
                    )
                }

                is PlaybackUpdate.SpeedReset -> {
                    RecordPositionRequest(
                        bookId = bookId.value,
                        positionMs = update.positionMs,
                        lastPlayedAt = now,
                        finished = entity?.isFinished ?: false,
                        playbackSpeed = update.defaultSpeed,
                        currentChapterId = null,
                    )
                }

                is PlaybackUpdate.PlaybackStarted -> {
                    RecordPositionRequest(
                        bookId = bookId.value,
                        positionMs = update.positionMs,
                        lastPlayedAt = now,
                        finished = entity?.isFinished ?: false,
                        playbackSpeed = update.speed,
                        currentChapterId = null,
                    )
                }

                is PlaybackUpdate.PlaybackPaused -> {
                    RecordPositionRequest(
                        bookId = bookId.value,
                        positionMs = update.positionMs,
                        lastPlayedAt = now,
                        finished = entity?.isFinished ?: false,
                        playbackSpeed = update.speed,
                        currentChapterId = null,
                    )
                }

                is PlaybackUpdate.PeriodicUpdate -> {
                    RecordPositionRequest(
                        bookId = bookId.value,
                        positionMs = update.positionMs,
                        lastPlayedAt = now,
                        finished = entity?.isFinished ?: false,
                        playbackSpeed = update.speed,
                        currentChapterId = null,
                    )
                }

                is PlaybackUpdate.BookFinished -> {
                    RecordPositionRequest(
                        bookId = bookId.value,
                        positionMs = update.finalPositionMs,
                        lastPlayedAt = now,
                        finished = true,
                        playbackSpeed = entity?.playbackSpeed ?: 1.0f,
                        currentChapterId = null,
                    )
                }

                is PlaybackUpdate.MarkComplete -> {
                    RecordPositionRequest(
                        bookId = bookId.value,
                        positionMs = entity?.positionMs ?: 0L,
                        lastPlayedAt = now,
                        finished = true,
                        playbackSpeed = entity?.playbackSpeed ?: 1.0f,
                        currentChapterId = null,
                    )
                }

                // User-command resets: enqueue the post-reset row so the discard/restart
                // reaches the server immediately (NewerWins on lastPlayedAt lets it beat
                // stale positions from other devices). coalesce=true supersedes any queued
                // periodic write for this book. The startedAt reset stays local-only:
                // RecordPositionRequest carries no startedAt and this arc makes no wire changes.
                PlaybackUpdate.DiscardProgress,
                PlaybackUpdate.Restart,
                -> {
                    if (entity == null) return false
                    RecordPositionRequest(
                        bookId = bookId.value,
                        positionMs = entity.positionMs,
                        lastPlayedAt = entity.lastPlayedAt ?: now,
                        finished = false,
                        playbackSpeed = entity.playbackSpeed,
                        currentChapterId = null,
                    )
                }
            }

        // signal = false: the row write stays in the transaction, but the drain signal must fire
        // only AFTER commit (savePlaybackState calls signalEnqueued). No swallow: a failed enqueue
        // propagates, rolling back the atomically {} block so no half-saved state can persist.
        pendingQueue.enqueue(
            channel = OutboxChannels.Positions,
            entityId = bookId.value,
            op = OpKind.Upsert,
            payload = contractJson.encodeToString(RecordPositionRequest.serializer(), request),
            ownerUserId = userId,
            coalesce = true,
            signal = false,
        )
        return true
    }

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
        val merged =
            existing?.copy(
                positionMs = u.positionMs,
                playbackSpeed = u.speed,
                hasCustomSpeed = u.custom,
                updatedAt = now,
                lastPlayedAt = now,
                syncedAt = null,
            ) ?: blank(bookId, now).copy(
                positionMs = u.positionMs,
                playbackSpeed = u.speed,
                hasCustomSpeed = u.custom,
            )
        dao.save(merged)
    }

    private suspend fun handleSpeedReset(
        bookId: BookId,
        u: PlaybackUpdate.SpeedReset,
    ) {
        val existing = dao.get(bookId)
        val now = currentEpochMilliseconds()
        val merged =
            existing?.copy(
                positionMs = u.positionMs,
                playbackSpeed = u.defaultSpeed,
                hasCustomSpeed = false,
                updatedAt = now,
                lastPlayedAt = now,
                syncedAt = null,
            ) ?: blank(bookId, now).copy(
                positionMs = u.positionMs,
                playbackSpeed = u.defaultSpeed,
                hasCustomSpeed = false,
            )
        dao.save(merged)
    }

    private suspend fun handlePlaybackStarted(
        bookId: BookId,
        u: PlaybackUpdate.PlaybackStarted,
    ) {
        val existing = dao.get(bookId)
        val now = currentEpochMilliseconds()
        val merged =
            existing?.copy(
                positionMs = u.positionMs,
                playbackSpeed = u.speed,
                startedAt = existing.startedAt ?: now, // preserve original startedAt if set
                lastPlayedAt = now,
                updatedAt = now,
                syncedAt = null,
            ) ?: blank(bookId, now).copy(
                positionMs = u.positionMs,
                playbackSpeed = u.speed,
                startedAt = now,
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
        val merged =
            existing?.copy(
                positionMs = u.finalPositionMs,
                isFinished = true,
                finishedAt = finishedAt,
                startedAt = startedAt,
                updatedAt = now,
                lastPlayedAt = now,
                syncedAt = null,
            ) ?: blank(bookId, now).copy(
                positionMs = u.finalPositionMs,
                isFinished = true,
                finishedAt = finishedAt,
                startedAt = startedAt,
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
