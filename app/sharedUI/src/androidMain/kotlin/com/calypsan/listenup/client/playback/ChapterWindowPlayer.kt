package com.calypsan.listenup.client.playback

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.domain.playback.PlaybackTimeline
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** Stable UID for the single [SimpleBasePlayer.MediaItemData] every reported [SimpleBasePlayer.State] carries. */
private const val CHAPTER_WINDOW_MEDIA_ITEM_UID = "chapter-window"

/** Restart-current-chapter threshold for the "previous" command — see [ChapterWindowPlayer] KDoc. */
private const val PREVIOUS_RESTART_THRESHOLD_MS = 3_000L

/**
 * Re-presents the wrapped local ExoPlayer as a single-window timeline scoped to the current
 * chapter, so every system playback surface (Android Auto now-playing, the phone notification,
 * the lock screen, Bluetooth/AVRCP, Wear) shows elapsed/remaining time and a seek bar for the
 * *chapter*, not the whole book or the underlying audio file — the same way a music player
 * shows the current track, not the whole album. In-app UI is unaffected; it reads
 * `PlaybackManager` directly and never touches this wrapper.
 *
 * This is handed to `MediaLibrarySession.Builder` in place of the raw local player
 * (`PlaybackService`, in a follow-up change). All chapter-window math is pure and lives in
 * [ChapterWindow.kt][ChapterWindow] — this class is a thin [ForwardingSimpleBasePlayer] adapter
 * over it, following the same split as [ControllerTrust]/[controllerTrustOf] in
 * `ControllerGating.kt`.
 *
 * **Previous/next chapter mapping.** `Player.seekToPrevious()`/`seekToNext()` normally bypass
 * chapter logic entirely — chapter skip today only exists as custom session commands. Because
 * this wrapper always reports a single-item playlist (the current chapter's window), Media3's
 * `BasePlayer.seekToPrevious()`/`seekToNext()` always route through [handleSeek] with
 * `COMMAND_SEEK_TO_PREVIOUS`/`COMMAND_SEEK_TO_NEXT` (there is never a previous/next *media item*
 * to hop to), which is exactly what car head-unit prev/next buttons invoke. "Previous" restarts
 * the current chapter when more than [PREVIOUS_RESTART_THRESHOLD_MS] into it — standard
 * media-player behavior — otherwise it moves to the previous chapter's start (or, at the first
 * chapter / in a chapterless book, clamps to the window's own start — a harmless restart).
 * `COMMAND_SEEK_TO_PREVIOUS` (and its `_MEDIA_ITEM` variant) is therefore *always* advertised via
 * [getState] — [hasPreviousChapter] — matching standard Media3/media-player UX where "previous"
 * is never greyed out; it's always at least a restart affordance. `COMMAND_SEEK_TO_NEXT` (and its
 * `_MEDIA_ITEM` variant) stays gated on [hasNextChapter]: past the last chapter there is genuinely
 * nothing to skip to, so the button greys out there.
 *
 * **Invalidation.** [ForwardingSimpleBasePlayer] already invalidates state automatically on
 * every underlying-player event (play/pause, buffering, ExoPlayer-driven position
 * discontinuities). The one case that doesn't cover is a chapter boundary crossed purely by
 * elapsed playback time — no underlying-player event fires for that. [invalidate] is the public
 * hook for it: the service collects `PlaybackManager.currentChapter` and calls [invalidate] on
 * chapter rollover so connected controllers pick up the new chapter title/duration together with
 * a position discontinuity.
 *
 * **Chapterless / single-chapter books.** When [chaptersProvider] returns an empty list,
 * [currentChapterWindow] presents the whole book as one window — no special-casing is visible to
 * controllers; they simply see a single "chapter" spanning the whole book.
 *
 * **No androidHostTest for this class.** Unlike `SpeedAwareCastPlayer`
 * (`playback/cast/SpeedAwareCastPlayer.kt`), which can be constructed with a bare
 * `mock<Player>()`, this class can't be instantiated at all in this Robolectric-free lane:
 * `SimpleBasePlayer`'s constructor calls `player.getApplicationLooper()` and builds a real
 * `Handler` from it immediately, and even stubbing that call with Mokkery to return
 * `Looper.getMainLooper()` throws `RuntimeException: Method getMainLooper in android.os.Looper
 * not mocked` (verified empirically) — there is no way to hand it a working `Looper` without
 * Robolectric. Every chapter-window calculation this class depends on lives in
 * [ChapterWindow.kt][ChapterWindow] and is fully covered by `ChapterWindowTest`; this adapter
 * layer (state/metadata assembly, command gating, seek dispatch) is exercised on a real device
 * instead.
 *
 * @param player The local ExoPlayer instance to wrap.
 * @param chaptersProvider Synchronous snapshot of the current book's chapters (empty when the
 *   book has none). The service passes `playbackManager.chapters.value`.
 * @param timelineProvider Synchronous snapshot of the file/offset timeline for the currently
 *   playing book, or null before playback has been prepared. The service passes
 *   `playbackManager.currentTimeline.value`.
 */
