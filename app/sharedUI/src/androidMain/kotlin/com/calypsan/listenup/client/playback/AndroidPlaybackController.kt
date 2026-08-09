package com.calypsan.listenup.client.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.session.MediaController
import com.calypsan.listenup.client.automotive.CoverUri
import com.calypsan.listenup.client.automotive.CustomActions
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
    private val packageName: String,
) : PlaybackController {
    private var cachedQueue: List<PlaybackMediaItem> = emptyList()

    override fun acquire() = holder.acquire()

    override fun releasePlayer() = holder.release()

    override val isReady: StateFlow<Boolean> = holder.isConnected

    override fun play() {
        val controller =
            holder.controller
                ?: return logger.warn { "AndroidPlaybackController.play: controller not ready" }

        // An idle player — left that way by a terminal error, or by the recovery stop/prepare
        // cycle — ignores play() entirely. See needsPrepareBeforePlay.
        if (needsPrepareBeforePlay(controller.playbackState)) {
            logger.info { "AndroidPlaybackController.play: player idle, re-preparing before play" }
            controller.prepare()
        }
        controller.play()
    }

    override fun pause() {
        holder.controller?.pause()
            ?: logger.warn { "AndroidPlaybackController.pause: controller not ready" }
    }

    /**
     * Seeks to a book-relative position by round-tripping it through the session as a custom
     * command, rather than resolving it to a `(index, offset)` pair and calling
     * `controller.seekTo(index, offset)` directly.
     *
     * The session player is [ChapterWindowPlayer], the chapter-scoped presentation wrapper (see
     * its class KDoc) — a plain controller-side seek is interpreted by the wrapper as
     * chapter-relative and clamped to the current chapter window, so a cross-chapter book-relative
     * seek would silently land in the wrong place. [CustomActions.SEEK_TO_BOOK_POSITION] carries
     * the book-relative target as-is; [PlaybackService.onCustomCommand] resolves it against the
     * raw transport player, bypassing the wrapper's chapter-relative reinterpretation entirely.
     */
    override fun seekTo(positionMs: Long) {
        val controller = holder.controller
        if (controller == null) {
            logger.warn { "AndroidPlaybackController.seekTo($positionMs): controller not ready" }
            return
        }
        logger.debug { "AndroidPlaybackController.seekTo: bookPos=$positionMs via SEEK_TO_BOOK_POSITION" }
        controller.sendCustomCommand(
            CustomActions.seekToBookPositionCommand(),
            CustomActions.seekToBookPositionArgs(positionMs),
        )
    }

    /**
     * Resolves a book-relative position (ms) to a `(itemIndex, offsetWithinItem)` pair suitable
     * for Media3 `setMediaItems(..., startIndex, positionMs)`. (Book-relative seeks no longer use
     * this — see [seekTo]'s KDoc — this is now [setMediaQueue]'s sole caller.)
     *
     * - Empty list → `(0, 0)`
     * - Position within an item → `(itemIndex, bookPositionMs - item.offsetMs)`
     * - Before first item → `(0, 0)`
     * - Past last item → `(lastIndex, lastItem.durationMs)` (uses item duration, not controller duration)
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
            // Use LAST item's durationMs, not controller.duration
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
        val items = buildMediaItems(prepareResult)
        setMediaQueue(items, prepareResult.resumePositionMs)
        setPlaybackSpeed(prepareResult.resumeSpeed)
        play()
    }

    /**
     * Maps a [PlaybackManager.PrepareResult] onto the queue items Media3 consumes.
     *
     * `internal` and pure so it can be tested without a [MediaController], which is a final
     * Android class that cannot be instantiated in host tests — the same reason
     * [resolveQueuePosition] is exposed this way.
     *
     * Artwork is addressed by book ID through `CoverContentProvider`, never by local path.
     * Android Auto rejects `file://` artwork URIs outright, so `prepareResult.coverPath` is
     * deliberately not used here.
     */
    internal fun buildMediaItems(prepareResult: PlaybackManager.PrepareResult): List<PlaybackMediaItem> {
        val artworkUri = CoverUri.forBook(packageName, prepareResult.timeline.bookId.value).toString()
        return prepareResult.timeline.files.map { file ->
            PlaybackMediaItem(
                mediaId = file.audioFileId,
                uri = file.playbackUri,
                localPath = file.localPath,
                durationMs = file.durationMs,
                offsetMs = file.startOffsetMs,
                title = prepareResult.bookTitle,
                artist = prepareResult.bookAuthor,
                albumTitle = prepareResult.seriesName,
                artworkUri = artworkUri,
            )
        }
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
