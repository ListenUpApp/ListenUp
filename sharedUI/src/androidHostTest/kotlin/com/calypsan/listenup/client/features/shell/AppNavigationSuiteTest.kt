package com.calypsan.listenup.client.features.shell

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.client.features.shell.components.AppNavigationSuite
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

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
    fun collapsedRailExposesDestinationsViaContentDescription() {
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
        composeRule.onNodeWithContentDescription("Library").assertExists()
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
        assertEquals(ShellDestination.Discover, selected)
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
        assertEquals(ShellDestination.Discover, selected)
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
        assertEquals(true, signedOut)
    }
}
