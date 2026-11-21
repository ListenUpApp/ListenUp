package com.calypsan.listenup.client.download

import android.content.Context
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.work.WorkManager

/** What a book run is scheduled with: the book, its title for the first notification, the network rule and size. */
internal data class BookTransferRequest(
    val bookId: String,
    val title: String,
    val wifiOnly: Boolean,
    val remainingBytes: Long,
)

/** Whether the platform took a schedule. */
internal enum class ScheduleResult {
    SCHEDULED,

    /** The platform refused it; on Android 14+ that means the app was not visible when it asked. */
    REFUSED,
}

/**
 * Schedules book runs on one Android transport. The only place download work is handed to the platform:
 * [UidtBookTransferScheduler] on Android 14+, [WorkManagerBookTransferScheduler] below it ([bookTransferSchedulerFor]).
 */
internal interface BookTransferScheduler {
    /** The transport this scheduler hands runs to. */
    val transport: DownloadTransport

    /**
     * Schedule [request]'s run. [replace] replaces a run already scheduled or running (a network-rule change);
     * otherwise a run already scheduled is left as it is.
     */
    suspend fun schedule(
        request: BookTransferRequest,
        replace: Boolean,
    ): ScheduleResult

    /** Cancel [bookId]'s scheduled or running run. */
    suspend fun cancel(bookId: String)

    /** Whether [bookId] has a run scheduled with the platform (waiting, or running). */
    suspend fun isScheduled(bookId: String): Boolean
}

/** The scheduler for this device: a user-initiated data transfer job from Android 14, WorkManager below it. */
internal fun bookTransferSchedulerFor(context: Context): BookTransferScheduler =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        UidtBookTransferScheduler(context)
    } else {
        WorkManagerBookTransferScheduler(WorkManager.getInstance(context))
    }

/**
 * The network a download needs: any network with internet, or an unmetered one when Wi-Fi only is on. Never
 * `VALIDATED` — a self-hosted server on a LAN with no route to the internet must still qualify, and the system's own
 * "any network" default requires validation. VPNs qualify, so a server reached over one is reachable here too.
 */
internal fun downloadNetworkRequest(wifiOnly: Boolean): NetworkRequest =
    NetworkRequest
        .Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .removeCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        .apply { if (wifiOnly) addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) }
        .build()
