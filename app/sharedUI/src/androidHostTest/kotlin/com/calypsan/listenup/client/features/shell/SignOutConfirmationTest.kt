package com.calypsan.listenup.client.features.shell

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.client.design.components.UserAvatarMenu
import com.calypsan.listenup.client.features.shell.components.AppNavigationSuite
import com.calypsan.listenup.client.features.shell.components.SignOutConfirmationHost
import com.calypsan.listenup.client.features.shell.components.rememberSignOutConfirmation
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Both of the shell's sign-out paths — the rail's Logout and the avatar menu's "Sign out" — ask
 * the one question before signing out, wired exactly as `AppShell` wires them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class SignOutConfirmationTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var signOuts = 0

    @Test
    fun railLogoutAsksBeforeSigningOut() {
        setShell()

        composeRule.onNodeWithText("Logout").performClick()

        composeRule.onNodeWithText(SIGN_OUT_BODY).assertIsDisplayed()
        signOuts shouldBe 0
    }

    @Test
    fun avatarMenuSignOutAsksBeforeSigningOut() {
        setShell(avatarMenuOpen = true)

        composeRule.onNode(hasText("Sign Out") and hasClickAction()).performClick()

        composeRule.onNodeWithText(SIGN_OUT_BODY).assertIsDisplayed()
        signOuts shouldBe 0
    }

    @Test
    fun confirmingSignsOutOnce() {
        setShell()

        composeRule.onNodeWithText("Logout").performClick()
        composeRule.onNode(hasText("Sign Out") and hasClickAction()).performClick()

        signOuts shouldBe 1
        composeRule.onNodeWithText(SIGN_OUT_BODY).assertDoesNotExist()
    }

    @Test
    fun cancellingStaysSignedIn() {
        setShell()

        composeRule.onNodeWithText("Logout").performClick()
        composeRule.onNodeWithText("Cancel").performClick()

        signOuts shouldBe 0
        composeRule.onNodeWithText(SIGN_OUT_BODY).assertDoesNotExist()
    }

    private fun setShell(avatarMenuOpen: Boolean = false) {
        composeRule.setContent {
            MaterialTheme {
                val signOutConfirmation = rememberSignOutConfirmation()
                var avatarMenuExpanded by remember { mutableStateOf(avatarMenuOpen) }
                Column {
                    UserAvatarMenu(
                        user = null,
                        expanded = avatarMenuExpanded,
                        onExpandedChange = { avatarMenuExpanded = it },
                        onMyProfileClick = {},
                        onSignOutClick = signOutConfirmation::request,
                    )
                    AppNavigationSuite(
                        navType = ShellNavType.RailExpanded,
                        currentDestination = ShellDestination.Home,
                        onDestinationSelected = {},
                        onSignOutRequest = signOutConfirmation::request,
                    )
                }
                SignOutConfirmationHost(signOutConfirmation, onSignOut = { signOuts++ })
            }
        }
    }

    private companion object {
        const val SIGN_OUT_BODY =
            "Are you sure you want to sign out? You'll need to sign in again to access your library."
    }
}
