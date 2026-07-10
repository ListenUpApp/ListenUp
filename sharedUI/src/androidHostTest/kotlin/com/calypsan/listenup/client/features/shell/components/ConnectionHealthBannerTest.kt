package com.calypsan.listenup.client.features.shell.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.calypsan.listenup.client.presentation.connection.ConnectionHealthUi
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ConnectionHealthBannerTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun hiddenRendersNothing() {
        composeRule.setContent {
            MaterialTheme {
                ConnectionHealthBanner(
                    state = ConnectionHealthUi.Hidden,
                    onSignIn = {},
                    onRetry = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.onNodeWithText("Signed out").assertDoesNotExist()
    }

    @Test
    fun sessionExpiredRendersSignInAction() {
        composeRule.setContent {
            MaterialTheme {
                ConnectionHealthBanner(
                    state = ConnectionHealthUi.SessionExpired,
                    onSignIn = {},
                    onRetry = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.onNodeWithText("Signed out").assertIsDisplayed()
        composeRule.onNodeWithText("Sign in").assertIsDisplayed()
    }

    @Test
    fun unreachableRendersRetryAction() {
        composeRule.setContent {
            MaterialTheme {
                ConnectionHealthBanner(
                    state = ConnectionHealthUi.Unreachable(sinceMillis = 0L),
                    onSignIn = {},
                    onRetry = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.onNodeWithText("Offline").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").assertIsDisplayed()
    }

    @Test
    fun outdatedRendersVersionsAndDismissAction() {
        composeRule.setContent {
            MaterialTheme {
                ConnectionHealthBanner(
                    state = ConnectionHealthUi.Outdated(clientVersion = "1.2.0", serverVersion = "1.3.0"),
                    onSignIn = {},
                    onRetry = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.onNodeWithText("Update available").assertIsDisplayed()
        composeRule.onNodeWithText("App 1.2.0 / server 1.3.0. Some features may not sync.").assertIsDisplayed()
        composeRule.onNodeWithText("Dismiss").assertIsDisplayed()
    }
}
