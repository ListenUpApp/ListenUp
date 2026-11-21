package com.calypsan.listenup.client.download

import android.app.Notification

/** What the download notification says. The words and the drawing are the app module's ([DownloadNotifications]). */
sealed interface DownloadNotice {
    /** The book the notice is about. */
    val bookId: String

    /** Its title. */
    val title: String

    /**
     * A book downloading: [downloadedBytes] of [totalBytes], on file [fileNumber] of [fileCount], with [queuedBehind]
     * more books queued after it. [resolving] while no bytes are moving yet (the progress is indeterminate).
     */
    data class Active(
        override val bookId: String,
        override val title: String,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val fileNumber: Int,
        val fileCount: Int,
        val queuedBehind: Int,
        val resolving: Boolean,
    ) : DownloadNotice

    /** A book that is not running, and why: paused by the person from the notification, or waiting to resume. */
    data class Held(
        override val bookId: String,
        override val title: String,
        val kind: HeldKind,
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : DownloadNotice

    /** Why a held download is not running. */
    enum class HeldKind {
        /** Paused from the notification: Resume and Cancel. */
        PAUSED_BY_YOU,

        /** Wi-Fi only is on and the device is off Wi-Fi: Cancel. */
        WAITING_FOR_WIFI,

        /** The system stopped it and it resumes by itself: Cancel. */
        WAITING_TO_RESUME,
    }
}

/**
 * The download notification on its own channel, apart from playback. Implemented by the app module, which owns the
 * channel, the strings and the actions; the transports only say what to show.
 *
 * One notification at a time: the running download owns [activeNotificationId] (posted by its job or foreground
 * worker, and removed by the platform when it stops), and a download that is not running shows on a held one, which
 * the next run's turn replaces.
 */
interface DownloadNotifications {
    /** The id every running download's notification shares, so books queued behind it never add a second one. */
    val activeNotificationId: Int

    /** The notification a running download shows; its host posts it. */
    fun active(notice: DownloadNotice.Active): Notification

    /** Post [notification] on [activeNotificationId] directly, for a worker the platform would not promote. */
    fun postActive(notification: Notification)

    /** Remove the active notification a run posted directly. */
    fun cancelActive()

    /** Show a download that is not running. Replaces any held notification already showing. */
    fun showHeld(notice: DownloadNotice.Held)

    /** Remove the held notification. */
    fun clearHeld()
}
