package com.calypsan.listenup.client.test.fake

import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.domain.playback.PlaybackTimeline
import com.calypsan.listenup.client.playback.AudioPlayer
import com.calypsan.listenup.client.playback.PlaybackManager
import com.calypsan.listenup.client.playback.PlaybackManager.ChapterInfo
import com.calypsan.listenup.client.playback.PlaybackManager.PlaybackErrorUiState
import com.calypsan.listenup.client.playback.PlaybackManager.PrepareResult
import com.calypsan.listenup.client.playback.PlaybackState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Test fake for [PlaybackManager] — implements the interface without inheritance.
 * Tests drive backing flows directly; assertions
 * check recorder lists.
 *
 * Mirrors the [FakeProgressTracker] pattern in spirit (rubric-aligned "fakes for
 * seams"), but lighter — no `super(...)` ceremony since the interface has no
 * superclass.
 *
 * Note: `playbackManager.chapterChange` and `playbackManager.sleepTimerEvent` are NOT
 * on the [PlaybackManager] interface. The VM observes chapter changes via
 * [currentChapter] (drive [currentChapterFlow] directly). Sleep events come from
 * `sleepTimerManager.sleepEvent` — back the SleepTimerManager mock with a SharedFlow
 * in those tests, not here.
 */
class FakePlaybackManager : PlaybackManager {
    // === Backing flows for read-side observers ===

    val currentBookIdFlow = MutableStateFlow<BookId?>(null)
    override val currentBookId: StateFlow<BookId?> = currentBookIdFlow.asStateFlow()

    val currentTimelineFlow = MutableStateFlow<PlaybackTimeline?>(null)
    override val currentTimeline: StateFlow<PlaybackTimeline?> = currentTimelineFlow.asStateFlow()

    val currentChapterFlow = MutableStateFlow<ChapterInfo?>(null)
    override val currentChapter: StateFlow<ChapterInfo?> = currentChapterFlow.asStateFlow()

    val chaptersFlow = MutableStateFlow<List<Chapter>>(emptyList())
    override val chapters: StateFlow<List<Chapter>> = chaptersFlow.asStateFlow()

    val currentPositionMsFlow = MutableStateFlow(0L)
    override val currentPositionMs: StateFlow<Long> = currentPositionMsFlow.asStateFlow()

    val totalDurationMsFlow = MutableStateFlow(0L)
    override val totalDurationMs: StateFlow<Long> = totalDurationMsFlow.asStateFlow()

    val playbackSpeedFlow = MutableStateFlow(1.0f)
    override val playbackSpeed: StateFlow<Float> = playbackSpeedFlow.asStateFlow()

    val volumeBoostDbFlow = MutableStateFlow(0f)
    override val volumeBoostDb: StateFlow<Float> = volumeBoostDbFlow.asStateFlow()

    val isPlayingFlow = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = isPlayingFlow.asStateFlow()

    val isBufferingFlow = MutableStateFlow(false)
    override val isBuffering: StateFlow<Boolean> = isBufferingFlow.asStateFlow()

    val playbackStateFlow = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    override val playbackState: StateFlow<PlaybackState> = playbackStateFlow.asStateFlow()

    val playbackErrorFlow = MutableStateFlow<PlaybackErrorUiState?>(null)
    override val playbackError: StateFlow<PlaybackErrorUiState?> = playbackErrorFlow.asStateFlow()

    val effectiveGainDbFlow = MutableStateFlow(0f)
    override val effectiveGainDb: StateFlow<Float> = effectiveGainDbFlow.asStateFlow()

    val preparingBookIdFlow = MutableStateFlow<BookId?>(null)
    override val preparingBookId: StateFlow<BookId?> = preparingBookIdFlow.asStateFlow()

    // No debounce in the fake — tests drive [preparingBookIdFlow]/[markPreparing] synchronously
    // and assert on it directly; the real debounce timing is covered by PreparingBookIdUiFlowTest.
    val preparingBookIdUiFlow = MutableStateFlow<BookId?>(null)
    override val preparingBookIdUi: StateFlow<BookId?> = preparingBookIdUiFlow.asStateFlow()

    // === Notification callback hook ===

    override var onChapterChanged: ((ChapterInfo) -> Unit)? = null

    // === Stubbed return values & recorder lists for write-side assertions ===

    var stubbedPrepareResult: PrepareResult? = null

    /**
     * Optional suspension hook invoked by [prepareForPlayback] before it returns. No-op by
     * default, so most tests resolve synchronously once the dispatcher advances. Tests that need
     * to exercise cancellation of an in-flight prepare (e.g. a superseding tap for a different
     * book) set this to something that genuinely suspends — e.g. `{ someDeferred.await() }` —
     * so the coroutine can be cancelled mid-flight instead of completing before the second tap.
     */
    var prepareForPlaybackSuspension: suspend () -> Unit = {}

    val activatedBookIds: MutableList<BookId> = mutableListOf()
    val markPreparingCalls: MutableList<BookId> = mutableListOf()
    var clearPreparingCalls: Int = 0
    val prepareForPlaybackCalls: MutableList<BookId> = mutableListOf()
    val startPlaybackCalls: MutableList<StartPlaybackCall> = mutableListOf()
    val reportedErrors: MutableList<PlaybackErrorUiState> = mutableListOf()
    var clearPlaybackCalls: Int = 0
    var clearErrorCalls: Int = 0
    val setPlayingCalls: MutableList<Boolean> = mutableListOf()
    val setBufferingCalls: MutableList<Boolean> = mutableListOf()
    val setPlaybackStateCalls: MutableList<PlaybackState> = mutableListOf()
    val updatedPositions: MutableList<Long> = mutableListOf()
    val updatedSpeeds: MutableList<Float> = mutableListOf()
    val speedChanges: MutableList<Float> = mutableListOf()
    val speedResets: MutableList<Float> = mutableListOf()
    val boostChanges: MutableList<Float> = mutableListOf()
    val boostResets: MutableList<Float> = mutableListOf()

    data class StartPlaybackCall(
        val player: AudioPlayer,
        val resumePositionMs: Long,
        val resumeSpeed: Float,
    )

    // === Write-side overrides ===

    override fun activateBook(bookId: BookId) {
        activatedBookIds += bookId
        currentBookIdFlow.value = bookId
    }

    override fun markPreparing(bookId: BookId) {
        markPreparingCalls += bookId
        preparingBookIdFlow.value = bookId
        preparingBookIdUiFlow.value = bookId
    }

    override fun clearPreparing() {
        clearPreparingCalls += 1
        preparingBookIdFlow.value = null
        preparingBookIdUiFlow.value = null
    }

    override suspend fun prepareForPlayback(bookId: BookId): PrepareResult? {
        prepareForPlaybackCalls += bookId
        prepareForPlaybackSuspension()
        return stubbedPrepareResult
    }

    override suspend fun startPlayback(
        player: AudioPlayer,
        resumePositionMs: Long,
        resumeSpeed: Float,
    ) {
        startPlaybackCalls += StartPlaybackCall(player, resumePositionMs, resumeSpeed)
    }

    override fun onSpeedChanged(speed: Float) {
        speedChanges += speed
        playbackSpeedFlow.value = speed
    }

    override fun onSpeedReset(defaultSpeed: Float) {
        speedResets += defaultSpeed
        playbackSpeedFlow.value = defaultSpeed
    }

    override fun onVolumeBoostChanged(boostDb: Float) {
        boostChanges += boostDb
        volumeBoostDbFlow.value = boostDb
    }

    override fun onBoostReset(defaultBoostDb: Float) {
        boostResets += defaultBoostDb
        volumeBoostDbFlow.value = defaultBoostDb
    }

    // === PlaybackStateProvider overrides ===

    override fun clearPlayback() {
        clearPlaybackCalls += 1
        currentBookIdFlow.value = null
        isPlayingFlow.value = false
    }

    // === PlaybackStateWriter overrides ===

    override fun setPlaying(playing: Boolean) {
        setPlayingCalls += playing
        isPlayingFlow.value = playing
    }

    override fun setBuffering(buffering: Boolean) {
        setBufferingCalls += buffering
        isBufferingFlow.value = buffering
    }

    override fun setPlaybackState(state: PlaybackState) {
        setPlaybackStateCalls += state
        playbackStateFlow.value = state
    }

    override fun updatePosition(positionMs: Long) {
        updatedPositions += positionMs
        currentPositionMsFlow.value = positionMs
    }

    /** Records raw `(index, offset)` calls, then converts via [currentTimelineFlow] like production. */
    val updatedMediaItemPositions: MutableList<Pair<Int, Long>> = mutableListOf()

    override fun updatePositionFromMediaItem(
        mediaItemIndex: Int,
        positionInItemMs: Long,
    ) {
        updatedMediaItemPositions += mediaItemIndex to positionInItemMs
        val bookPositionMs =
            currentTimelineFlow.value?.toBookPosition(mediaItemIndex, positionInItemMs)
                ?: positionInItemMs
        updatePosition(bookPositionMs)
    }

    override fun updateSpeed(speed: Float) {
        updatedSpeeds += speed
        playbackSpeedFlow.value = speed
    }

    override fun reportError(
        message: String,
        isRecoverable: Boolean,
    ) {
        reportedErrors += PlaybackErrorUiState(message = message, isRecoverable = isRecoverable, timestampMs = 0L)
    }

    override fun clearError() {
        clearErrorCalls += 1
        playbackErrorFlow.value = null
    }
}
