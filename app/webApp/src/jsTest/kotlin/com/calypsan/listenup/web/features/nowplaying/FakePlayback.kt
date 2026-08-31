package com.calypsan.listenup.web.features.nowplaying

import com.calypsan.listenup.client.domain.repository.PlaybackPreferences
import kotlinx.coroutines.flow.flowOf
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.domain.playback.PlaybackTimeline
import com.calypsan.listenup.client.playback.AudioPlayer
import com.calypsan.listenup.client.playback.AudioSegment
import com.calypsan.listenup.client.playback.PlaybackManager
import com.calypsan.listenup.client.playback.PlaybackState
import com.calypsan.listenup.core.BookId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * A [PlaybackManager] that prepares one real, decodable segment and bridges the player's flows
 * back — the two behaviours [LivePlayback] actually depends on, in the same order
 * `PlaybackManagerImpl` performs them.
 *
 * Deliberately NOT a stub that returns canned state: the point of the specs that use this is to
 * prove audio reaches the speakers through the real player, so the prepare has to hand back a
 * timeline pointing at bytes a browser can decode, and `startPlayback` has to `load()` them
 * without playing — which is exactly the shape that made the built-in-player lane silent.
 */
internal fun fakePlaybackManager(
    segment: AudioSegment,
    title: String,
    prepare: PrepareOutcome = PrepareOutcome.SUCCEEDS,
): FakePlaybackManager = FakePlaybackManager(segment, title, prepare)

/**
 * How a fake prepare ends. The two failing shapes are the ones a prime has to survive: a prepare
 * that blows up, and one that is still in flight when the session is closed.
 */
internal enum class PrepareOutcome {
    SUCCEEDS,
    THROWS,
    NEVER_RETURNS,

    /** The first book starts; every book after it has an RPC still in flight. */
    STALLS_AFTER_FIRST,
}

/**
 * Concrete rather than hidden behind [PlaybackManager] so a spec can *drive* the flows the
 * interface only exposes for reading — `currentChapter` in particular, which is how an
 * end-of-chapter sleep timer is told a chapter turned over.
 */
