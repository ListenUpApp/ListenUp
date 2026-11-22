package com.calypsan.listenup.client.download

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The merged manifest carries what the download transports need: the permissions, the job service only the system can
 * bind, WorkManager's foreground service typed `dataSync`, and the action receiver no other app can reach.
 */
@RunWith(RobolectricTestRunner::class)
class DownloadManifestTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val packages = context.packageManager

    @Test
    fun `the download permissions are requested`() {
        val requested =
            packages
                .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions
                .orEmpty()
                .toList()

        requested shouldContainAll
            listOf(
                Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC,
                Manifest.permission.RUN_USER_INITIATED_JOBS,
                Manifest.permission.RECEIVE_BOOT_COMPLETED,
                Manifest.permission.POST_NOTIFICATIONS,
            )
    }

    @Test
    fun `the job service is private and bound only by the system`() {
        val service = packages.getServiceInfo(ComponentName(context, BookTransferJobService::class.java), 0)

        service.exported shouldBe false
        service.permission shouldBe "android.permission.BIND_JOB_SERVICE"
    }

    @Test
    fun `WorkManager's foreground service is typed dataSync`() {
        val service =
            packages.getServiceInfo(
                ComponentName(context, "androidx.work.impl.foreground.SystemForegroundService"),
                0,
            )

        (service.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC != 0) shouldBe true
    }

    @Test
    fun `the notification's action receiver is not exported`() {
        packages.getReceiverInfo(ComponentName(context, DownloadActionReceiver::class.java), 0).exported shouldBe false
    }
}
