package com.calypsan.listenup.client.playback

import android.content.Context
import com.calypsan.listenup.client.composeapp.R
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaStyleNotificationHelper
import androidx.media3.session.SessionCommand
import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.client.localization.SystemStrings
import com.calypsan.listenup.client.localization.SystemStringsHolder
import com.calypsan.listenup.client.notifications.NotificationChannels
import kotlin.time.Duration.Companion.milliseconds
import com.google.common.collect.ImmutableList
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Custom notification provider for audiobook playback.
 *
 * Provides chapter-aware content:
 * - Smart chapter title (named chapters vs "Chapter X of Y")
 * - Time remaining in chapter
 * - Chapter skip buttons in expanded view
 * - Skip buttons in collapsed view, moving by the user's configured intervals ([skipIntervals])
 */
@OptIn(UnstableApi::class)
class AudiobookNotificationProvider(
    private val context: Context,
    private val playbackManager: PlaybackManager,
    private val skipIntervals: SkipIntervalsHolder,
    private val bookTitle: () -> CharSequence? = { null },
    private val strings: SystemStringsHolder = SystemStringsHolder(),
) : MediaNotification.Provider {
    companion object {
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = NotificationChannels.PLAYBACK

        private const val MAX_ARTWORK_CACHE = 8

        // Custom commands. Value-neutral names and action strings: the amount they move is the
        // user's synced setting (#1300), so a "_30" in the name was a lie the moment the setting
        // shipped. Nothing outside the app can depend on the literals — a custom session command
        // is only actionable by a controller we granted it to in `onConnect`, and each controller
        // receives the command inside the CommandButton it is handed.
        const val COMMAND_SKIP_BACK = "listenup.SKIP_BACK"
        const val COMMAND_SKIP_FORWARD = "listenup.SKIP_FORWARD"
        const val COMMAND_PREV_CHAPTER = "listenup.PREV_CHAPTER"
        const val COMMAND_NEXT_CHAPTER = "listenup.NEXT_CHAPTER"

        // Drawable resource IDs - loaded at runtime
        private var icNotification: Int = 0
        private var icPlay: Int = 0
        private var icPause: Int = 0

        /**
         * Get custom commands to add to the session.
         */
        fun getCustomCommands(): List<SessionCommand> =
            listOf(
                SessionCommand(COMMAND_SKIP_BACK, Bundle.EMPTY),
                SessionCommand(COMMAND_SKIP_FORWARD, Bundle.EMPTY),
                SessionCommand(COMMAND_PREV_CHAPTER, Bundle.EMPTY),
                SessionCommand(COMMAND_NEXT_CHAPTER, Bundle.EMPTY),
            )
    }

    /** Catalog snapshot for this render. Read per call, so a locale change lands on the next tick. */
    private val copy: SystemStrings get() = strings.current

    init {
        loadResourceIds()
    }

    // Static R references, NOT Resources.getIdentifier. A name-based lookup is invisible to the
    // release build's resource shrinker (`isShrinkResources = true`), which stripped these drawables
    // for want of any static reference — getIdentifier then returned 0 and every notification fell
    // back to a system icon. Debug builds never showed it, because they are not shrunk.
    private fun loadResourceIds() {
        icNotification = R.drawable.ic_notification
        icPlay = R.drawable.ic_play
        icPause = R.drawable.ic_pause
    }

    /** LRU artwork cache — Media3 re-emits createNotification on every state tick. */
    private val artworkCache =
        object : LinkedHashMap<String, android.graphics.Bitmap>(
            MAX_ARTWORK_CACHE,
            0.75f,
            true, // access-order for LRU behavior
        ) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, android.graphics.Bitmap>?) =
                size > MAX_ARTWORK_CACHE
        }

    private fun cachedArtwork(
        key: String,
        decode: () -> android.graphics.Bitmap?,
    ): android.graphics.Bitmap? =
        synchronized(artworkCache) {
            artworkCache[key] ?: decode()?.also { artworkCache[key] = it }
        }

    override fun createNotification(
        mediaSession: MediaSession,
        customLayout: ImmutableList<CommandButton>,
        actionFactory: MediaNotification.ActionFactory,
        onNotificationChangedCallback: MediaNotification.Provider.Callback,
    ): MediaNotification {
        val player = mediaSession.player
        val chapterInfo = playbackManager.currentChapter.value

        // Build notification
        val builder =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(if (icNotification != 0) icNotification else android.R.drawable.ic_media_play)
                .setOngoing(player.isPlaying)
                .setContentIntent(mediaSession.sessionActivity)
                .setDeleteIntent(
                    actionFactory.createMediaActionPendingIntent(
                        mediaSession,
                        Player.COMMAND_STOP,
                    ),
                )

        // Content: Book title and chapter info
        val metadata = player.mediaMetadata
        builder.setContentTitle(notificationTitle(bookTitle(), metadata.title))

        // Subtitle: chapter info with time remaining
        val subtitle = buildChapterSubtitle(chapterInfo)
        builder.setContentText(subtitle)

        // Cover art
        metadata.artworkUri?.let { uri ->
            val bitmap =
                cachedArtwork(uri.toString()) {
                    try {
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            android.graphics.BitmapFactory.decodeStream(stream)
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.warn(e) { "Failed to decode notification artwork from $uri" }
                        null
                    }
                }
            bitmap?.let { builder.setLargeIcon(it) }
        }

        // Actions: 5 total, compact view shows 3 (indices 1, 2, 3)
        // [0] Prev Chapter  [1] Skip back  [2] Play/Pause  [3] Skip forward  [4] Next Chapter
        val actions = mutableListOf<NotificationCompat.Action>()

        // Previous chapter
        actions.add(
            actionFactory.createCustomActionFromCustomCommandButton(
                mediaSession,
                CommandButton
                    .Builder(CommandButton.ICON_PREVIOUS)
                    .setDisplayName(copy.playerPreviousChapter)
                    .setSessionCommand(SessionCommand(COMMAND_PREV_CHAPTER, Bundle.EMPTY))
                    .build(),
            ),
        )

        // Skip back by the configured interval. Both the glyph and the spoken label carry the
        // real number: a "30" drawn on a button that moves 20 s is the app disagreeing with
        // itself in the one place the user cannot correct it.
        val backwardSec = skipIntervals.backwardSec
        actions.add(
            actionFactory.createCustomActionFromCustomCommandButton(
                mediaSession,
                CommandButton
                    .Builder(SkipCommandIcons.backward(backwardSec))
                    .setDisplayName(copy.playerSkipBackward.format(backwardSec))
                    .setSessionCommand(SessionCommand(COMMAND_SKIP_BACK, Bundle.EMPTY))
                    .build(),
            ),
        )

        // Play/Pause - use standard media action
        val playPauseIcon =
            if (player.isPlaying) {
                if (icPause != 0) icPause else android.R.drawable.ic_media_pause
            } else {
                if (icPlay != 0) icPlay else android.R.drawable.ic_media_play
            }
        val playPauseAction =
            actionFactory.createMediaAction(
                mediaSession,
                IconCompat.createWithResource(context, playPauseIcon),
                if (player.isPlaying) copy.playerPause else copy.playerPlay,
                Player.COMMAND_PLAY_PAUSE,
            )
        actions.add(playPauseAction)

        // Skip forward by the configured interval.
        val forwardSec = skipIntervals.forwardSec
        actions.add(
            actionFactory.createCustomActionFromCustomCommandButton(
                mediaSession,
                CommandButton
                    .Builder(SkipCommandIcons.forward(forwardSec))
                    .setDisplayName(copy.playerSkipForward.format(forwardSec))
                    .setSessionCommand(SessionCommand(COMMAND_SKIP_FORWARD, Bundle.EMPTY))
                    .build(),
            ),
        )

        // Next chapter
        actions.add(
            actionFactory.createCustomActionFromCustomCommandButton(
                mediaSession,
                CommandButton
                    .Builder(CommandButton.ICON_NEXT)
                    .setDisplayName(copy.playerNextChapter)
                    .setSessionCommand(SessionCommand(COMMAND_NEXT_CHAPTER, Bundle.EMPTY))
                    .build(),
            ),
        )

        actions.forEach { builder.addAction(it) }

        // MediaStyle is required for notification to appear in notification shade
        // Compact view shows actions at indices 1, 2, 3 (Skip back, Play/Pause, Skip forward)
        builder.setStyle(
            MediaStyleNotificationHelper
                .MediaStyle(mediaSession)
                .setShowActionsInCompactView(1, 2, 3),
        )

        // Set visibility and category for lock screen display
        builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        builder.setCategory(NotificationCompat.CATEGORY_TRANSPORT)

        return MediaNotification(NOTIFICATION_ID, builder.build())
    }

    override fun getNotificationChannelInfo(): MediaNotification.Provider.NotificationChannelInfo =
        MediaNotification.Provider.NotificationChannelInfo(
            CHANNEL_ID,
            NotificationChannels.PLAYBACK_NAME,
        )

    override fun handleCustomCommand(
        session: MediaSession,
        action: String,
        extras: Bundle,
    ): Boolean = false

    /**
     * Picks the notification's headline: the book, falling back to whatever the session player
     * is presenting.
     *
     * The session player is [ChapterWindowPlayer], which replaces the item title with the
     * *chapter* title so system surfaces present a chapter the way a music player presents a
     * track. Reading that here put the chapter in both lines and left the book nameless, so the
     * book title is sourced from the underlying transport player instead. The fallback keeps the
     * never-stranded property: a chapter title beats "Unknown Book".
     */
    internal fun notificationTitle(
        bookTitle: CharSequence?,
        sessionTitle: CharSequence?,
    ): String =
        bookTitle?.toString()?.takeIf { it.isNotBlank() }
            ?: sessionTitle?.toString()?.takeIf { it.isNotBlank() }
            ?: copy.playerUnknownBook

    /**
     * Build chapter subtitle with time remaining.
     *
     * Examples:
     * - Named chapter: "Chapter 14: The Chandrian • 8m left"
     * - Generic chapter: "Chapter 14 of 92 • 8m left"
     * - No chapter info: "Playing..."
     *
     * Visible for testing.
     */
    internal fun buildChapterSubtitle(chapterInfo: PlaybackManager.ChapterInfo?): String {
        if (chapterInfo == null) return copy.playerPlaying

        val chapterText =
            if (chapterInfo.isGenericTitle) {
                copy.playerChapterOf.format(chapterInfo.index + 1, chapterInfo.totalChapters)
            } else {
                chapterInfo.title
            }

        val timeRemaining = formatDuration(chapterInfo.remainingMs)
        return copy.playerChapterRemaining.format(chapterText, timeRemaining)
    }

    /**
     * Format duration in human-readable form.
     *
     * Visible for testing.
     */
    internal fun formatDuration(ms: Long): String = DurationFormatter.hoursMinutesOrUnderMinute(ms.milliseconds)
}
