package com.calypsan.listenup.client.download

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import com.calypsan.listenup.client.domain.repository.LocalPreferences

/**
 * What an Android device looked like at a stop: release, standby bucket, battery saver, Doze, whether ListenUp is
 * exempt from battery optimisation, whether its notifications are on, the Wi-Fi-only rule and whether the network is
 * metered. Each read is cheap and none can throw for lack of a permission.
 */
internal class AndroidDownloadDeviceState(
    private val context: Context,
    private val localPreferences: LocalPreferences,
) : DownloadDeviceState {
    override fun snapshot(): DownloadDeviceSnapshot {
        val power = context.getSystemService(PowerManager::class.java)
        return DownloadDeviceSnapshot(
            sdkInt = Build.VERSION.SDK_INT,
            standbyBucket = context.getSystemService(UsageStatsManager::class.java)?.appStandbyBucket,
            powerSave = power?.isPowerSaveMode,
            deviceIdle = power?.isDeviceIdleMode,
            ignoringBatteryOptimizations = power?.isIgnoringBatteryOptimizations(context.packageName),
            notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
            wifiOnly = localPreferences.wifiOnlyDownloads.value,
            networkMetered = context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered,
        )
    }

    /**
     * Why the process that was transferring a file started at [startedAt] ended (`ApplicationExitInfo.reason`): a Task
     * Manager stop, a low-memory kill, a crash. Each orphan is charged to the first exit after its start, so two deaths
     * in a row are each recorded against their own file. Null when the platform kept no record of it.
     */
    override fun processDeathCode(startedAt: Long?): Int? {
        val exits =
            context
                .getSystemService(ActivityManager::class.java)
                ?.getHistoricalProcessExitReasons(context.packageName, 0, 0)
                .orEmpty()
                .map { exit -> ProcessExit(at = exit.timestamp, reason = exit.reason) }
        return exitThatEnded(exits, startedAt)?.reason
    }
}
