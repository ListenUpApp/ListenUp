package com.calypsan.listenup.client.features.shell

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.client.features.shell.components.AppNavigationSuite
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import io.kotest.matchers.shouldBe

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class AppNavigationSuiteTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun bottomBarShowsAllDestinations() {
        composeRule.setContent {
            MaterialTheme {
                AppNavigationSuite(
                    navType = ShellNavType.BottomBar,
                    currentDestination = ShellDestination.Home,
                    onDestinationSelected = {},
                    onSignOut = {},
                )
            }
        }
        composeRule.onNodeWithText("Home").assertIsDisplayed()
        composeRule.onNodeWithText("Library").assertIsDisplayed()
        composeRule.onNodeWithText("Discover").assertIsDisplayed()
    }

    @Test
    fun collapsedRailExposesDestinations() {
        composeRule.setContent {
            MaterialTheme {
                AppNavigationSuite(
                    navType = ShellNavType.RailCollapsed,
                    currentDestination = ShellDestination.Library,
                    onDestinationSelected = {},
                    onSignOut = {},
                )
            }
        }
        // The expressive rail renders a visible label per destination; the merged
        // accessibility node exposes it as text (not the icon's contentDescription).
        composeRule.onNodeWithText("Library").assertIsDisplayed()
    }

    @Test
    fun tappingBottomBarDestinationInvokesCallback() {
        var selected: ShellDestination? = null
        composeRule.setContent {
            MaterialTheme {
                AppNavigationSuite(
                    navType = ShellNavType.BottomBar,
                    currentDestination = ShellDestination.Home,
                    onDestinationSelected = { selected = it },
                    onSignOut = {},
                )
            }
        }
        composeRule.onNodeWithText("Discover").performClick()
        selected shouldBe ShellDestination.Discover
    }

    @Test
    fun tappingRailDestinationInvokesCallback() {
        var selected: ShellDestination? = null
        composeRule.setContent {
            MaterialTheme {
                AppNavigationSuite(
                    navType = ShellNavType.RailExpanded,
                    currentDestination = ShellDestination.Home,
                    onDestinationSelected = { selected = it },
                    onSignOut = {},
                )
            }
        }
        composeRule.onNodeWithText("Discover").performClick()
        selected shouldBe ShellDestination.Discover
    }

    @Test
    fun tappingRailLogoutAsksBeforeSigningOut() {
        var signOuts = 0
        setRail(onSignOut = { signOuts++ })

        composeRule.onNodeWithText("Logout").performClick()

        composeRule.onNodeWithText(SIGN_OUT_BODY).assertIsDisplayed()
        signOuts shouldBe 0
    }

    @Test
    fun confirmingRailLogoutSignsOutOnce() {
        var signOuts = 0
        setRail(onSignOut = { signOuts++ })

        composeRule.onNodeWithText("Logout").performClick()
        composeRule.onNode(hasText("Sign Out") and hasClickAction()).performClick()

        signOuts shouldBe 1
        composeRule.onNodeWithText(SIGN_OUT_BODY).assertDoesNotExist()
    }

    @Test
    fun dismissingRailLogoutStaysSignedIn() {
        var signOuts = 0
        setRail(onSignOut = { signOuts++ })

        composeRule.onNodeWithText("Logout").performClick()
        composeRule.onNodeWithText("Cancel").performClick()

        signOuts shouldBe 0
        composeRule.onNodeWithText(SIGN_OUT_BODY).assertDoesNotExist()
    }

    private fun setRail(onSignOut: () -> Unit) {
        composeRule.setContent {
            MaterialTheme {
                AppNavigationSuite(
                    navType = ShellNavType.RailExpanded,
                    currentDestination = ShellDestination.Home,
                    onDestinationSelected = {},
                    onSignOut = onSignOut,
                )
            }
        }
    }

    private companion object {
        const val SIGN_OUT_BODY =
            "Are you sure you want to sign out? You'll need to sign in again to access your library."
    }
}
