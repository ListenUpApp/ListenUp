package com.calypsan.listenup.client.features.auth

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.client.presentation.auth.LoginUiState
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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
    fun `reset root password entry invokes its callback`() {
        var clicked = false
        composeRule.setContent {
            MaterialTheme {
                Column {
                    LoginFooter(
                        openRegistration = false,
                        onRegister = {},
                        onChangeServer = {},
                        onResetRoot = { clicked = true },
                    )
                }
            }
        }

        composeRule.onNodeWithText("Reset root password").assertIsDisplayed()
        composeRule.onNodeWithText("Reset root password").performClick()

        clicked shouldBe true
    }
}
