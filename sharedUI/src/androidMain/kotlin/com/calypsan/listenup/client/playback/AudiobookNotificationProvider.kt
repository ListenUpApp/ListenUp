package com.calypsan.listenup.client.playback

import android.content.Context
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
import com.calypsan.listenup.client.notifications.NotificationChannels
import kotlin.time.Duration.Companion.milliseconds
import com.google.common.collect.ImmutableList
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

private const val RES_TYPE_DRAWABLE = "drawable"

/**
 * Custom notification provider for audiobook playback.
 *
 * Provides chapter-aware content:
 * - Smart chapter title (named chapters vs "Chapter X of Y")
 * - Time remaining in chapter
 * - Chapter skip buttons in expanded view
 * - 30s skip buttons in collapsed view
 */
@OptIn(UnstableApi::class)
class AudiobookNotificationProvider(
    private val context: Context,
    private val playbackManager: PlaybackManager,
) : MediaNotification.Provider {
    companion object {
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = NotificationChannels.PLAYBACK

        private const val MAX_ARTWORK_CACHE = 8

        // Custom commands
        const val COMMAND_SKIP_BACK_30 = "listenup.SKIP_BACK_30"
        const val COMMAND_SKIP_FORWARD_30 = "listenup.SKIP_FORWARD_30"
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
                SessionCommand(COMMAND_SKIP_BACK_30, Bundle.EMPTY),
                SessionCommand(COMMAND_SKIP_FORWARD_30, Bundle.EMPTY),
                SessionCommand(COMMAND_PREV_CHAPTER, Bundle.EMPTY),
                SessionCommand(COMMAND_NEXT_CHAPTER, Bundle.EMPTY),
            )
    }

    init {
        loadResourceIds()
    }

    private fun loadResourceIds() {
        val resources = context.resources
        val packageName = context.packageName
        icNotification = resources.getIdentifier("ic_notification", RES_TYPE_DRAWABLE, packageName)
        icPlay = resources.getIdentifier("ic_play", RES_TYPE_DRAWABLE, packageName)
        icPause = resources.getIdentifier("ic_pause", RES_TYPE_DRAWABLE, packageName)
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
        builder.setContentTitle(metadata.title ?: "Unknown Book")

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
        // [0] Prev Chapter  [1] Skip -30s  [2] Play/Pause  [3] Skip +30s  [4] Next Chapter
        val actions = mutableListOf<NotificationCompat.Action>()

        // Previous chapter
        actions.add(
            actionFactory.createCustomActionFromCustomCommandButton(
                mediaSession,
                CommandButton
                    .Builder(CommandButton.ICON_PREVIOUS)
                    .setDisplayName("Previous chapter")
                    .setSessionCommand(SessionCommand(COMMAND_PREV_CHAPTER, Bundle.EMPTY))
                    .build(),
            ),
        )

        // Skip back 30s
        actions.add(
            actionFactory.createCustomActionFromCustomCommandButton(
                mediaSession,
                CommandButton
                    .Builder(CommandButton.ICON_REWIND)
                    .setDisplayName("Skip back 30 seconds")
                    .setSessionCommand(SessionCommand(COMMAND_SKIP_BACK_30, Bundle.EMPTY))
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
                if (player.isPlaying) "Pause" else "Play",
                Player.COMMAND_PLAY_PAUSE,
            )
        actions.add(playPauseAction)

        // Skip forward 30s
        actions.add(
            actionFactory.createCustomActionFromCustomCommandButton(
                mediaSession,
                CommandButton
                    .Builder(CommandButton.ICON_FAST_FORWARD)
                    .setDisplayName("Skip forward 30 seconds")
                    .setSessionCommand(SessionCommand(COMMAND_SKIP_FORWARD_30, Bundle.EMPTY))
                    .build(),
            ),
        )

        // Next chapter
        actions.add(
            actionFactory.createCustomActionFromCustomCommandButton(
                mediaSession,
                CommandButton
                    .Builder(CommandButton.ICON_NEXT)
                    .setDisplayName("Next chapter")
                    .setSessionCommand(SessionCommand(COMMAND_NEXT_CHAPTER, Bundle.EMPTY))
                    .build(),
            ),
        )

        actions.forEach { builder.addAction(it) }

        // MediaStyle is required for notification to appear in notification shade
        // Compact view shows actions at indices 1, 2, 3 (Skip -30s, Play/Pause, Skip +30s)
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
        if (chapterInfo == null) return "Playing..."

        val chapterText =
            if (chapterInfo.isGenericTitle) {
                "Chapter ${chapterInfo.index + 1} of ${chapterInfo.totalChapters}"
            } else {
                chapterInfo.title
            }

        val timeRemaining = formatDuration(chapterInfo.remainingMs)
        return "$chapterText • $timeRemaining left"
    }

    /**
     * Format duration in human-readable form.
     *
     * Visible for testing.
     */
    internal fun formatDuration(ms: Long): String = DurationFormatter.hoursMinutesOrUnderMinute(ms.milliseconds)
}
