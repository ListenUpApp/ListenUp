package com.calypsan.listenup.client.playback

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.domain.playback.PlaybackTimeline
import com.calypsan.listenup.client.localization.SystemStrings
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** Stable UID for the single [SimpleBasePlayer.MediaItemData] every reported [SimpleBasePlayer.State] carries. */
private const val CHAPTER_WINDOW_MEDIA_ITEM_UID = "chapter-window"

/**
 * Snapshot of everything [ChapterWindowPlayer.getState] and [ChapterWindowPlayer.handleSeek]
 * both need: the chapters, the underlying player's book-relative position, the [ChapterWindow]
 * derived from it, and the [PlaybackTimeline] used to resolve book positions back to
 * player coordinates. Computed once per call by `currentChapterContext()` so the two
 * override points never drift on how they read the underlying player.
 */
private data class ChapterSeekContext(
    val chapters: List<Chapter>,
    val bookPositionMs: Long,
    val window: ChapterWindow,
    val timeline: PlaybackTimeline,
)

/**
 * Re-presents the wrapped local ExoPlayer as a single-window timeline scoped to the current
 * chapter, so every system playback surface (Android Auto now-playing, the phone notification,
 * the lock screen, Bluetooth/AVRCP, Wear) shows elapsed/remaining time and a seek bar for the
 * *chapter*, not the whole book or the underlying audio file — the same way a music player
 * shows the current track, not the whole album. In-app UI still reads `PlaybackManager`
 * directly for its state (fed by `PlaybackService`'s own poll of the raw transport player, not
 * a controller of this wrapper), and in-app seeks ride the `SEEK_TO_BOOK_POSITION` custom
 * session command rather than a plain controller seek — a controller-side seek through this
 * wrapper is reinterpreted chapter-relatively and clamped to the current chapter window, which
 * cannot express a book position. Plain play/pause/speed controller calls are coordinate-agnostic
 * and pass through this wrapper unmodified.
 *
 * This is handed to `MediaLibrarySession.Builder` in place of the raw local player
 * (see `PlaybackService.initializeMediaSession`). All chapter-window math is pure and lives in
 * [ChapterWindow.kt][ChapterWindow] — this class is a thin [ForwardingSimpleBasePlayer] adapter
 * over it, following the same split as [ControllerTrust]/[controllerTrustOf] in
 * `ControllerGating.kt`.
 *
 * **Previous/next chapter mapping.** `Player.seekToPrevious()`/`seekToNext()` normally bypass
 * chapter logic entirely — chapter skip today only exists as custom session commands. Because
 * this wrapper always reports a single-item playlist (the current chapter's window), Media3's
 * `BasePlayer.seekToPrevious()`/`seekToNext()` always route through [handleSeek] with
 * `COMMAND_SEEK_TO_PREVIOUS`/`COMMAND_SEEK_TO_NEXT` (there is never a previous/next *media item*
 * to hop to), which is exactly what car head-unit prev/next buttons invoke. Where they land is the
 * shared [ChapterNavigation] rule (spec §3.3), the same one the app, iOS and the web use: "previous"
 * restarts the current chapter once it has run for more than three seconds, otherwise it moves to the
 * previous chapter's start, and chapter 1 restarts. `COMMAND_SEEK_TO_PREVIOUS` (and its `_MEDIA_ITEM`
 * variant) is therefore *always* advertised via [getState]: "previous" is never greyed out, it is at
 * least a restart. `COMMAND_SEEK_TO_NEXT` (and its `_MEDIA_ITEM` variant) is gated on
 * [ChapterNavigation.hasNext]: at the last chapter there is nothing to skip to, so the button greys
 * out there, and a next that arrives anyway does nothing.
 *
 * **Metadata.** The title stays the book's; the artist is the chapter and the time left in it
 * ("Bridge Four · 8m left", [systemTrackLine]), which Android 13+ draws as the card's second line;
 * the album is the author (spec §5.7).
 *
 * **Invalidation.** [ForwardingSimpleBasePlayer] already invalidates state automatically on
 * every underlying-player event (play/pause, buffering, ExoPlayer-driven position
 * discontinuities). Two cases it doesn't cover are crossed purely by elapsed playback time: a
 * chapter boundary, and the minute the card's "8m left" counts down. [invalidate] is the public
 * hook for both: the service calls it on chapter rollover and once a minute while playing, so
 * connected controllers pick up the new chapter and time left.
 *
 * **Chapterless / single-chapter books.** When [chaptersProvider] returns an empty list,
 * [currentChapterWindow] presents the whole book as one window — no special-casing is visible to
 * controllers; they simply see a single "chapter" spanning the whole book.
 *
 * **Testing.** `SimpleBasePlayer`'s constructor calls `player.getApplicationLooper()` and builds a
 * real `Handler` from it immediately, so a Mokkery `mock<Player>()` cannot construct this class —
 * stubbing that call throws `RuntimeException: Method getMainLooper in android.os.Looper not
 * mocked`. That much is still true, and is why this class is not built from a mock the way
 * `SpeedAwareCastPlayer` (`playback/cast/SpeedAwareCastPlayer.kt`) is. It does NOT mean the class
 * is untestable here: `androidHostTest` runs Robolectric, under which a hand-written fake returning
 * `Looper.getMainLooper()` satisfies the constructor. `ChapterWindowPlayerSeekTest` drives this
 * adapter's seek dispatch that way. (An earlier revision of this KDoc called the lane
 * "Robolectric-free" and sent this layer to device-only verification; that was wrong, and the gap
 * it left is where the #1237/#1241 auto-rewind regression hid.) The pure chapter-window
 * calculations live in [ChapterWindow.kt][ChapterWindow], covered by `ChapterWindowTest`.
 *
 * @param player The local ExoPlayer instance to wrap.
 * @param chaptersProvider Synchronous snapshot of the current book's chapters (empty when the
 *   book has none). The service passes `playbackManager.chapters.value`.
 * @param timelineProvider Synchronous snapshot of the file/offset timeline for the currently
 *   playing book, or null before playback has been prepared. The service passes
 *   `playbackManager.currentTimeline.value`.
 * @param strings The current catalog snapshot, for the card's second line.
 */
