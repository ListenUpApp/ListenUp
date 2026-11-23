package com.calypsan.listenup.client.download

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.calypsan.listenup.client.domain.repository.LocalPreferences
import dev.mokkery.mock
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowActivityManager.ApplicationExitInfoBuilder

/**
 * The process death a file left mid-transfer is charged to: the platform's record of the first exit after the transfer
 * started, so two deaths in a row each land on their own file rather than both on the newest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class AndroidDownloadDeviceStateTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val state = AndroidDownloadDeviceState(context, mock<LocalPreferences>())

    private fun died(
        at: Long,
        reason: Int,
    ) {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        shadowOf(activityManager).addApplicationExitInfo(
            ApplicationExitInfoBuilder
                .newBuilder()
                .setProcessName(context.packageName)
                .setTimestamp(at)
                .setReason(reason)
                .build(),
        )
    }

    @Test
    fun `each transfer is charged to the death that ended it`() {
        died(at = 2_000L, reason = ApplicationExitInfo.REASON_LOW_MEMORY)
        died(at = 4_000L, reason = ApplicationExitInfo.REASON_USER_REQUESTED)

        state.processDeathCode(startedAt = 1_000L) shouldBe ApplicationExitInfo.REASON_LOW_MEMORY
        state.processDeathCode(startedAt = 3_000L) shouldBe ApplicationExitInfo.REASON_USER_REQUESTED
    }

    @Test
    fun `no recorded exit is no code`() {
        state.processDeathCode(startedAt = 1_000L) shouldBe null
    }
}
