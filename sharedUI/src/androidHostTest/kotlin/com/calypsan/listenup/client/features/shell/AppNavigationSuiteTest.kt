package com.calypsan.listenup.client.features.shell

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
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
    fun tappingRailLogoutInvokesSignOut() {
        var signedOut = false
        composeRule.setContent {
            MaterialTheme {
                AppNavigationSuite(
                    navType = ShellNavType.RailExpanded,
                    currentDestination = ShellDestination.Home,
                    onDestinationSelected = {},
                    onSignOut = { signedOut = true },
                )
            }
        }
        composeRule.onNodeWithText("Logout").performClick()
        signedOut shouldBe true
    }
}
