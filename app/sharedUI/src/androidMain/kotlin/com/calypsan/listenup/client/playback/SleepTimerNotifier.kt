package com.calypsan.listenup.client.playback

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import com.calypsan.listenup.client.composeapp.R
import com.calypsan.listenup.client.localization.SystemStrings
import com.calypsan.listenup.client.localization.SystemStringsHolder
import com.calypsan.listenup.client.notifications.NotificationChannels
import com.calypsan.listenup.client.presentation.player.ChapterName
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.Date
import java.util.Locale

private val logger = KotlinLogging.logger {}

/** Notification id for the sleep timer, beside the media notification (1) and the refusal notice (2). */
internal const val SLEEP_NOTIFICATION_ID = 3

/**
 * What the sleep notification shows (spec §5.7, placement decision 9). Only what the shade draws differently, so a
 * timer's per-second ticks collapse to one value and the notification is posted on a change, never every second.
 */
internal sealed interface SleepNotice {
    /** No timer: no notification. */
    data object Off : SleepNotice

    /** A duration timer, pausing at [deadlineMs] (wall clock). The system draws the countdown to it. */
    data class Counting(
        val deadlineMs: Long,
    ) : SleepNotice

    /** An end-of-chapter timer, pausing at the end of [chapter]; null when there is no chapter to name. */
    data class EndOfChapter(
        val chapter: ChapterName?,
    ) : SleepNotice

    /** The timer fired and the audio is fading out. */
    data object Fading : SleepNotice
}

/** The notice for [state], with [chapter] the current chapter's name. Pure. */
internal fun sleepNoticeOf(
    state: SleepTimerState,
    chapter: ChapterName?,
): SleepNotice =
    when (state) {
        SleepTimerState.Inactive -> {
            SleepNotice.Off
        }

        SleepTimerState.FadingOut -> {
            SleepNotice.Fading
        }

        is SleepTimerState.Active -> {
            when (state.mode) {
                is SleepTimerMode.Duration -> SleepNotice.Counting(deadlineMs = state.startedAt + state.totalMs)
                SleepTimerMode.EndOfChapter -> SleepNotice.EndOfChapter(chapter)
            }
        }
    }

/**
 * Shows a running sleep timer in the notification shade and on the lock screen, with its actions (spec §5.7):
 * a duration timer counts down and offers Add 10 min · Cancel; an end-of-chapter timer names the chapter and offers
 * Cancel; the fade offers Keep listening. The countdown is the system's own chronometer, so nothing re-posts per
 * second.
 *
 * Application-scoped rather than the playback service's: the service stops on its idle timeout, and a timer a
 * listener set must stay visible and cancellable until it ends. Denied notifications are a silent no-op, as for
 * [PlaybackRefusalNotifier].
 *
 * An ongoing notification outlives a killed process, so the countdown carries a timeout: the system removes it once
 * the deadline and the fade are past, even with nobody left to cancel it.
 */
class SleepTimerNotifier(
    private val context: Context,
    private val sleepTimer: Flow<SleepTimerState>,
    private val chapter: Flow<ChapterName?>,
    private val strings: SystemStringsHolder,
    private val scope: CoroutineScope,
    /** Wall-clock milliseconds; the countdown's timeout is measured from it. */
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val notificationManager: NotificationManager?
        get() = context.getSystemService(NotificationManager::class.java)

    /** Follows the timer for the scope's lifetime. */
    fun start() {
        scope.launch {
            combine(sleepTimer, chapter, ::sleepNoticeOf)
                .distinctUntilChanged()
                .collect(::show)
        }
    }

    /** Posts, updates or clears the notification for [notice]. */
    internal fun show(notice: SleepNotice) {
        val manager = notificationManager ?: return
        if (notice == SleepNotice.Off) {
            manager.cancel(SLEEP_NOTIFICATION_ID)
            return
        }
        manager.notify(SLEEP_NOTIFICATION_ID, build(notice, strings.current))
        logger.debug { "Sleep notification: $notice" }
    }

    private fun build(
        notice: SleepNotice,
        words: SystemStrings,
    ): Notification {
        val builder =
            NotificationCompat
                .Builder(context, NotificationChannels.PLAYBACK)
                // Static R reference — see AudiobookNotificationProvider.loadResourceIds.
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(words.sleepTitle)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                // It names at most a chapter, as the media card on the same lock screen already does.
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
        launchIntent()?.let(builder::setContentIntent)
        when (notice) {
            is SleepNotice.Counting -> {
                builder
                    .setContentText(words.sleepPausesAt.format(Locale.getDefault(), clockTime(notice.deadlineMs)))
                    .setWhen(notice.deadlineMs)
                    .setShowWhen(true)
                    .setUsesChronometer(true)
                    .setChronometerCountDown(true)
                    .setTimeoutAfter((notice.deadlineMs - nowMs()).coerceAtLeast(0) + SLEEP_FADE_DURATION_MS)
                    .addAction(action(SleepTimerActionReceiver.ACTION_ADD_TEN, words.sleepAddTen))
                    .addAction(action(SleepTimerActionReceiver.ACTION_CANCEL, words.sleepCancel))
            }

            is SleepNotice.EndOfChapter -> {
                builder
                    .setContentText(endOfChapterText(notice.chapter, words))
                    .addAction(action(SleepTimerActionReceiver.ACTION_CANCEL, words.sleepCancel))
            }

            SleepNotice.Fading -> {
                builder
                    .setContentText(words.sleepFading)
                    .addAction(action(SleepTimerActionReceiver.ACTION_KEEP_LISTENING, words.sleepKeepListening))
            }

            // Never built: show() cancels instead.
            SleepNotice.Off -> {
                return builder.build()
            }
        }
        return builder.build()
    }

    private fun endOfChapterText(
        chapter: ChapterName?,
        words: SystemStrings,
    ): String {
        val name =
            when (chapter) {
                is ChapterName.Titled -> chapter.title
                is ChapterName.Untitled -> words.playerMiniChapter.format(Locale.getDefault(), chapter.number)
                ChapterName.WholeBook, null -> return words.sleepEndOfChapter
            }
        return words.sleepPausesAtEndOf.format(Locale.getDefault(), name)
    }

    /** The deadline in the device's own clock format, as the sleep sheet's "Pauses at" says it. */
    private fun clockTime(epochMs: Long): String = DateFormat.getTimeFormat(context).format(Date(epochMs))

    private fun action(
        intentAction: String,
        title: String,
    ): NotificationCompat.Action {
        val intent = Intent(context, SleepTimerActionReceiver::class.java).setAction(intentAction)
        val pending =
            PendingIntent.getBroadcast(
                context,
                intentAction.hashCode(),
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return NotificationCompat.Action.Builder(null, title, pending).build()
    }

    private fun launchIntent(): PendingIntent? =
        context.packageManager?.getLaunchIntentForPackage(context.packageName)?.let { intent ->
            PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
}
