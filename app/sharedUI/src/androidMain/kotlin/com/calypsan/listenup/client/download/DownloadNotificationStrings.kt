package com.calypsan.listenup.client.download

import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.downloads_notification_cancel
import listenup.composeapp.generated.resources.downloads_notification_downloading
import listenup.composeapp.generated.resources.downloads_notification_pause
import listenup.composeapp.generated.resources.downloads_notification_paused_text
import listenup.composeapp.generated.resources.downloads_notification_paused_title
import listenup.composeapp.generated.resources.downloads_notification_progress
import listenup.composeapp.generated.resources.downloads_notification_progress_files
import listenup.composeapp.generated.resources.downloads_notification_queued
import listenup.composeapp.generated.resources.downloads_notification_resume
import listenup.composeapp.generated.resources.downloads_notification_waiting_text
import listenup.composeapp.generated.resources.downloads_notification_waiting_title
import listenup.composeapp.generated.resources.downloads_notification_wifi_text
import listenup.composeapp.generated.resources.downloads_notification_wifi_title
import org.jetbrains.compose.resources.getString

/**
 * The download notification's words, resolved from the catalog once into a snapshot, for the same reason as
 * [com.calypsan.listenup.client.localization.SystemStrings]: a job service posts its notification from a synchronous
 * callback that cannot suspend. [ENGLISH] serves until the first [loadDownloadNotificationStrings] lands.
 *
 * Format strings stay format strings; the notification fills them in.
 */
data class DownloadNotificationStrings(
    /** Title while a book downloads, `%1$s` its title. */
    val downloading: String,
    /** "212 MB of 640 MB": `%1$s` saved, `%2$s` total. */
    val progress: String,
    /** "212 MB of 640 MB · file 3 of 14". */
    val progressFiles: String,
    /** Header line naming the queue, `%1$d` books behind. */
    val queued: String,
    /** Title of a download paused from the notification. */
    val pausedTitle: String,
    /** "Project Hail Mary · 212 MB of 640 MB". */
    val pausedText: String,
    /** Title while Wi-Fi only holds a download back. */
    val wifiTitle: String,
    /** "Project Hail Mary · downloads use Wi-Fi only". */
    val wifiText: String,
    /** Title while a download waits on the system. */
    val waitingTitle: String,
    /** "Project Hail Mary · resumes automatically". */
    val waitingText: String,
    /** Action: pause. */
    val pause: String,
    /** Action: cancel. */
    val cancel: String,
    /** Action: resume. */
    val resume: String,
) {
    /** The pre-load snapshot, kept identical to `en.json`. */
    companion object {
        /** English, as the catalog has it. */
        val ENGLISH =
            DownloadNotificationStrings(
                downloading = "Downloading %1\$s",
                progress = "%1\$s of %2\$s",
                progressFiles = "%1\$s of %2\$s · file %3\$d of %4\$d",
                queued = "%1\$d more queued",
                pausedTitle = "Download paused",
                pausedText = "%1\$s · %2\$s of %3\$s",
                wifiTitle = "Waiting for Wi-Fi",
                wifiText = "%1\$s · downloads use Wi-Fi only",
                waitingTitle = "Waiting to continue",
                waitingText = "%1\$s · resumes automatically",
                pause = "Pause",
                cancel = "Cancel",
                resume = "Resume",
            )
    }
}

/** Every [DownloadNotificationStrings] value from the catalog, in the current locale. */
suspend fun loadDownloadNotificationStrings(): DownloadNotificationStrings =
    DownloadNotificationStrings(
        downloading = getString(Res.string.downloads_notification_downloading),
        progress = getString(Res.string.downloads_notification_progress),
        progressFiles = getString(Res.string.downloads_notification_progress_files),
        queued = getString(Res.string.downloads_notification_queued),
        pausedTitle = getString(Res.string.downloads_notification_paused_title),
        pausedText = getString(Res.string.downloads_notification_paused_text),
        wifiTitle = getString(Res.string.downloads_notification_wifi_title),
        wifiText = getString(Res.string.downloads_notification_wifi_text),
        waitingTitle = getString(Res.string.downloads_notification_waiting_title),
        waitingText = getString(Res.string.downloads_notification_waiting_text),
        pause = getString(Res.string.downloads_notification_pause),
        cancel = getString(Res.string.downloads_notification_cancel),
        resume = getString(Res.string.downloads_notification_resume),
    )
