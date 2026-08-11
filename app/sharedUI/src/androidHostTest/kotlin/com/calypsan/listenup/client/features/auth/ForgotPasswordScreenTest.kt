package com.calypsan.listenup.client.features.auth

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.client.presentation.auth.ForgotPasswordUiState
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Render tests for [ForgotPasswordContent] — one case per [ForgotPasswordUiState] arm that
 * carries meaningful copy or an affordance. JUnit4 + Robolectric is the canonical shape for
 * Compose UI tests in this module (see [com.calypsan.listenup.client.features.admin
 * .ManagementSectionTest]); Robolectric supplies the real Android resource environment so
 * `stringResource` resolves the packaged English strings.
 */
@RunWith(RobolectricTestRunner::class)
class ForgotPasswordScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun setContent(
        state: ForgotPasswordUiState,
        onBack: () -> Unit = {},
        onRequestReset: (String) -> Unit = {},
        onCheckStatus: () -> Unit = {},
        onCompleteReset: (String, String) -> Unit = { _, _ -> },
    ) {
        composeRule.setContent {
            MaterialTheme {
                ForgotPasswordContent(
                    state = state,
                    onBack = onBack,
                    onRequestReset = onRequestReset,
                    onCheckStatus = onCheckStatus,
                    onCompleteReset = onCompleteReset,
                )
            }
        }
    }

    @Test
    fun `enter email shows the explainer and a disabled continue until text is typed`() {
        setContent(state = ForgotPasswordUiState.EnterEmail)

        composeRule.onNodeWithText("Reset your password").assertIsDisplayed()
        composeRule
            .onNodeWithText("Your server admin will need to approve this and give you a code.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Continue").assertIsNotEnabled()
    }

    @Test
    fun `awaiting approval shows the explainer and invokes check status`() {
        var checked = false
        setContent(
            state = ForgotPasswordUiState.AwaitingApproval(ticketId = "t-1"),
            onCheckStatus = { checked = true },
        )

        composeRule
            .onNodeWithText(
                "Waiting for your admin to approve this. You can close the app — come back with your code when " +
                    "you have it.",
            ).assertIsDisplayed()
        composeRule.onNodeWithText("Check Status").performClick()

        checked shouldBe true
    }

    @Test
    fun `enter code shows the remaining attempts and the retained error`() {
        setContent(
            state =
                ForgotPasswordUiState.EnterCode(
                    ticketId = "t-1",
                    attemptsRemaining = 2,
                    error = "That code is not correct.",
                ),
        )

        // The form scrolls under Robolectric's default (short) window once both fields, the
        // attempts line, and the button are all present — assertExists rather than
        // assertIsDisplayed so this doesn't depend on viewport height.
        composeRule.onNodeWithText("2 attempts left").assertExists()
        composeRule.onNodeWithText("That code is not correct.").assertExists()
    }

    @Test
    fun `enter code keeps continue disabled until both fields are filled`() {
        setContent(state = ForgotPasswordUiState.EnterCode(ticketId = "t-1"))

        composeRule.onNodeWithText("Continue").assertIsNotEnabled()
    }

    @Test
    fun `denied shows the decline message and returns to sign in`() {
        var back = false
        setContent(state = ForgotPasswordUiState.Denied, onBack = { back = true })

        composeRule.onNodeWithText("Your admin declined this request.").assertIsDisplayed()
        composeRule.onNodeWithText("Back to sign in").performClick()

        back shouldBe true
    }

    @Test
    fun `complete shows the success message and returns to sign in`() {
        var back = false
        setContent(state = ForgotPasswordUiState.Complete, onBack = { back = true })

        composeRule.onNodeWithText("Your password has been reset. Sign in with your new password.").assertIsDisplayed()
        composeRule.onNodeWithText("Back to sign in").performClick()

        back shouldBe true
    }

    @Test
    fun `error shows the carried message and retries via onBack`() {
        var back = false
        setContent(state = ForgotPasswordUiState.Error("Your reset request expired. Please start again."), onBack = { back = true })

        composeRule.onNodeWithText("Your reset request expired. Please start again.").assertIsDisplayed()
        composeRule.onNodeWithText("Try Again").performClick()

        back shouldBe true
    }
}
