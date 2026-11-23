package com.calypsan.listenup.client.features.bookdetail

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The cancel-download confirm says what you lose: the amount already saved, and that it goes. iOS words its alert the
 * same way (`CancelDownloadCopy`), in title case for the buttons.
 */
@RunWith(RobolectricTestRunner::class)
class CancelDownloadDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val taps = mutableListOf<String>()

    private fun show() =
        composeRule.setContent {
            MaterialTheme {
                CancelDownloadDialog(
                    savedBytes = 212L * 1024 * 1024,
                    onConfirm = { taps += "cancel" },
                    onDismiss = { taps += "keep" },
                )
            }
        }

    @Test
    fun `the confirm names what is already saved and that it will be removed`() {
        show()

        composeRule.onNodeWithText("Cancel this download?").assertExists()
        composeRule.onNodeWithText("The 212 MB already saved on this device will be removed.").assertExists()
    }

    @Test
    fun `cancel download discards and keep downloading backs out`() {
        show()

        composeRule.onNodeWithText("Keep downloading").performClick()
        composeRule.onNodeWithText("Cancel download").performClick()
        taps shouldBe listOf("keep", "cancel")
    }
}
