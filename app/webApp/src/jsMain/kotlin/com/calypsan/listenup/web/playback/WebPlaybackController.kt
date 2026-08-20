package com.calypsan.listenup.web.playback

import com.calypsan.listenup.client.playback.AudioSegment
import com.calypsan.listenup.client.playback.PlaybackController
import com.calypsan.listenup.client.playback.PlaybackManager
import com.calypsan.listenup.client.playback.PlaybackMediaItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Browser implementation of [PlaybackController]. Wraps the shared [HtmlAudioPlayer], the same
 * way [com.calypsan.listenup.client.playback.DesktopPlaybackController] wraps `FfmpegAudioPlayer`
 * — both are eagerly-ready `AudioPlayer` instances with no service-lifecycle, so `acquire`/
 * `releasePlayer` are no-ops and `isReady` is a constant `true`.
 *
 * Depends on the concrete [HtmlAudioPlayer] rather than the shared `AudioPlayer` interface,
 * specifically so [setVolume] can reach [HtmlAudioPlayer.setVolume] — the one behavior that is
 * NOT shared with Desktop, because an `HTMLMediaElement` actually exposes a volume control.
 *
 * Note: `releasePlayer` here does NOT call [HtmlAudioPlayer.releasePlayer]; that would tear down
 * the player, which is fine per its own KDoc (not terminal — `load()` revives it) but is not this
 * method's job, matching the Desktop controller's contract.
 */
internal class WebPlaybackController(
    private val audioPlayer: HtmlAudioPlayer,
    private val playbackManager: PlaybackManager,
) : PlaybackController {
    override val isReady: StateFlow<Boolean>
        field = MutableStateFlow(true)

    override fun acquire() = Unit

    override fun releasePlayer() = Unit

    override fun play() = audioPlayer.play()

    override fun pause() = audioPlayer.pause()

    override fun seekTo(positionMs: Long) = audioPlayer.seekTo(positionMs)

    override fun setPlaybackSpeed(speed: Float) = audioPlayer.setSpeed(speed)

    override fun stop() {
        audioPlayer.pause()
        audioPlayer.seekTo(0L)
    }

    override fun setVolume(volume: Float) = audioPlayer.setVolume(volume)

    override suspend fun setMediaQueue(
        items: List<PlaybackMediaItem>,
        startPositionMs: Long,
    ) {
        val segments =
            items.map { item ->
                AudioSegment(
                    url = item.uri,
                    hlsUrl = null, // set by PlaybackManagerImpl's own path; the queue path is direct-only
                    localPath = item.localPath,
                    durationMs = item.durationMs,
                    offsetMs = item.offsetMs,
                )
            }
        audioPlayer.load(segments)
        if (startPositionMs > 0L) {
            audioPlayer.seekTo(startPositionMs)
        }
    }

    override suspend fun startPlayback(prepareResult: PlaybackManager.PrepareResult) {
        playbackManager.startPlayback(
            player = audioPlayer,
            resumePositionMs = prepareResult.resumePositionMs,
            resumeSpeed = prepareResult.resumeSpeed,
        )
    }
}
