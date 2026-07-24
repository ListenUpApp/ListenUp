package com.calypsan.listenup.client.test.fake

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.domain.model.PlaybackPosition
import com.calypsan.listenup.client.domain.repository.LastPlayedInfo
import com.calypsan.listenup.client.domain.repository.PlaybackPositionRepository
import com.calypsan.listenup.client.domain.repository.PlaybackUpdate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory fake of [PlaybackPositionRepository] backed by a [MutableStateFlow] of
 * book-id → position. Emits on every write so reactive consumers (`observeAll`)
 * behave like the real Room-backed implementation.
 *
 * Why a fake instead of a Mokkery mock: the bugs this interface participates in (Bug 2 —
 * completion persistence, Bug 3 — speed stickiness, Bug 6 — position sync) are all
 * read-after-write bugs. A mock can only verify calls happened; this fake reproduces
 * the read-back semantics so seam tests can catch write-that-doesn't-stick regressions.
 *
 * @param initialPositions seed state for the fake (useful for test fixtures).
 * @param nowMs timestamp provider — defaults to `0L` so tests can use
 *     `TestScope.currentTime` for deterministic time, or override for specific moments.
 */
class FakePlaybackPositionRepository(
    initialPositions: Map<String, PlaybackPosition> = emptyMap(),
    private val nowMs: () -> Long = { 0L },
) : PlaybackPositionRepository {
    private val state = MutableStateFlow(initialPositions)

    override suspend fun get(bookId: BookId): AppResult<PlaybackPosition?> =
        AppResult.Success(state.value[bookId.value])

    override suspend fun getLastPlayedBook(): AppResult<LastPlayedInfo?> {
        val most = state.value.values.maxByOrNull { it.effectiveLastPlayedAtMs } ?: return AppResult.Success(null)
        return AppResult.Success(
            LastPlayedInfo(
                bookId = BookId(most.bookId),
                positionMs = most.positionMs,
                playbackSpeed = most.playbackSpeed,
            ),
        )
    }

    override fun observeAll(): Flow<Map<BookId, PlaybackPosition>> =
        state.asStateFlow().map { stringKeyed ->
            stringKeyed.mapKeys { (k, _) -> BookId(k) }
        }

    override fun observe(bookId: BookId): Flow<PlaybackPosition?> = state.asStateFlow().map { it[bookId.value] }

    override suspend fun delete(bookId: BookId): AppResult<Unit> {
        state.value = state.value - bookId.value
        return AppResult.Success(Unit)
    }

    override suspend fun markComplete(
        bookId: BookId,
        startedAt: Long?,
        finishedAt: Long?,
    ): AppResult<Unit> {
        val key = bookId.value
        val now = nowMs()
        val existing =
            state.value[key] ?: PlaybackPosition(
                bookId = key,
                positionMs = 0L,
                playbackSpeed = 1.0f,
                hasCustomSpeed = false,
                updatedAtMs = now,
                syncedAtMs = null,
                lastPlayedAtMs = now,
            )
        state.value = state.value + (
            key to
                existing.copy(
                    isFinished = true,
                    finishedAtMs = finishedAt ?: now,
                    startedAtMs = startedAt ?: existing.startedAtMs,
                    updatedAtMs = now,
                )
        )
        return AppResult.Success(Unit)
    }

    override suspend fun discardProgress(bookId: BookId): AppResult<Unit> {
        state.value = state.value - bookId.value
        return AppResult.Success(Unit)
    }

    override suspend fun restartBook(bookId: BookId): AppResult<Unit> {
        val key = bookId.value
        val existing = state.value[key] ?: return AppResult.Success(Unit)
        state.value = state.value + (
            key to
                existing.copy(
                    positionMs = 0L,
                    isFinished = false,
                    finishedAtMs = null,
                    updatedAtMs = nowMs(),
                )
        )
        return AppResult.Success(Unit)
    }

    /**
     * Minimal in-memory variant dispatch. Mirrors the production repository's
     * behavior closely enough to back seam-level tests but does not exercise
     * Mutex/transaction semantics (the fake is single-threaded).
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    override suspend fun savePlaybackState(
        bookId: BookId,
        update: PlaybackUpdate,
    ): AppResult<Unit> {
        val key = bookId.value
        val now = nowMs()
        val existing = state.value[key]
        val merged: PlaybackPosition? =
            when (update) {
                is PlaybackUpdate.Position -> {
                    (existing ?: blankPosition(key, now)).copy(
                        positionMs = update.positionMs,
                        updatedAtMs = now,
                        lastPlayedAtMs = now,
                        syncedAtMs = null,
                    )
                }

                is PlaybackUpdate.Speed -> {
                    (existing ?: blankPosition(key, now)).copy(
                        positionMs = update.positionMs,
                        playbackSpeed = update.speed,
                        hasCustomSpeed = update.custom,
                        updatedAtMs = now,
                        lastPlayedAtMs = now,
                        syncedAtMs = null,
                    )
                }

                is PlaybackUpdate.SpeedReset -> {
                    (existing ?: blankPosition(key, now)).copy(
                        positionMs = update.positionMs,
                        playbackSpeed = update.defaultSpeed,
                        hasCustomSpeed = false,
                        updatedAtMs = now,
                        lastPlayedAtMs = now,
                        syncedAtMs = null,
                    )
                }

                is PlaybackUpdate.PlaybackStarted -> {
                    (existing ?: blankPosition(key, now)).copy(
                        positionMs = update.positionMs,
                        playbackSpeed = update.speed,
                        startedAtMs = existing?.startedAtMs ?: now,
                        updatedAtMs = now,
                        lastPlayedAtMs = now,
                        syncedAtMs = null,
                    )
                }

                is PlaybackUpdate.PlaybackPaused -> {
                    (existing ?: blankPosition(key, now)).copy(
                        positionMs = update.positionMs,
                        updatedAtMs = now,
                        lastPlayedAtMs = now,
                        syncedAtMs = null,
                    )
                }

                is PlaybackUpdate.PeriodicUpdate -> {
                    (existing ?: blankPosition(key, now)).copy(
                        positionMs = update.positionMs,
                        updatedAtMs = now,
                        lastPlayedAtMs = now,
                        syncedAtMs = null,
                    )
                }

                is PlaybackUpdate.BookFinished -> {
                    (existing ?: blankPosition(key, now)).copy(
                        positionMs = update.finalPositionMs,
                        isFinished = true,
                        finishedAtMs = existing?.finishedAtMs ?: now,
                        startedAtMs = existing?.startedAtMs ?: now,
                        updatedAtMs = now,
                        lastPlayedAtMs = now,
                        syncedAtMs = null,
                    )
                }

                is PlaybackUpdate.MarkComplete -> {
                    (existing ?: blankPosition(key, now)).copy(
                        isFinished = true,
                        finishedAtMs = update.finishedAt ?: now,
                        startedAtMs = update.startedAt ?: existing?.startedAtMs ?: now,
                        updatedAtMs = now,
                        syncedAtMs = null,
                    )
                }

                PlaybackUpdate.DiscardProgress -> {
                    existing?.copy(
                        positionMs = 0L,
                        isFinished = false,
                        finishedAtMs = null,
                        updatedAtMs = now,
                        lastPlayedAtMs = now,
                        syncedAtMs = null,
                    )
                }

                PlaybackUpdate.Restart -> {
                    existing?.copy(
                        positionMs = 0L,
                        isFinished = false,
                        finishedAtMs = null,
                        startedAtMs = now,
                        updatedAtMs = now,
                        lastPlayedAtMs = now,
                        syncedAtMs = null,
                    )
                }
            }
        if (merged != null) {
            state.value = state.value + (key to merged)
        }
        return AppResult.Success(Unit)
    }

    private fun blankPosition(
        bookId: String,
        now: Long,
    ): PlaybackPosition =
        PlaybackPosition(
            bookId = bookId,
            positionMs = 0L,
            playbackSpeed = 1.0f,
            hasCustomSpeed = false,
            updatedAtMs = now,
            syncedAtMs = null,
            lastPlayedAtMs = now,
            isFinished = false,
            finishedAtMs = null,
            startedAtMs = null,
        )
}
