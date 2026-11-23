package com.calypsan.listenup.client.download

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.calypsan.listenup.api.result.onSuccess
import com.calypsan.listenup.client.MainActivity
import com.calypsan.listenup.client.core.suspendRunCatching
import com.calypsan.listenup.client.composeapp.R
import com.calypsan.listenup.client.notifications.NotificationChannels
import com.calypsan.listenup.client.presentation.bookdetail.byteSizeLabel
import com.calypsan.listenup.client.share.ShareLinkCodec
import com.calypsan.listenup.client.share.ShareTarget
import com.calypsan.listenup.core.BookId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Notification ids: beside the media notification (1), the playback refusal (2) and the sleep timer (3). Every running
 * download shares [DOWNLOAD_ACTIVE_NOTIFICATION_ID]; a download that is not running shows on
 * [DOWNLOAD_HELD_NOTIFICATION_ID], so the platform removing a job's notification never takes the held one with it.
 */
internal const val DOWNLOAD_ACTIVE_NOTIFICATION_ID = 4
internal const val DOWNLOAD_HELD_NOTIFICATION_ID = 5

private const val PROGRESS_MAX = 1_000

/**
 * The download notification as the approved design draws it: "Downloading *title*", "212 MB of 640 MB · file 3 of 14"
 * (no file count for a one-file book), "2 more queued" as the header line when books wait behind it, determinate
 * progress (indeterminate while the file's address resolves), and Pause and Cancel. A held download reads
 * "Download paused" (Resume, Cancel), "Waiting for Wi-Fi" or "Waiting to continue" (Cancel).
 *
 * Silent and low priority on its own [NotificationChannels.DOWNLOADS] channel, never through playback. Public on the
 * lock screen, like the media card: it names only the book. A tap opens the book. Denied notifications are a silent
 * no-op; the download runs regardless, and the stop history records that its progress was hidden.
 */
