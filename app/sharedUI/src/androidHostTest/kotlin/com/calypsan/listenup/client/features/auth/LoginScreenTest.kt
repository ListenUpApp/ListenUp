package com.calypsan.listenup.client.features.auth

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.activity.ComponentDialog
import com.calypsan.listenup.api.dto.auth.PasswordResetStatusEvent
import com.calypsan.listenup.api.dto.auth.PasswordResetTicket
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.repository.PasswordResetRepository
import com.calypsan.listenup.client.presentation.auth.LoginUiState
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowDialog

/**
 * Render tests for the "Forgot password?" and "Reset root password" affordances added to
 * [LoginFields] / [LoginFooter] alongside [ForgotPasswordScreen]. JUnit4 + Robolectric is the
 * canonical shape for Compose UI tests in this module (see [com.calypsan.listenup.client
 * .features.admin.ManagementSectionTest]).
 */
@RunWith(RobolectricTestRunner::class)
class LoginScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `forgot password link invokes its callback`() {
        var clicked = false
        composeRule.setContent {
            MaterialTheme {
                // LoginFields is always rendered inside AuthScaffold's Column content slot in
                // production — reproduce that here so the fields and buttons stack instead of
                // overlapping at (0,0), which would let a later sibling's touch target steal
                // the click meant for an earlier one.
                Column {
                    LoginFields(
                        state = LoginUiState.Idle,
                        onSubmit = { _, _ -> },
                        onForgotPassword = { clicked = true },
                    )
                }
            }
        }

        composeRule.onNodeWithText("Forgot password?").assertIsDisplayed()
        composeRule.onNodeWithText("Forgot password?").performClick()

        clicked shouldBe true
    }

    @Test
    fun `reset root password entry invokes its callback while the hatch is armed`() {
        var clicked = false
        composeRule.setContent {
            MaterialTheme {
                Column {
                    LoginFooter(
                        openRegistration = false,
                        onRegister = {},
                        onChangeServer = {},
                        showRootReset = true,
                        onResetRoot = { clicked = true },
                        onClaimInvite = null,
                    )
                }
            }
        }

        composeRule.onNodeWithText("Reset root password").assertIsDisplayed()
        composeRule.onNodeWithText("Reset root password").performClick()

        clicked shouldBe true
    }

    @Test
    fun `reset root password entry is absent while the hatch is disarmed`() {
        // The everyday sign-in screen carries no operator plumbing: the entry exists only while
        // the server reports LISTENUP_ROOT_RESET armed (ServerInfo.rootResetArmed).
        composeRule.setContent {
            MaterialTheme {
                Column {
                    LoginFooter(
                        openRegistration = false,
                        onRegister = {},
                        onChangeServer = {},
                        showRootReset = false,
                        onResetRoot = {},
                        onClaimInvite = null,
                    )
                }
            }
        }

        composeRule.onNodeWithText("Reset root password").assertDoesNotExist()
    }

    @Test
    fun `the sign-in footer offers to enter an invite code`() {
        // An invite is the only way onto a server with registration closed, and a code given by
        // voice or in a chat that strips links has no deep link to open: the code must be typeable.
        var claimed = false
        composeRule.setContent {
            MaterialTheme {
                Column {
                    LoginFooter(
                        openRegistration = false,
                        onRegister = {},
                        onChangeServer = {},
                        showRootReset = false,
                        onClaimInvite = { claimed = true },
                    )
                }
            }
        }

        composeRule.onNodeWithText("Have an invite code?").assertIsDisplayed()
        composeRule.onNodeWithText("Enter code").performClick()

        claimed shouldBe true
    }

    @Test
    fun `a re-auth sign-in with no invite route offers no invite code`() {
        composeRule.setContent {
            MaterialTheme {
                Column {
                    LoginFooter(
                        openRegistration = false,
                        onRegister = {},
                        onChangeServer = {},
                        showRootReset = false,
                        onClaimInvite = null,
                    )
                }
            }
        }

        composeRule.onNodeWithText("Enter code").assertDoesNotExist()
    }

    @Test
    fun `the email survives a save and restore round trip`() {
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            MaterialTheme { Column { LoginFields(state = LoginUiState.Idle, onSubmit = { _, _ -> }) } }
        }
        composeRule.onNodeWithText("Email").performTextInput("reader@example.com")

        restorationTester.emulateSavedInstanceStateRestore()

        editableTextOf("Email") shouldBe "reader@example.com"
    }

    @Test
    fun `the password is not restored from saved state`() {
        // A password must never reach the saved-instance Bundle, which the system can persist to
        // disk. If someone later "fixes" rotation by making it saveable, this fails.
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            MaterialTheme { Column { LoginFields(state = LoginUiState.Idle, onSubmit = { _, _ -> }) } }
        }
        composeRule.onNodeWithText("Password").performTextInput("s3cret-pass")
        editableTextOf("Password").length shouldBe "s3cret-pass".length

        restorationTester.emulateSavedInstanceStateRestore()

        editableTextOf("Password") shouldBe ""
    }

    @Test
    fun `the owner-reset dialog ignores a dismiss request while its request runs`() {
        val repository = HangingRootResetRepository()
        var dismissed = false
        composeRule.setContent {
            MaterialTheme { RootResetDialog(onDismissRequest = { dismissed = true }, repository = repository) }
        }
        composeRule.onNodeWithText("Token").performTextInput("one-time-token")
        composeRule.onNodeWithText("Password").performTextInput("n3w-pass")
        composeRule.onNodeWithText("Continue").performClick()
        composeRule.waitForIdle()
        repository.calls shouldBe 1

        // The system Back a user would press: the dialog window's own dispatcher.
        composeRule.runOnUiThread {
            (ShadowDialog.getLatestDialog() as ComponentDialog).onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()

        dismissed shouldBe false
    }

    @Test
    fun `keyboard Done on the owner-reset dialog does not submit a blank token`() {
        val repository = HangingRootResetRepository()
        composeRule.setContent {
            MaterialTheme { RootResetDialog(onDismissRequest = {}, repository = repository) }
        }
        composeRule.onNodeWithText("Password").performTextInput("n3w-pass")

        composeRule.onNodeWithText("Password").performImeAction()
        composeRule.waitForIdle()

        repository.calls shouldBe 0
    }

    private fun editableTextOf(label: String): String =
        composeRule
            .onNodeWithText(label)
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.EditableText)
            ?.text
            .orEmpty()
}

/** A [PasswordResetRepository] whose root reset never returns, so a spec can act mid-request. */
private class HangingRootResetRepository : PasswordResetRepository {
    var calls = 0
        private set

    override suspend fun resetRootPassword(
        token: String,
        newPassword: String,
    ): AppResult<Unit> {
        calls++
        return CompletableDeferred<AppResult<Unit>>().await()
    }

    override suspend fun requestReset(email: String): AppResult<PasswordResetTicket> = error("unused")

    override fun observeStatus(ticketId: String): Flow<PasswordResetStatusEvent> = emptyFlow()

    override suspend fun fetchStatus(ticketId: String): PasswordResetStatusEvent? = null

    override suspend fun resumableTicketId(): String? = null

    override suspend fun abandonPendingRequest() = Unit

    override suspend fun completeReset(
        ticketId: String,
        code: String,
        newPassword: String,
    ): AppResult<Unit> = error("unused")
}
