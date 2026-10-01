package com.calypsan.listenup.client.features.admin.inbox

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Release asks first (spec §7), in the calm words, and only the verb releases. */
@RunWith(RobolectricTestRunner::class)
class ReleaseToEveryoneDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var confirms = 0
    private var dismisses = 0

    @Test
    fun `one book is asked about in the singular`() {
        setContent(bookCount = 1)

        composeRule.onNodeWithText("Release to everyone?").assertIsDisplayed()
        composeRule.onNodeWithText("Every member will be able to find and play it.").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").assertIsDisplayed()
        composeRule.onNodeWithText("Release").assertIsDisplayed()
    }

    @Test
    fun `several books are asked about in the plural`() {
        setContent(bookCount = 3)

        composeRule.onNodeWithText("Every member will be able to find and play them.").assertIsDisplayed()
    }

    @Test
    fun `Release confirms`() {
        setContent(bookCount = 1)

        composeRule.onNodeWithText("Release").performClick()
        composeRule.runOnIdle {
            confirms shouldBe 1
            dismisses shouldBe 0
        }
    }

    @Test
    fun `Cancel releases nothing`() {
        setContent(bookCount = 1)

        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.runOnIdle {
            confirms shouldBe 0
            dismisses shouldBe 1
        }
    }

    private fun setContent(bookCount: Int) {
        composeRule.setContent {
            MaterialTheme {
                ReleaseToEveryoneDialog(
                    bookCount = bookCount,
                    onConfirm = { confirms++ },
                    onDismiss = { dismisses++ },
                )
            }
        }
    }
}
