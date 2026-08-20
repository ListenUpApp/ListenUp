package com.calypsan.listenup.web.playback

import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.domain.playback.PlaybackTimeline
import com.calypsan.listenup.client.playback.AudioPlayer
import com.calypsan.listenup.client.playback.PlaybackManager
import com.calypsan.listenup.client.playback.PlaybackState
import com.calypsan.listenup.core.BookId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * [WebPlaybackController.setVolume] is the one place this controller diverges from
 * [com.calypsan.listenup.client.playback.DesktopPlaybackController]'s shape — everywhere else it
 * is a line-for-line mirror. Desktop pins its divergence-free delegation with `"setVolume is a
 * no-op"`; this pins the opposite claim against the real DOM: the underlying `<audio>` element's
 * volume actually changes, clamp included.
 *
 * [neverCalledPlaybackManager] never has a member invoked — `setVolume` doesn't touch
 * [PlaybackManager] at all — so every member throws if reached, the way an unset mokkery `mock()`
 * would on desktop (mokkery has no JS-test lane wired here; this is the by-hand equivalent).
 */
class WebPlaybackControllerTest :
    FunSpec({
        test("setVolume sets the underlying element's volume") {
            val player = HtmlAudioPlayer()
            val sut = WebPlaybackController(audioPlayer = player, playbackManager = neverCalledPlaybackManager)

            sut.setVolume(HALF_VOLUME)

            player.volume shouldBe HALF_VOLUME.toDouble()
        }

        test("setVolume clamps above 1.0 to silence the element's own IndexSizeError") {
            val player = HtmlAudioPlayer()
            val sut = WebPlaybackController(audioPlayer = player, playbackManager = neverCalledPlaybackManager)

            sut.setVolume(ABOVE_MAX_VOLUME)

            player.volume shouldBe 1.0
        }

        test("setVolume clamps below 0.0") {
            val player = HtmlAudioPlayer()
            val sut = WebPlaybackController(audioPlayer = player, playbackManager = neverCalledPlaybackManager)

            sut.setVolume(BELOW_MIN_VOLUME)

            player.volume shouldBe 0.0
        }
    })

// detekt's test exclusions miss **/jsTest/** (see F6 in the web-playback plan's follow-ups), so
// MagicNumber fires here the way it would not in commonTest/jvmTest — named constants document the
// arithmetic under test rather than working around a gate config gap mid-feature-branch.
private const val HALF_VOLUME = 0.5f
private const val ABOVE_MAX_VOLUME = 1.5f
private const val BELOW_MIN_VOLUME = -0.5f

private fun unexpected(): Nothing = error("WebPlaybackControllerTest: setVolume must not touch PlaybackManager")

private val neverCalledPlaybackManager: PlaybackManager =
    object : PlaybackManager {
        override val currentBookId: StateFlow<BookId?> = MutableStateFlow(null)

        override fun clearPlayback() = unexpected()

        override fun setPlaying(playing: Boolean) = unexpected()

        override fun setBuffering(buffering: Boolean) = unexpected()

        override fun setPlaybackState(state: PlaybackState) = unexpected()

        override fun updatePosition(positionMs: Long) = unexpected()

        override fun updatePositionFromMediaItem(
            mediaItemIndex: Int,
            positionInItemMs: Long,
        ) = unexpected()

        override fun updateSpeed(speed: Float) = unexpected()

        override fun reportError(
            message: String,
            isRecoverable: Boolean,
        ) = unexpected()

        override val currentTimeline: StateFlow<PlaybackTimeline?> = MutableStateFlow(null)
        override val isPlaying: StateFlow<Boolean> = MutableStateFlow(false)
        override val isBuffering: StateFlow<Boolean> = MutableStateFlow(false)
        override val currentPositionMs: StateFlow<Long> = MutableStateFlow(0L)
        override val totalDurationMs: StateFlow<Long> = MutableStateFlow(0L)
        override val playbackSpeed: StateFlow<Float> = MutableStateFlow(1.0f)
        override val volumeBoostDb: StateFlow<Float> = MutableStateFlow(0f)
        override val playbackState: StateFlow<PlaybackState> = MutableStateFlow(PlaybackState.Idle)
        override val playbackError: StateFlow<PlaybackManager.PlaybackErrorUiState?> = MutableStateFlow(null)
        override val chapters: StateFlow<List<Chapter>> = MutableStateFlow(emptyList())
        override val preparingBookId: StateFlow<BookId?> = MutableStateFlow(null)
        override val preparingBookIdUi: Flow<BookId?> = MutableStateFlow(null)
        override val currentChapter: StateFlow<PlaybackManager.ChapterInfo?> = MutableStateFlow(null)
        override val effectiveGainDb: StateFlow<Float> = MutableStateFlow(0f)
        override var onChapterChanged: ((PlaybackManager.ChapterInfo) -> Unit)? = null

        override fun activateBook(bookId: BookId) = unexpected()

        override fun markPreparing(bookId: BookId) = unexpected()

        override fun clearPreparing() = unexpected()

        override suspend fun prepareForPlayback(bookId: BookId): PlaybackManager.PrepareResult? = unexpected()

        override suspend fun startPlayback(
            player: AudioPlayer,
            resumePositionMs: Long,
            resumeSpeed: Float,
        ) = unexpected()

        override fun onSpeedChanged(speed: Float) = unexpected()

        override fun onSpeedReset(defaultSpeed: Float) = unexpected()

        override fun onVolumeBoostChanged(boostDb: Float) = unexpected()

        override fun onBoostReset(defaultBoostDb: Float) = unexpected()

        override fun clearError() = unexpected()
    }
