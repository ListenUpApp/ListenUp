package com.calypsan.listenup.client.features.admin

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.client.presentation.admin.UserDetailUiState
import com.calypsan.listenup.client.presentation.admin.UserPermissionsUiState
import com.calypsan.listenup.client.testing.Windows
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * An admin screen whose first load failed offers Try again, and Try again re-runs the load — one screen that
 * holds a draft (Permissions) and one that does not (Member details).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Windows.PHONE)
class AdminLoadFailedRetryTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val failure = TransportError.NetworkUnavailable()

    @Test
    fun `permissions that failed to load offer Try again, which retries`() {
        var retries = 0
        val actions = PermissionsActions(onSelectPreset = {}, onSetPermission = { _, _ -> }, onRequestRole = {}, onRetry = { retries++ })
        composeRule.setContent {
            MaterialTheme {
                UserPermissionsBody(
                    state = UserPermissionsUiState.Error(failure),
                    actions = actions,
                    padding = PaddingValues(),
                )
            }
        }

        composeRule.onNodeWithText("Try again").assertIsDisplayed().performClick()

        assertEquals(1, retries)
    }

    @Test
    fun `member details that failed to load offer Try again, which retries`() {
        var retries = 0
        composeRule.setContent {
            MaterialTheme {
                UserDetailBody(
                    state = UserDetailUiState.Error(failure),
                    innerPadding = PaddingValues(),
                    onPermissionsClick = {},
                    onRetry = { retries++ },
                )
            }
        }

        composeRule.onNodeWithText("Try again").assertIsDisplayed().performClick()

        assertEquals(1, retries)
    }
}