class AndroidDownloadNotifications(
    private val context: Context,
    scope: CoroutineScope,
) : DownloadNotifications {
    @Volatile
    private var words: DownloadNotificationStrings = DownloadNotificationStrings.ENGLISH

    init {
        // English serves until the catalog loads; a catalog that cannot load leaves it serving.
        scope.launch {
            suspendRunCatching { loadDownloadNotificationStrings() }.onSuccess { words = it }
        }
    }

    private val manager: NotificationManager?
        get() = context.getSystemService(NotificationManager::class.java)

    override val activeNotificationId: Int = DOWNLOAD_ACTIVE_NOTIFICATION_ID

    override fun active(notice: DownloadNotice.Active): Notification {
        val w = words
        val progress =
            if (notice.fileCount > 1) {
                w.progressFiles.format(
                    Locale.getDefault(),
                    byteSizeLabel(notice.downloadedBytes),
                    byteSizeLabel(notice.totalBytes),
                    notice.fileNumber,
                    notice.fileCount,
                )
            } else {
                w.progress.format(
                    Locale.getDefault(),
                    byteSizeLabel(notice.downloadedBytes),
                    byteSizeLabel(notice.totalBytes),
                )
            }
        val builder =
            base(notice.bookId)
                .setContentTitle(w.downloading.format(Locale.getDefault(), notice.title))
                .setOngoing(true)
                .setProgress(PROGRESS_MAX, permille(notice.downloadedBytes, notice.totalBytes), notice.resolving)
                .addAction(action(notice.bookId, DownloadActionReceiver.ACTION_PAUSE, w.pause))
                .addAction(action(notice.bookId, DownloadActionReceiver.ACTION_CANCEL, w.cancel))
        if (notice.totalBytes > 0) {
            builder.setContentText(progress)
            // Up to Android 11 a progress notification sets its text on the title's line, where "Downloading <title>"
            // leaves it no room. The big-text template gives the line its own row below the title, above the bar.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                builder.setStyle(NotificationCompat.BigTextStyle().bigText(progress))
            }
        }
        if (notice.queuedBehind > 0) builder.setSubText(w.queued.format(Locale.getDefault(), notice.queuedBehind))
        return builder.build()
    }

    override fun postActive(notification: Notification) {
        manager?.notify(DOWNLOAD_ACTIVE_NOTIFICATION_ID, notification)
    }

    override fun cancelActive() {
        manager?.cancel(DOWNLOAD_ACTIVE_NOTIFICATION_ID)
    }

    override fun showHeld(notice: DownloadNotice.Held) {
        manager?.notify(DOWNLOAD_HELD_NOTIFICATION_ID, held(notice))
    }

    override fun clearHeld() {
        manager?.cancel(DOWNLOAD_HELD_NOTIFICATION_ID)
    }

    /** The notification for a download that is not running. Dismissible: nothing is running behind it. */
    internal fun held(notice: DownloadNotice.Held): Notification {
        val w = words
        val locale = Locale.getDefault()
        val builder =
            base(notice.bookId)
                .setOngoing(false)
                .setProgress(PROGRESS_MAX, permille(notice.downloadedBytes, notice.totalBytes), false)
        when (notice.kind) {
            DownloadNotice.HeldKind.PAUSED_BY_YOU -> {
                builder
                    .setContentTitle(w.pausedTitle)
                    .setContentText(
                        w.pausedText.format(
                            locale,
                            notice.title,
                            byteSizeLabel(notice.downloadedBytes),
                            byteSizeLabel(notice.totalBytes),
                        ),
                    ).addAction(resumeAction(notice.bookId, w.resume))
            }

            DownloadNotice.HeldKind.WAITING_FOR_WIFI -> {
                builder.setContentTitle(w.wifiTitle).setContentText(w.wifiText.format(locale, notice.title))
            }

            DownloadNotice.HeldKind.WAITING_TO_RESUME -> {
                builder.setContentTitle(w.waitingTitle).setContentText(w.waitingText.format(locale, notice.title))
            }
        }
        builder.addAction(action(notice.bookId, DownloadActionReceiver.ACTION_CANCEL, w.cancel))
        return builder.build()
    }

    private fun base(bookId: String): NotificationCompat.Builder =
        NotificationCompat
            .Builder(context, NotificationChannels.DOWNLOADS)
            // Static R reference — see AudiobookNotificationProvider.loadResourceIds.
            .setSmallIcon(R.drawable.ic_notification)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openBook(bookId))

    /** Opens the book's screen through the share-link route every book link already takes. */
    private fun openBook(bookId: String): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode(bookId, OPEN),
            bookIntent(bookId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /**
     * Resume opens the app on the book and resumes there: from Android 14 a download job can only be scheduled while
     * the app is visible, and a broadcast from the shade does not make it so.
     */
    private fun resumeAction(
        bookId: String,
        label: String,
    ): NotificationCompat.Action =
        NotificationCompat.Action
            .Builder(
                0,
                label,
                PendingIntent.getActivity(
                    context,
                    requestCode(bookId, RESUME),
                    bookIntent(bookId).putExtra(MainActivity.EXTRA_RESUME_DOWNLOAD_BOOK_ID, bookId),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            ).build()

    private fun action(
        bookId: String,
        actionName: String,
        label: String,
    ): NotificationCompat.Action =
        NotificationCompat.Action
            .Builder(
                0,
                label,
                PendingIntent.getBroadcast(
                    context,
                    requestCode(bookId, actionName.hashCode()),
                    Intent(context, DownloadActionReceiver::class.java)
                        .setAction(actionName)
                        .putExtra(DownloadActionReceiver.EXTRA_BOOK_ID, bookId),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            ).build()

    private fun bookIntent(bookId: String): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse(ShareLinkCodec.encode(ShareTarget.Book(BookId(bookId), null, null))))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    private fun requestCode(
        bookId: String,
        salt: Int,
    ): Int = REQUEST_CODE_PRIME * bookId.hashCode() + salt

    private companion object {
        const val OPEN = 1
        const val RESUME = 2

        /** Spreads a book's request codes apart, so its open, resume and action intents never share one. */
        const val REQUEST_CODE_PRIME = 31
    }
}

private fun permille(
    done: Long,
    total: Long,
): Int = if (total > 0) (done * PROGRESS_MAX / total).toInt().coerceIn(0, PROGRESS_MAX) else 0
