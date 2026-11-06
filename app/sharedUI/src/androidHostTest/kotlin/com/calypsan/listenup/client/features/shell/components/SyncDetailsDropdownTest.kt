package com.calypsan.listenup.client.features.shell.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.calypsan.listenup.client.presentation.sync.SyncIndicatorUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The sync details say when the library last synced, and say so plainly when the last refresh failed. */
@RunWith(RobolectricTestRunner::class)
class SyncDetailsDropdownTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `a failed refresh says Couldn't sync, over a recent good sync`() {
        render(SyncIndicatorUiState(lastSyncedAtMs = System.currentTimeMillis(), refreshFailed = true))

        composeRule.onNodeWithText("Couldn't sync").assertExists()
        composeRule.onNodeWithText("Synced just now").assertDoesNotExist()
    }

    @Test
    fun `a good sync says when it landed`() {
        render(SyncIndicatorUiState(lastSyncedAtMs = System.currentTimeMillis() - 2 * MINUTE_MS))

        composeRule.onNodeWithText("Synced 2 min ago").assertExists()
    }

    @Test
    fun `before the first sync it says so`() {
        render(SyncIndicatorUiState())

        composeRule.onNodeWithText("Not synced yet").assertExists()
    }

    private fun render(state: SyncIndicatorUiState) {
        composeRule.setContent {
            MaterialTheme {
                SyncDetailsDropdown(
                    expanded = true,
                    state = state,
                    onRetryOperation = {},
                    onDismissOperation = {},
                    onRetryAll = {},
                    onDismissAll = {},
                    onDismiss = {},
                )
            }
        }
    }

    private companion object {
        const val MINUTE_MS = 60_000L
    }
}
