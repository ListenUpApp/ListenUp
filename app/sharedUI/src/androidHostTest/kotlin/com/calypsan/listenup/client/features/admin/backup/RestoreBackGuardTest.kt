package com.calypsan.listenup.client.features.admin.backup

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.calypsan.listenup.api.dto.backup.RestoreResult
import com.calypsan.listenup.core.BackupId
import com.calypsan.listenup.client.design.util.PlatformBackHandler
import com.calypsan.listenup.client.presentation.admin.RestoreBackupUiState
import com.calypsan.listenup.client.presentation.admin.RestoreFromFileUiState
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The restore screens hide their top-bar arrow while a destructive operation runs; the system Back
 * gesture is the second door and must be closed under the same condition. A lower-priority handler
 * stands in for the navigator popping the entry (which would clear the ViewModel and its restore).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class RestoreBackGuardTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var pops = 0
    private var completions = 0

    @Test
    fun backWhileRestoringDoesNotLeaveTheScreen() {
        hostRestore(RestoreBackupUiState.Restoring)

        pressBack()

        pops shouldBe 0
        completions shouldBe 0
    }

    @Test
    fun backOnACompletedRestoreActsAsDone() {
        hostRestore(RestoreBackupUiState.Completed(RESULT))

        pressBack()

        completions shouldBe 1
        pops shouldBe 0
    }

    @Test
    fun backBeforeTheRestoreStartsLeavesTheScreen() {
        hostRestore(RestoreBackupUiState.Idle())

        pressBack()

        pops shouldBe 1
    }

    @Test
    fun backWhileUploadingABackupFileDoesNotLeaveTheScreen() {
        hostFromFile(RestoreFromFileUiState.Uploading(filename = "backup.zip"))

        pressBack()

        pops shouldBe 0
    }

    @Test
    fun backBeforeAnUploadLeavesTheScreen() {
        hostFromFile(RestoreFromFileUiState.Idle)

        pressBack()

        pops shouldBe 1
    }

    private fun hostRestore(state: RestoreBackupUiState) = host { RestoreBackupBackGuard(state = state, onComplete = { completions++ }) }

    private fun hostFromFile(state: RestoreFromFileUiState) = host { RestoreFromFileBackGuard(state = state) }

    private fun host(guard: @Composable () -> Unit) {
        composeRule.setContent {
            PlatformBackHandler(enabled = true) { pops++ }
            guard()
        }
    }

    private fun pressBack() {
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
    }

    private companion object {
        val RESULT =
            RestoreResult(
                restoredFrom = BackupId("backup-1"),
                includedImages = false,
                schemaMigratedFrom = "1",
                schemaMigratedTo = "1",
            )
    }
}
