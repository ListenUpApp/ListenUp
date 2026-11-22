package com.calypsan.listenup.client.download

/** Why a run stopped, as the platform said it: the mapped [reason] and the platform's raw [code], when it gave one. */
internal data class StopCause(
    val reason: DownloadStopReason,
    val code: Int? = null,
)

/**
 * What the device looked like when a download stopped. Every field is null where the platform cannot say; it is
 * recorded beside the stop so a doctor can tell "the system was rationing work" from "ListenUp was killed".
 */
internal data class DownloadDeviceSnapshot(
    val sdkInt: Int? = null,
    val standbyBucket: Int? = null,
    val powerSave: Boolean? = null,
    val deviceIdle: Boolean? = null,
    val ignoringBatteryOptimizations: Boolean? = null,
    val notificationsEnabled: Boolean? = null,
    val wifiOnly: Boolean? = null,
    val networkMetered: Boolean? = null,
)

/** Reads the device's state for a stop record. */
internal fun interface DownloadDeviceState {
    /** The device as it is now. */
    fun snapshot(): DownloadDeviceSnapshot
}

/** One stop of a book's download, as recorded on the row and in the stop history. */
internal data class DownloadStop(
    val at: Long,
    val bookId: String,
    val audioFileId: String?,
    val cause: StopCause,
    val bytesAtStop: Long,
    val totalBytes: Long,
    val transport: DownloadTransport?,
    val foreground: Boolean?,
    val device: DownloadDeviceSnapshot,
) {
    /**
     * The grep-friendly line every stop logs: `download: stop book=… file=… reason=QUOTA code=10 …`. It carries ids,
     * counts and device state only; never a URL, since a signed download URL carries a signature.
     */
    fun logLine(): String =
        buildString {
            append("download: stop book=").append(bookId)
            append(" file=").append(audioFileId ?: "-")
            append(" reason=").append(cause.reason.name)
            append(" code=").append(cause.code?.toString() ?: "-")
            append(" transport=").append(transport?.name ?: "-")
            append(" fg=").append(foreground?.toString() ?: "-")
            append(
                " bytes=",
            ).append(bytesAtStop / BYTES_PER_MB).append('/').append(totalBytes / BYTES_PER_MB).append("MB")
            append(" sdk=").append(device.sdkInt?.toString() ?: "-")
            append(" bucket=").append(device.standbyBucket?.toString() ?: "-")
            append(" saver=").append(device.powerSave?.toString() ?: "-")
            append(" doze=").append(device.deviceIdle?.toString() ?: "-")
            append(" unrestricted=").append(device.ignoringBatteryOptimizations?.toString() ?: "-")
            append(" notif=").append(device.notificationsEnabled?.toString() ?: "-")
            append(" wifiOnly=").append(device.wifiOnly?.toString() ?: "-")
            append(" metered=").append(device.networkMetered?.toString() ?: "-")
        }
}

private const val BYTES_PER_MB = 1_000_000L