@OptIn(UnstableApi::class)
class ChapterWindowPlayer(
    player: Player,
    private val chaptersProvider: () -> List<Chapter>,
    private val timelineProvider: () -> PlaybackTimeline?,
) : ForwardingSimpleBasePlayer(player) {
    /**
     * Forces every connected controller to re-read [getState] and observe a position
     * discontinuity. See the "Invalidation" section of the class KDoc for when this is needed
     * beyond [ForwardingSimpleBasePlayer]'s automatic invalidation.
     */
    fun invalidate() = invalidateState()

    override fun getState(): State {
        val baseState = super.getState()
        val timeline = timelineProvider() ?: return baseState
        val chapters = chaptersProvider()
        val underlyingPlayer = player

        val bookPositionMs =
            timeline.toBookPosition(
                underlyingPlayer.currentMediaItemIndex,
                underlyingPlayer.currentPosition,
            )
        val window = currentChapterWindow(chapters, bookPositionMs, timeline.totalDurationMs)

        return baseState
            .buildUpon()
            .setPlaylist(listOf(chapterMediaItemData(baseState, window, chapters, underlyingPlayer)))
            .setCurrentMediaItemIndex(0)
            .setContentPositionMs(window.positionInWindowMs)
            .setAvailableCommands(baseState.availableCommands.withChapterSeekCommands(chapters, window))
            .build()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        val timeline = timelineProvider() ?: return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
        if (seekCommand == Player.COMMAND_SEEK_BACK || seekCommand == Player.COMMAND_SEEK_FORWARD) {
            // Skip-back/forward increments operate on the real player position directly —
            // they aren't chapter-relative, so the default handling is already correct.
            return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
        }

        val chapters = chaptersProvider()
        val currentBookPositionMs = timeline.toBookPosition(player.currentMediaItemIndex, player.currentPosition)
        val window = currentChapterWindow(chapters, currentBookPositionMs, timeline.totalDurationMs)

        val targetBookPositionMs =
            when (seekCommand) {
                Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> {
                    previousChapterTarget(
                        chapters,
                        currentBookPositionMs,
                        timeline.totalDurationMs,
                        PREVIOUS_RESTART_THRESHOLD_MS,
                    )
                }

                Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> {
                    nextChapterTarget(chapters, currentBookPositionMs, timeline.totalDurationMs)
                }

                Player.COMMAND_SEEK_TO_DEFAULT_POSITION -> {
                    window.windowStartMs
                }

                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_MEDIA_ITEM -> {
                    window.seekTargetToBookPosition(positionMs)
                }

                else -> {
                    return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
                }
            }

        val resolved = timeline.resolve(targetBookPositionMs)
        player.seekTo(resolved.mediaItemIndex, resolved.positionInFileMs)
        return Futures.immediateVoidFuture()
    }

    /** Builds the single [SimpleBasePlayer.MediaItemData] representing [window]. */
    private fun chapterMediaItemData(
        baseState: State,
        window: ChapterWindow,
        chapters: List<Chapter>,
        underlyingPlayer: Player,
    ): SimpleBasePlayer.MediaItemData {
        val chapterTitle = chapters.getOrNull(window.chapterIndex)?.title
        val metadataBuilder = baseState.currentMetadata.buildUpon()
        if (chapterTitle != null) {
            metadataBuilder.setTitle(chapterTitle)
        }
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
        window: ChapterWindow,
    ): Player.Commands {
        val hasPrevious = hasPreviousChapter()
        val hasNext = hasNextChapter(chapters, window)
        return buildUpon()
            .addIf(Player.COMMAND_SEEK_TO_PREVIOUS, hasPrevious)
            .removeIf(Player.COMMAND_SEEK_TO_PREVIOUS, !hasPrevious)
            .addIf(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, hasPrevious)
            .removeIf(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, !hasPrevious)
            .addIf(Player.COMMAND_SEEK_TO_NEXT, hasNext)
            .removeIf(Player.COMMAND_SEEK_TO_NEXT, !hasNext)
            .addIf(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, hasNext)
            .removeIf(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, !hasNext)
            .build()
    }
}
