package com.calypsan.listenup.client.features.admin.backup

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.calypsan.listenup.client.design.components.LocalSnackbarHostState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A backup download ends in the app's snackbar, not a Toast: the snackbar sits above the mini-player
 * with the rest of the app's feedback, and TalkBack reads it the same way.
 */
@RunWith(RobolectricTestRunner::class)
class BackupDownloadNoticesTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var notices: BackupDownloadNotices

    private fun setContent() {
        val host = SnackbarHostState()
        composeRule.setContent {
            CompositionLocalProvider(LocalSnackbarHostState provides host) {
                MaterialTheme {
                    notices = rememberBackupDownloadNotices()
                    SnackbarHost(host)
                }
            }
        }
    }

    @Test
    fun `a saved download says so in the snackbar`() {
        setContent()

        composeRule.runOnIdle { notices.saved() }

        composeRule.onNodeWithText("Backup saved to your device.").assertIsDisplayed()
    }

    @Test
    fun `a failed download says so in the snackbar`() {
        setContent()

        composeRule.runOnIdle { notices.failed() }

        composeRule.onNodeWithText("Couldn't save the backup to your device.").assertIsDisplayed()
    }
}