@OptIn(UnstableApi::class)
class ChapterWindowPlayer(
    player: Player,
    private val chaptersProvider: () -> List<Chapter>,
    private val timelineProvider: () -> PlaybackTimeline?,
    private val strings: () -> SystemStrings,
) : ForwardingSimpleBasePlayer(player) {
    /**
     * Forces every connected controller to re-read [getState] and observe a position
     * discontinuity. See the "Invalidation" section of the class KDoc for when this is needed
     * beyond [ForwardingSimpleBasePlayer]'s automatic invalidation.
     */
    fun invalidate() {
        invalidateState()
    }

    override fun getState(): State {
        val baseState = super.getState()
        val context = currentChapterContext() ?: return baseState

        return baseState
            .buildUpon()
            .setPlaylist(
                listOf(
                    chapterMediaItemData(
                        window = context.window,
                        chapters = context.chapters,
                        underlyingPlayer = player,
                    ),
                ),
            ).setCurrentMediaItemIndex(0)
            .setContentPositionMs(windowPositionSupplier(context.window))
            .setAvailableCommands(
                baseState.availableCommands.withChapterSeekCommands(context.chapters, context.bookPositionMs),
            ).build()
    }

    /**
     * Live [SimpleBasePlayer.PositionSupplier] for [window]'s position, re-derived from the
     * underlying player on every call rather than stamped once at [getState]-build time.
     *
     * `setContentPositionMs(Long)` freezes the position at the moment [getState] runs. That's
     * fine for a Media3 controller, which re-polls [getState] on its own cadence and gets a fresh
     * stamp each time — but an in-process reader holding onto a [State] between
     * [ForwardingSimpleBasePlayer]'s automatic invalidations (see the class KDoc's "Invalidation"
     * section) would see the position frozen at whatever it was when that [State] was built. A
     * supplier fixes that the same way [SimpleBasePlayer.PositionSupplier.getExtrapolating] does
     * for a raw player position, and matches [SimpleBasePlayer]'s own idiom for live-advancing
     * positions (`setContentPositionMs(PositionSupplier)`).
     */
    private fun windowPositionSupplier(window: ChapterWindow): SimpleBasePlayer.PositionSupplier =
        SimpleBasePlayer.PositionSupplier {
            val timeline = timelineProvider()
            if (timeline == null) {
                // Timeline gone (playback torn down) — fall back to the position captured when
                // this window was computed rather than crashing or returning a stale zero.
                window.positionInWindowMs
            } else {
                val liveBookPositionMs = timeline.toBookPosition(player.currentMediaItemIndex, player.currentPosition)
                (liveBookPositionMs - window.windowStartMs).coerceIn(0L, window.windowDurationMs)
            }
        }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        if (seekCommand == Player.COMMAND_SEEK_BACK || seekCommand == Player.COMMAND_SEEK_FORWARD) {
            // Skip-back/forward increments operate on the real player position directly —
            // they aren't chapter-relative, so the default handling is already correct.
            return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
        }
        val context = currentChapterContext() ?: return super.handleSeek(mediaItemIndex, positionMs, seekCommand)

        val targetBookPositionMs =
            when (seekCommand) {
                Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> {
                    ChapterNavigation.previousStartMs(context.bookPositionMs, context.chapters)
                }

                Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> {
                    ChapterNavigation.nextStartMs(context.bookPositionMs, context.chapters)
                        ?: return Futures.immediateVoidFuture()
                }

                Player.COMMAND_SEEK_TO_DEFAULT_POSITION -> {
                    context.window.windowStartMs
                }

                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_MEDIA_ITEM -> {
                    context.window.seekTargetToBookPosition(positionMs)
                }

                else -> {
                    return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
                }
            }

        val resolved = context.timeline.resolve(targetBookPositionMs)
        player.seekTo(resolved.mediaItemIndex, resolved.positionInFileMs)
        return Futures.immediateVoidFuture()
    }

    /**
     * Reads the underlying player's current book position and derives the [ChapterWindow] it
     * falls in — the single computation [getState] and [handleSeek] both build on. Returns null
     * before playback has been prepared (no [PlaybackTimeline] yet), in which case callers fall
     * back to default `ForwardingSimpleBasePlayer`/`SimpleBasePlayer` handling.
     */
    private fun currentChapterContext(): ChapterSeekContext? {
        val timeline = timelineProvider() ?: return null
        val chapters = chaptersProvider()
        val bookPositionMs = timeline.toBookPosition(player.currentMediaItemIndex, player.currentPosition)
        val window = currentChapterWindow(chapters, bookPositionMs, timeline.totalDurationMs)
        return ChapterSeekContext(
            chapters = chapters,
            bookPositionMs = bookPositionMs,
            window = window,
            timeline = timeline,
        )
    }

    /** Builds the single [SimpleBasePlayer.MediaItemData] representing [window]. */
    private fun chapterMediaItemData(
        window: ChapterWindow,
        chapters: List<Chapter>,
        underlyingPlayer: Player,
    ): SimpleBasePlayer.MediaItemData {
        // Title stays the book, as the media item names it. Android 13+ draws the card's second line from the
        // artist, so the chapter and its time left go there (spec §5.7), and the author moves to the album.
        val book = underlyingPlayer.mediaMetadata
        val metadataBuilder =
            book
                .buildUpon()
                .setArtist(systemTrackLine(window.chapterName(chapters), window.remainingMs, strings()))
                .setAlbumTitle(book.artist)
        if (window.chapterIndex >= 0) {
            metadataBuilder.setTrackNumber(window.chapterIndex + 1)
            metadataBuilder.setTotalTrackCount(chapters.size)
        }

        return SimpleBasePlayer.MediaItemData
            .Builder(CHAPTER_WINDOW_MEDIA_ITEM_UID)
            .setMediaItem(underlyingPlayer.currentMediaItem ?: MediaItem.EMPTY)
            .setMediaMetadata(metadataBuilder.build())
            .setDurationUs(window.windowDurationMs * 1_000L)
            .setIsSeekable(true)
            .build()
    }

    /** Adds/removes the chapter-aware seek commands so a controller reflects [window]'s edges. */
    private fun Player.Commands.withChapterSeekCommands(
        chapters: List<Chapter>,
        bookPositionMs: Long,
    ): Player.Commands {
        val hasNext = ChapterNavigation.hasNext(bookPositionMs, chapters)
        return buildUpon()
            .add(Player.COMMAND_SEEK_TO_PREVIOUS)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .addIf(Player.COMMAND_SEEK_TO_NEXT, hasNext)
            .removeIf(Player.COMMAND_SEEK_TO_NEXT, !hasNext)
            .addIf(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, hasNext)
            .removeIf(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, !hasNext)
            .build()
    }
}
