package com.calypsan.listenup.client.features.admin.upload

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class StopUploadGuardTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var stops = 0

    @Test
    fun backWhileUploadingAsksBeforeStopping() {
        setGuard(uploading = true)

        pressBack()

        composeRule.onNodeWithText("Stop upload?").assertIsDisplayed()
        stops shouldBe 0
    }

    @Test
    fun confirmingStopsTheUploadOnce() {
        setGuard(uploading = true)

        pressBack()
        composeRule.onNodeWithText("Stop upload").performClick()

        stops shouldBe 1
        composeRule.onNodeWithText("Stop upload?").assertDoesNotExist()
    }

    @Test
    fun dismissingKeepsUploading() {
        setGuard(uploading = true)

        pressBack()
        composeRule.onNodeWithText("Keep uploading").performClick()

        stops shouldBe 0
        composeRule.onNodeWithText("Stop upload?").assertDoesNotExist()
    }

    @Test
    fun anUploadThatEndsWhileAskingClosesTheQuestion() {
        var uploading by mutableStateOf(true)
        setGuard(uploading = { uploading })

        pressBack()
        composeRule.onNodeWithText("Stop upload?").assertIsDisplayed()
        uploading = false
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Stop upload?").assertDoesNotExist()
        stops shouldBe 0
    }

    private fun setGuard(uploading: Boolean) = setGuard(uploading = { uploading })

    private fun setGuard(uploading: () -> Boolean) {
        composeRule.setContent {
            MaterialTheme {
                var confirming by remember { mutableStateOf(false) }
                StopUploadGuard(
                    uploading = uploading(),
                    confirming = confirming,
                    onConfirmingChange = { confirming = it },
                    onStop = { stops++ },
                )
            }
        }
    }

    private fun pressBack() {
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
    }
}
