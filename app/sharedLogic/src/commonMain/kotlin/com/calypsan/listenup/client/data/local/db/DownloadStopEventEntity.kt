package com.calypsan.listenup.client.data.local.db

import androidx.room3.Entity
import androidx.room3.PrimaryKey
import com.calypsan.listenup.client.download.DownloadStopReason
import com.calypsan.listenup.client.download.DownloadTransport

/** How many stop events the device keeps; older ones are pruned on every insert. */
internal const val MAX_DOWNLOAD_STOP_EVENTS = 200

/**
 * One time a download run stopped before its book finished, with the device's state at that moment, so a later doctor
 * or a shared log can say why downloads kept stopping ("quota, while in the rare bucket, with notifications off").
 * Bounded to the last [MAX_DOWNLOAD_STOP_EVENTS]; never synced.
 *
 * The device fields are null where the platform could not say.
 */
@Entity(tableName = "download_stop_events")
internal data class DownloadStopEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val at: Long,
    val bookId: String,
    val audioFileId: String?,
    val reason: DownloadStopReason,
    val reasonCode: Int?,
    val bytesAtStop: Long,
    val totalBytes: Long,
    val transport: DownloadTransport?,
    val foreground: Boolean?,
    val sdkInt: Int?,
    val standbyBucket: Int?,
    val powerSave: Boolean?,
    val deviceIdle: Boolean?,
    val ignoringBatteryOptimizations: Boolean?,
    val notificationsEnabled: Boolean?,
    val wifiOnly: Boolean?,
    val networkMetered: Boolean?,
)
