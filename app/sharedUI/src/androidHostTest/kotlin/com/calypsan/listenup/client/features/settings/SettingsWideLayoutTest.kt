package com.calypsan.listenup.client.features.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.calypsan.listenup.client.presentation.settings.SettingsUiState
import com.calypsan.listenup.client.testing.Windows
import com.calypsan.listenup.client.testing.assertSideBySide
import com.calypsan.listenup.client.testing.assertStacked
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings on a tablet spreads its section groups across the width; on a phone they stay one
 * column, in the order they have always been in.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsWideLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(qualifiers = Windows.TABLET)
    fun `on a tablet the section groups sit side by side`() {
        setContent()

        assertSideBySide(
            composeRule.onNodeWithText("Appearance"),
            composeRule.onNodeWithText("Playback"),
        )
        assertSideBySide(
            composeRule.onNodeWithText("Playback"),
            composeRule.onNodeWithText("Sleep timer"),
        )
    }

    @Test
    @Config(qualifiers = Windows.PHONE)
    fun `on a phone the section groups stay one column`() {
        setContent()

        assertStacked(
            composeRule.onNodeWithText("Appearance"),
            composeRule.onNodeWithText("Playback"),
        )
    }

    private fun setContent() {
        composeRule.setContent {
            MaterialTheme {
                SettingsContent(
                    state = SettingsUiState(isLoading = false, appVersion = "1.0"),
                    actions = NoOpActions,
                    showDynamicColors = true,
                    showSleepTimer = true,
                    onNavigateToDevices = {},
                    onNavigateToStorage = {},
                    onNavigateToLicenses = {},
                    onNavigateToNotificationSettings = {},
                    onSignOutClick = {},
                    onShareLogs = {},
                    onSendTestNotification = {},
                )
            }
        }
    }

    private companion object {
        val NoOpActions =
            SettingsActions(
                onThemeModeChange = {},
                onDynamicColorsChange = {},
                onPlaybackSpeedChange = {},
                onVolumeBoostChange = {},
                onSkipForwardChange = {},
                onSkipBackwardChange = {},
                onAutoRewindChange = {},
                onSleepTimerChange = {},
                onIgnoreTitleArticlesChange = {},
                onHideSingleBookSeriesChange = {},
                onHapticFeedbackChange = {},
                onWifiOnlyDownloadsChange = {},
            )
    }
}
