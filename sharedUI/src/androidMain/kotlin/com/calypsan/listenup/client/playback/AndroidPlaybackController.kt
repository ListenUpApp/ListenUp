package com.calypsan.listenup.client.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.session.MediaController
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.StateFlow

private val logger = KotlinLogging.logger {}

/**
 * Minimal abstraction over [MediaControllerHolder] consumed by [AndroidPlaybackController].
 *
 * Exists solely to make [AndroidPlaybackController] testable in androidHostTest
 * without requiring a real Android [android.content.Context]. [MediaControllerHolder]
 * satisfies this interface directly.
 */
interface ControllerHolder {
    fun acquire()

    fun release()

    val isConnected: StateFlow<Boolean>
    val controller: MediaController?
}

/**
 * Android implementation of [PlaybackController]. Wraps [ControllerHolder] (backed by
 * [MediaControllerHolder] in production) + Media3 [MediaController].
 *
 * Command methods route through `holder.controller?.X()` — if the controller is
 * null (not yet connected, or disconnected), the command is silently dropped
 * with a warn-log. This matches the pre-Phase-E1 VM behavior of
 * `mediaController ?: return`. Throwing would leak Media3 service-lifecycle
 * quirks into VM error handling.
 */
class AndroidPlaybackController(
    private val holder: ControllerHolder,
) : PlaybackController {
    private var cachedQueue: List<PlaybackMediaItem> = emptyList()

    override fun acquire() = holder.acquire()

    override fun release() = holder.release()

    override val isReady: StateFlow<Boolean> = holder.isConnected

    override fun play() {
        holder.controller?.play()
            ?: logger.warn { "AndroidPlaybackController.play: controller not ready" }
    }

    override fun pause() {
        holder.controller?.pause()
            ?: logger.warn { "AndroidPlaybackController.pause: controller not ready" }
    }

    override fun seekTo(positionMs: Long) {
        val controller = holder.controller
        if (controller == null) {
            logger.warn { "AndroidPlaybackController.seekTo($positionMs): controller not ready" }
            return
        }
        if (cachedQueue.isEmpty()) {
            logger.warn {
                "AndroidPlaybackController.seekTo($positionMs): no cached queue, falling back to single-arg seek"
            }
            controller.seekTo(positionMs)
            return
        }
        val (index, offset) = resolveQueuePosition(cachedQueue, positionMs)
        logger.debug { "AndroidPlaybackController.seekTo: bookPos=$positionMs → idx=$index, offset=$offset" }
        controller.seekTo(index, offset)
    }

    /**
     * Resolves a book-relative position (ms) to a `(itemIndex, offsetWithinItem)` pair
     * suitable for Media3 `seekTo(windowIndex, positionMs)` and `setMediaItems(..., startIndex, positionMs)`.
     *
     * - Empty list → `(0, 0)`
     * - Position within an item → `(itemIndex, bookPositionMs - item.offsetMs)`
     * - Before first item → `(0, 0)`
     * - Past last item → `(lastIndex, lastItem.durationMs)` (Drift #26 fix: uses item duration, not controller duration)
     */
    internal fun resolveQueuePosition(
        items: List<PlaybackMediaItem>,
        bookPositionMs: Long,
    ): Pair<Int, Long> {
        if (items.isEmpty()) return 0 to 0L
        for ((i, item) in items.withIndex()) {
            if (bookPositionMs in item.offsetMs until item.offsetMs + item.durationMs) {
                return i to bookPositionMs - item.offsetMs
            }
        }
        // Position before first item OR past last item
        val first = items.first()
        return if (bookPositionMs < first.offsetMs) {
            0 to 0L
        } else {
            // Past end → snap to last item's end
            // Drift #26 (a) fix: use LAST item's durationMs, not controller.duration
            val lastIndex = items.size - 1
            lastIndex to items.last().durationMs
        }
    }

    override fun setPlaybackSpeed(speed: Float) {
        holder.controller?.setPlaybackParameters(PlaybackParameters(speed))
            ?: logger.warn { "AndroidPlaybackController.setPlaybackSpeed($speed): controller not ready" }
    }

    override fun stop() {
        holder.controller?.stop()
            ?: logger.warn { "AndroidPlaybackController.stop: controller not ready" }
    }

    override fun setVolume(volume: Float) {
        holder.controller?.let { it.volume = volume }
            ?: logger.warn { "AndroidPlaybackController.setVolume($volume): controller not ready" }
    }

    override suspend fun setMediaQueue(
        items: List<PlaybackMediaItem>,
        startPositionMs: Long,
    ) {
        val controller = holder.controller
        if (controller == null) {
            logger.warn { "AndroidPlaybackController.setMediaQueue: controller not ready" }
            return
        }
        cachedQueue = items
        val mediaItems =
            items.map { item ->
                MediaItem
                    .Builder()
                    .setMediaId(item.mediaId)
                    .setUri(item.uri)
                    .setMediaMetadata(
                        MediaMetadata
                            .Builder()
                            .setTitle(item.title)
                            .setArtist(item.artist)
                            .setAlbumTitle(item.albumTitle)
                            .setArtworkUri(item.artworkUri?.let { Uri.parse(it) })
                            .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
                            .build(),
                    ).build()
            }
        val (startIndex, positionInItem) = resolveQueuePosition(items, startPositionMs)
        controller.setMediaItems(mediaItems, startIndex, positionInItem)
        controller.prepare()
    }

    override suspend fun startPlayback(prepareResult: PlaybackManager.PrepareResult) {
        val items =
            prepareResult.timeline.files.map { file ->
                PlaybackMediaItem(
                    mediaId = file.audioFileId,
                    uri = file.playbackUri,
                    localPath = file.localPath,
                    durationMs = file.durationMs,
                    offsetMs = file.startOffsetMs,
                    title = prepareResult.bookTitle,
                    artist = prepareResult.bookAuthor,
                    albumTitle = prepareResult.seriesName,
                    artworkUri = prepareResult.coverPath?.let { "file://$it" },
                )
            }
        setMediaQueue(items, prepareResult.resumePositionMs)
        setPlaybackSpeed(prepareResult.resumeSpeed)
        play()
    }
}

/**
 * Adapter so that [MediaControllerHolder] satisfies [ControllerHolder] without modification.
 */
fun MediaControllerHolder.asControllerHolder(): ControllerHolder =
    object : ControllerHolder {
        override fun acquire() = this@asControllerHolder.acquire()

        override fun release() = this@asControllerHolder.release()

        override val isConnected: StateFlow<Boolean> = this@asControllerHolder.isConnected
        override val controller: MediaController? get() = this@asControllerHolder.controller
    }