internal class FakePlaybackManager(
    private val segment: AudioSegment,
    private val title: String,
    private val prepare: PrepareOutcome,
) : PlaybackManager {
    private var prepareCount = 0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override val currentBookId = MutableStateFlow<BookId?>(null)
    override val currentTimeline = MutableStateFlow<PlaybackTimeline?>(null)
    override val isPlaying = MutableStateFlow(false)
    override val isBuffering = MutableStateFlow(false)
    override val currentPositionMs = MutableStateFlow(0L)
    override val totalDurationMs = MutableStateFlow(0L)
    override val playbackSpeed = MutableStateFlow(1.0f)
    override val volumeBoostDb = MutableStateFlow(0f)
    override val playbackState = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    override val playbackError = MutableStateFlow<PlaybackManager.PlaybackErrorUiState?>(null)
    override val chapters = MutableStateFlow<List<Chapter>>(emptyList())
    override val preparingBookId = MutableStateFlow<BookId?>(null)
    override val preparingBookIdUi: Flow<BookId?> = preparingBookId
    override val currentChapter = MutableStateFlow<PlaybackManager.ChapterInfo?>(null)
    override val effectiveGainDb = MutableStateFlow(0f)
    override var onChapterChanged: ((PlaybackManager.ChapterInfo) -> Unit)? = null

    override fun clearPlayback() {
        currentBookId.value = null
    }

    override fun setPlaying(playing: Boolean) {
        isPlaying.value = playing
    }

    override fun setBuffering(buffering: Boolean) {
        isBuffering.value = buffering
    }

    override fun setPlaybackState(state: PlaybackState) {
        playbackState.value = state
    }

    override fun updatePosition(positionMs: Long) {
        currentPositionMs.value = positionMs
    }

    override fun updatePositionFromMediaItem(
        mediaItemIndex: Int,
        positionInItemMs: Long,
    ) = updatePosition(positionInItemMs)

    override fun updateSpeed(speed: Float) {
        playbackSpeed.value = speed
    }

    override fun reportError(
        message: String,
        isRecoverable: Boolean,
    ) {
        playbackError.value =
            PlaybackManager.PlaybackErrorUiState(message = message, isRecoverable = isRecoverable, timestampMs = 0L)
    }

    override fun activateBook(bookId: BookId) {
        currentBookId.value = bookId
    }

    override fun markPreparing(bookId: BookId) {
        preparingBookId.value = bookId
    }

    override fun clearPreparing() {
        preparingBookId.value = null
    }

    override suspend fun prepareForPlayback(bookId: BookId): PlaybackManager.PrepareResult {
        // A real prepare is an RPC round-trip, so it always suspends. Without this the fake returns
        // inline — and because `LivePlayback.playBook` launches UNDISPATCHED, the whole
        // prepare -> activate -> load chain would run to completion *before* `playBook` returns.
        // A spec asserting on state "while the prepare is in flight" would then silently read the
        // finished state instead, which is how the no-restart spec passed with its own fix deleted.
        yield()
        prepareCount++
        when (prepare) {
            PrepareOutcome.SUCCEEDS -> Unit

            PrepareOutcome.STALLS_AFTER_FIRST -> if (prepareCount > 1) awaitCancellation()

            PrepareOutcome.THROWS -> error("prepare failed")

            // Suspends until the caller's scope is cancelled — the shape of a real RPC that is
            // still in flight when the shell unmounts.
            PrepareOutcome.NEVER_RETURNS -> awaitCancellation()
        }
        val timeline =
            PlaybackTimeline(
                bookId = bookId,
                totalDurationMs = segment.durationMs,
                files =
                    listOf(
                        PlaybackTimeline.FileSegment(
                            audioFileId = "af-1",
                            filename = "one.wav",
                            format = "wav",
                            startOffsetMs = segment.offsetMs,
                            durationMs = segment.durationMs,
                            size = 0L,
                            streamingUrl = segment.url,
                            hlsUrl = segment.hlsUrl,
                            localPath = null,
                            mediaItemIndex = 0,
                        ),
                    ),
            )
        currentTimeline.value = timeline
        totalDurationMs.value = segment.durationMs
        return PlaybackManager.PrepareResult(
            timeline = timeline,
            bookTitle = title,
            bookAuthor = "Frank Herbert",
            seriesName = null,
            coverPath = null,
            totalChapters = 1,
            resumePositionMs = 0L,
            resumeSpeed = 1.0f,
            resumeBoostDb = 0f,
            measuredGainDb = null,
            normalizationGainDb = null,
        )
    }

    /**
     * `PlaybackManagerImpl.startPlayback` in miniature: load, set speed, seek, then bridge the
     * player's flows back. Note what is NOT here — a `play()`. That absence is the production
     * behaviour under test.
     */
    override suspend fun startPlayback(
        player: AudioPlayer,
        resumePositionMs: Long,
        resumeSpeed: Float,
    ) {
        player.load(listOf(segment))
        player.setSpeed(resumeSpeed)
        if (resumePositionMs > 0) player.seekTo(resumePositionMs)
        scope.launch {
            player.state.collect { state ->
                playbackState.value = state
                isPlaying.value = state == PlaybackState.Playing
            }
        }
        scope.launch { player.positionMs.collect { currentPositionMs.value = it } }
        scope.launch { player.durationMs.collect { totalDurationMs.value = it } }
    }

    override fun onSpeedChanged(speed: Float) = updateSpeed(speed)

    override fun onSpeedReset(defaultSpeed: Float) = updateSpeed(defaultSpeed)

    override fun onVolumeBoostChanged(boostDb: Float) {
        volumeBoostDb.value = boostDb
    }

    override fun onBoostReset(defaultBoostDb: Float) {
        volumeBoostDb.value = defaultBoostDb
    }

    override fun clearError() {
        playbackError.value = null
    }
}

/**
 * A [PlaybackPreferences] that answers from constructor values and never touches storage.
 *
 * Hand-written rather than mocked: the transport specs only ever read the two skip intervals, and
 * a mock would need all eight members stubbed at every call site to avoid throwing on the ones the
 * bar reads incidentally.
 */
internal class FakePlaybackPreferences(
    private val skipForwardSec: Int = PlaybackPreferences.DEFAULT_SKIP_FORWARD_SEC,
    private val skipBackwardSec: Int = PlaybackPreferences.DEFAULT_SKIP_BACKWARD_SEC,
    private val speed: Float = PlaybackPreferences.DEFAULT_PLAYBACK_SPEED,
) : PlaybackPreferences {
    override fun observeDefaultPlaybackSpeed(): Flow<Float> = flowOf(speed)

    override suspend fun getDefaultPlaybackSpeed(): Float = speed

    override fun observeDefaultVolumeBoostDb(): Flow<Float> = flowOf(PlaybackPreferences.DEFAULT_VOLUME_BOOST_DB)

    override suspend fun getDefaultVolumeBoostDb(): Float = PlaybackPreferences.DEFAULT_VOLUME_BOOST_DB

    override fun observeDefaultSkipForwardSec(): Flow<Int> = flowOf(skipForwardSec)

    override fun observeDefaultSkipBackwardSec(): Flow<Int> = flowOf(skipBackwardSec)

    override suspend fun getDefaultSkipForwardSec(): Int = skipForwardSec

    override suspend fun getDefaultSkipBackwardSec(): Int = skipBackwardSec
}
