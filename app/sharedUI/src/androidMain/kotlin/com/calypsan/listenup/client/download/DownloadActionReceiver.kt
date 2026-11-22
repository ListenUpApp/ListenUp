package com.calypsan.listenup.client.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.calypsan.listenup.core.BookId
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.qualifier.named

private val logger = KotlinLogging.logger {}

/** What the download notification's buttons do. */
interface DownloadNotificationControls {
    /** Pause [bookId], keeping what it saved; the notification turns into "Download paused". */
    suspend fun pause(bookId: BookId)

    /** Cancel [bookId] and discard what it saved; the queue moves up. */
    suspend fun cancel(bookId: BookId)
}

/** Does what [action], one of [DownloadActionReceiver]'s actions, asks for [bookId]. Unknown actions do nothing. */
internal suspend fun handleDownloadAction(
    action: String?,
    bookId: String?,
    controls: DownloadNotificationControls,
) {
    val book = bookId?.let(::BookId) ?: return
    when (action) {
        DownloadActionReceiver.ACTION_PAUSE -> controls.pause(book)
        DownloadActionReceiver.ACTION_CANCEL -> controls.cancel(book)
    }
}

/**
 * Receives Pause and Cancel from the download notification. Cancel never asks: the notification has nowhere to ask,
 * and it discards only the book it names. Resume is not here: it opens the app, where a download can be scheduled.
 *
 * Not exported (see the manifest): these intents come from our own [android.app.PendingIntent] and nowhere else. The
 * work runs on the app scope under [goAsync], since stopping a run and updating the rows suspend.
 */
class DownloadActionReceiver :
    BroadcastReceiver(),
    KoinComponent {
    private val controls: DownloadNotificationControls by inject()
    private val scope: CoroutineScope by inject(named("appScope"))

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val pending = goAsync()
        val action = intent.action
        val bookId = intent.getStringExtra(EXTRA_BOOK_ID)
        logger.info { "download: notification action=${action ?: "-"} book=${bookId ?: "-"}" }
        scope.launch {
            try {
                handleDownloadAction(action, bookId, controls)
            } finally {
                pending.finish()
            }
        }
    }

    /** The notification's actions. */
    companion object {
        /** Pause the book. */
        const val ACTION_PAUSE = "com.calypsan.listenup.action.DOWNLOAD_PAUSE"

        /** Cancel the book. */
        const val ACTION_CANCEL = "com.calypsan.listenup.action.DOWNLOAD_CANCEL"

        /** The book the action is for. */
        const val EXTRA_BOOK_ID = "book_id"
    }
}
