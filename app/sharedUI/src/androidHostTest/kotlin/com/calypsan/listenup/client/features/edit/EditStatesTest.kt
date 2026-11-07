package com.calypsan.listenup.client.features.edit

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.api.error.ContributorError
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.api.error.TransportError
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The edit screens' two failure surfaces: a subject that could not be loaded (never an empty form),
 * and a Save/merge/image failure shown beside the form instead of in place of it.
 */
@RunWith(RobolectricTestRunner::class)
class EditStatesTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var backs = 0
    private var retries = 0
    private var dismissals = 0

    @Test
    fun `a retryable load failure offers Retry and Back`() {
        val error = TransportError.Timeout()
        composeRule.setContent {
            MaterialTheme { EditLoadFailed(error = error, onBack = { backs++ }, onRetry = { retries++ }) }
        }

        composeRule.onNodeWithText("Retry").performClick()
        composeRule.onNodeWithText("Back").performClick()

        retries shouldBe 1
        backs shouldBe 1
    }

    @Test
    fun `a contributor that no longer exists offers only Back`() {
        composeRule.setContent {
            MaterialTheme { EditLoadFailed(error = ContributorError.NotFound(), onBack = { backs++ }, onRetry = { retries++ }) }
        }

        composeRule.onNodeWithText("Retry").assertDoesNotExist()
        composeRule.onNodeWithText("Back").performClick()

        backs shouldBe 1
    }

    @Test
    fun `an inline failure says what happened and can be dismissed`() {
        val error = InternalError()
        composeRule.setContent {
            MaterialTheme { EditInlineError(error = error, onDismiss = { dismissals++ }) }
        }

        composeRule.onNodeWithText("Dismiss").performClick()

        dismissals shouldBe 1
    }
}
