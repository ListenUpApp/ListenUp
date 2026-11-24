package com.calypsan.listenup.client.playback

import android.content.Context
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import com.calypsan.listenup.client.localization.SystemStringsHolder
import com.calypsan.listenup.client.playback.cast.CastMediaItemFactory
import com.calypsan.listenup.client.playback.cast.CastPreparer
import com.calypsan.listenup.client.playback.cast.CastSessionController
import com.calypsan.listenup.client.playback.cast.CastSourceItem
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Orchestrates live Local ↔ Cast player swaps on [MediaLibrarySession] without disturbing
 * the retained local queue or losing the listening span across the hand-off.
 */
@OptIn(UnstableApi::class)
internal class CastHandoffCoordinator(
    private val context: Context,
    private val playbackManager: PlaybackManager,
    private val castPreparer: CastPreparer,
    private val transport: ActiveTransport,
    private val systemStrings: SystemStringsHolder,
    private val castPlaybackListener: Player.Listener,
    private val onEndListen: () -> Unit,
    private val onCancelIdleTimer: () -> Unit,
    private val onSaveCurrentPosition: () -> Unit,
) {
    /** Local → Cast: re-fetch network URLs, build the cast queue at the current position, swap the session player. */
    suspend fun handoffToCast(
        controller: CastSessionController,
        session: MediaLibrarySession,
        local: ExoPlayer,
    ) {
        val bookId = playbackManager.currentBookId.value ?: return

        val prepared = castPreparer.prepareForCast(bookId)
        if (prepared == null) {
            logger.warn { "Cast prepare failed — staying local" }
            // Honest-over-silent: the user tapped cast and nothing audible happened.
            Toast.makeText(context, systemStrings.current.playerCastStartFailed, Toast.LENGTH_LONG).show()
            return
        }
        // Connect→disconnect race: the session can drop while prepareForCast suspends. Re-check
        // before committing so we never swap onto a dead cast session with no path back.
        if (!controller.isSessionAvailable) {
            logger.warn { "Cast session gone during prepare — staying local" }
            // Honest-over-silent, matching the siblings above/below: the user tapped cast and
            // audio just keeps playing locally with no explanation otherwise.
            Toast.makeText(context, systemStrings.current.playerCastSessionLost, Toast.LENGTH_LONG).show()
            return
        }
        // Snapshot the currently-loaded items for queue order + on-TV metadata.
        val sourceItems =
            (0 until local.mediaItemCount).map { i ->
                val mi = local.getMediaItemAt(i)
                CastSourceItem(
                    fileId = mi.mediaId,
                    title = mi.mediaMetadata.title?.toString(),
                    artist = mi.mediaMetadata.artist?.toString(),
                    albumTitle = mi.mediaMetadata.albumTitle?.toString(),
                )
            }
        val factory = CastMediaItemFactory()
        val built = factory.build(sourceItems, prepared.files, prepared.coverUrlAbsolute)
        // Index-shift safety: only cast a complete, in-order queue. A dropped file would
        // shift indices and resume on the wrong file — fall back to local instead.
        if (built.tracks.size != sourceItems.size) {
            logger.warn {
                "Cannot cast this book (uncastable=${built.droppedUncastable}, " +
                    "unmatched=${built.droppedUnmatched}) — staying local"
            }
            // Honest-over-silent: tell the user why casting didn't start. Staying local is the
            // never-stranded fallback. (A typed AppError + snackbar is a tracked follow-up; a
            // Toast is the v1 honest-over-silent signal.)
            Toast.makeText(context, systemStrings.current.playerCastUnsupportedFormat, Toast.LENGTH_LONG).show()
            return
        }
        val mediaItems = built.tracks.map { factory.toMediaItem(it) }
        val wasPlaying = local.playWhenReady
        val speed = local.playbackParameters.speed
        val index = local.currentMediaItemIndex
        val positionInItem = local.currentPosition

        // Commit to the swap. Set `casting` BEFORE pausing local, because pausing fires the
        // local PlayerListener's onIsPlayingChanged(false) → startIdleTimer, which must stand
        // down while casting (the cast player has no listener to ever cancel it). While casting
        // the local player is paused, so periodic progress saves + listening-event recording
        // pause too; the position is still persisted on disconnect via the cast-aware
        // getBookRelativePosition(). (Tracked follow-up: drive the position job off
        // session.player so cast time counts toward stats.)
        // The local listen ends here, read off the local player while it is still the transport;
        // the cast player's own play opens the next span.
        transport.handOff(TransportSource.CAST, onEndListen)
        val cast = controller.castPlayer
        // The cast player drives bookkeeping for as long as it is the transport. Without this
        // listener a cast session begun from a paused state recorded nothing at all: the local
        // player never entered isPlaying, so nothing ever started the position loops or opened
        // a span. Attached before prepare() so its first is-playing event is not missed.
        cast.addListener(castPlaybackListener)
        cast.setMediaItems(mediaItems, index, positionInItem)
        cast.playWhenReady = wasPlaying
        cast.prepare()
        cast.setPlaybackSpeed(speed) // routes via SpeedAwareCastPlayer → RemoteMediaClient.setPlaybackRate
        session.player = cast
        local.playWhenReady = false // release local audio focus; keep its items loaded for fast return
        onCancelIdleTimer() // belt-and-suspenders: the `casting` guard already blocks new timers
        logger.info { "Swapped to Cast at index=$index pos=$positionInItem" }
    }

    /** Cast → Local: read cast position, seek the retained ExoPlayer there, swap back, persist progress. */
    fun handoffToLocal(
        controller: CastSessionController,
        session: MediaLibrarySession,
        local: ExoPlayer,
        wrapper: ChapterWindowPlayer,
    ) {
        // If the session isn't currently on the cast player, nothing to do (we never swapped).
        // (Was an identity check against `local` before ChapterWindowPlayer existed — the session
        // player is never `local` anymore even when not casting, since it's now the wrapper.)
        if (!transport.casting) return
        val cast = controller.castPlayer
        val index = cast.currentMediaItemIndex
        val positionInItem = cast.currentPosition
        val wasPlaying = cast.playWhenReady
        // SpeedAwareCastPlayer mirrors the live receiver rate, so a speed change made while casting
        // is carried back to the local player — otherwise saveCurrentPosition() below persists a
        // stale speed.
        val speed = cast.playbackParameters.speed
        // Known v1 limitation: the retained local player still holds the book that was loaded
        // at hand-off. If the user changed books on the cast device mid-session, swapping back
        // could seek the wrong book. (Tracked follow-up.)
        cast.removeListener(castPlaybackListener)
        local.seekTo(index, positionInItem)
        local.playWhenReady = wasPlaying
        local.setPlaybackSpeed(speed)
        session.player = wrapper
        // The cast listen ends here, read off the cast player while it is still the transport: its own
        // stop can no longer arrive (its listener is gone), and the local player's play opens the next
        // span. Normal idle logic resumes now that the local player drives the session.
        transport.handOff(TransportSource.LOCAL, onEndListen)
        logger.info { "Swapped back to local at index=$index pos=$positionInItem" }
        onSaveCurrentPosition() // existing method — persists through the normal recorder
    }
}
