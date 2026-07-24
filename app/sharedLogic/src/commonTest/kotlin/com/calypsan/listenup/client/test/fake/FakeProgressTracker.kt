package com.calypsan.listenup.client.test.fake

import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.domain.model.PlaybackPosition
import com.calypsan.listenup.client.domain.repository.DownloadRepository
import com.calypsan.listenup.client.domain.repository.PlaybackPositionRepository
import com.calypsan.listenup.client.playback.ProgressTracker
import kotlinx.coroutines.CoroutineScope

/**
 * In-memory fake of [ProgressTracker] for seam-level tests that verify
 * [com.calypsan.listenup.client.playback.PlaybackManager] forwards player-state
 * transitions to the tracker.
 *
 * Records every invocation of [onPlaybackStarted] / [onPlaybackPaused] in
 * [onPlaybackStartedCalls] / [onPlaybackPausedCalls]. [getResumePosition]
 * returns [stubbedResumePosition] (default `null`).
 *
 * Constructor parameters mirror [ProgressTracker]'s — call sites pass the same
 * dependency mocks they would use to construct a real tracker. The overrides
 * ignore the parent state, so the parent's deps are inert.
 */
class FakeProgressTracker(
    downloadRepository: DownloadRepository,
    positionRepository: PlaybackPositionRepository,
    scope: CoroutineScope,
) : ProgressTracker(
        downloadRepository = downloadRepository,
        positionRepository = positionRepository,
        scope = scope,
    ) {
    private val _onPlaybackStartedCalls: MutableList<Triple<BookId, Long, Float>> = mutableListOf()
    val onPlaybackStartedCalls: List<Triple<BookId, Long, Float>> get() = _onPlaybackStartedCalls.toList()

    private val _onPlaybackPausedCalls: MutableList<Triple<BookId, Long, Float>> = mutableListOf()
    val onPlaybackPausedCalls: List<Triple<BookId, Long, Float>> get() = _onPlaybackPausedCalls.toList()

    /** Returned from [getResumePosition]. Default `null` mirrors a never-played book. */
    var stubbedResumePosition: PlaybackPosition? = null

    override fun onPlaybackStarted(
        bookId: BookId,
        positionMs: Long,
        speed: Float,
    ) {
        _onPlaybackStartedCalls += Triple(bookId, positionMs, speed)
    }

    override fun onPlaybackPaused(
        bookId: BookId,
        positionMs: Long,
        speed: Float,
    ) {
        _onPlaybackPausedCalls += Triple(bookId, positionMs, speed)
    }

    override suspend fun getResumePosition(bookId: BookId): PlaybackPosition? = stubbedResumePosition
}
