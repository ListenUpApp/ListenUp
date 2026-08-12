package com.calypsan.listenup.client.features.auth

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import com.calypsan.listenup.client.features.auth.components.CODE_BOX_TAG
import com.calypsan.listenup.client.features.auth.components.CODE_FIELD_TAG
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
 *
 * The flow's premise is the thing under test as much as the widgets are: a self-hosted server has
 * no mail transport, so the screens have to say that a person is involved, or the wait and the
 * code both look like bugs. No admin is ever named — several people may hold the role, and the
 * one state a never-approved request can reach is the waiting one, so naming there would separate
 * real accounts from unknown ones.
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
        onRetry: () -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                ForgotPasswordContent(
                    state = state,
                    onBack = onBack,
                    onRequestReset = onRequestReset,
                    onCheckStatus = onCheckStatus,
                    onCompleteReset = onCompleteReset,
                    onRetry = onRetry,
                )
            }
        }
    }

    @Test
    fun `enter email says a person handles this, and how`() {
        setContent(state = ForgotPasswordUiState.EnterEmail)

        composeRule.onNodeWithText("Reset your password").assertIsDisplayed()
        composeRule.onNodeWithText("This server has no email, so a person handles this instead.").assertIsDisplayed()
        composeRule.onNodeWithText("How this works").assertExists()
        composeRule.onNodeWithText("An admin on this server gets your request and approves it.").assertExists()
        composeRule.onNodeWithText("You enter the code here and pick a new password.").assertExists()
        composeRule.onNodeWithText("Send request").assertIsNotEnabled()
    }

    @Test
    fun `awaiting approval names no admin, shows the ticket, and invokes check status`() {
        var checked = false
        setContent(
            state = ForgotPasswordUiState.AwaitingApproval(ticketId = "7F2A"),
            onCheckStatus = { checked = true },
        )

        composeRule
            .onNodeWithText("Waiting for an admin to approve this. Ask whoever runs this server for the code.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Request #7F2A").assertExists()
        composeRule.onNodeWithText("You can close ListenUp — your request is kept.").assertExists()
        composeRule.onNodeWithText("Check Status").performSemanticsAction(SemanticsActions.OnClick)

        checked shouldBe true
    }

    @Test
    fun `a comfortable attempt budget is not worth an alarm`() {
        setContent(state = ForgotPasswordUiState.EnterCode(ticketId = "t-1", attemptsRemaining = 5))

        composeRule.onNodeWithText("5 attempts left").assertDoesNotExist()
    }

    @Test
    fun `a shrinking attempt budget is stated plainly`() {
        setContent(state = ForgotPasswordUiState.EnterCode(ticketId = "t-1", attemptsRemaining = 2))

        composeRule.onNodeWithText("2 attempts left").assertExists()
    }

    @Test
    fun `the last attempt says what happens when it is spent`() {
        setContent(state = ForgotPasswordUiState.EnterCode(ticketId = "t-1", attemptsRemaining = 1))

        composeRule
            .onNodeWithText("1 attempt left. After that the request is cancelled and you'll need to ask again.")
            .assertExists()
    }

    @Test
    fun `enter code explains what a typo costs`() {
        setContent(state = ForgotPasswordUiState.EnterCode(ticketId = "t-1"))

        composeRule
            .onNodeWithText("At least 8 characters. A typo here means asking again — tap the eye to check it.")
            .assertExists()
    }

    @Test
    fun `the code is entered as eight separate boxes, matching the server's code length`() {
        // Someone is reading the code aloud; grouped characters are easier to key and re-check
        // than a run of eight in one field. Eight, not six: the server's ResetCodeGenerator
        // mints 4+4 codes, and a shorter row silently truncates a real code.
        setContent(state = ForgotPasswordUiState.EnterCode(ticketId = "t-1"))

        // Unmerged: the cells are the text field's decoration, so their semantics merge into it.
        composeRule.onAllNodesWithTag(CODE_BOX_TAG, useUnmergedTree = true).assertCountEquals(8)
    }

    @Test
    fun `typed code lands in the boxes, normalised the way the server will read it`() {
        setContent(state = ForgotPasswordUiState.EnterCode(ticketId = "t-1"))

        composeRule.onNodeWithTag(CODE_FIELD_TAG).performTextInput("k4m9-tq")

        // The separator is dropped and the case raised, matching the server's own normalise().
        composeRule.onNodeWithText("K").assertExists()
        composeRule.onNodeWithText("Q").assertExists()
    }

    @Test
    fun `enter code shows the retained error`() {
        setContent(
            state =
                ForgotPasswordUiState.EnterCode(
                    ticketId = "t-1",
                    attemptsRemaining = 2,
                    error = "That code is not correct.",
                ),
        )

        composeRule.onNodeWithText("That code is not correct.").assertExists()
    }

    @Test
    fun `enter code keeps continue disabled until both fields are filled`() {
        setContent(state = ForgotPasswordUiState.EnterCode(ticketId = "t-1"))

        composeRule.onNodeWithText("Continue").assertIsNotEnabled()
    }

    @Test
    fun `declined is not a dead end — asking again re-opens the request`() {
        var retried = false
        var back = false
        setContent(state = ForgotPasswordUiState.Denied, onRetry = { retried = true }, onBack = { back = true })

        composeRule.onNodeWithText("Ask again").performClick()
        retried shouldBe true

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
