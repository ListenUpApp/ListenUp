package com.calypsan.listenup.client.design.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.client.features.library.components.LibrarySortCard
import com.calypsan.listenup.client.features.settings.SettingsActions
import com.calypsan.listenup.client.features.settings.SettingsContent
import com.calypsan.listenup.client.presentation.library.SortCategory
import com.calypsan.listenup.client.presentation.library.SortDirection
import com.calypsan.listenup.client.presentation.library.SortState
import com.calypsan.listenup.client.presentation.settings.SettingsUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins that a choice says which option is chosen — to TalkBack, not only by fill or a check glyph.
 * The 2026-09-27 audit found ~50 hand-drawn pills and pickers with no selected semantics; these are
 * representative of the sweep: a card choice, a settings value picker and a sort menu.
 */
@RunWith(RobolectricTestRunner::class)
class ChoiceSemanticsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `an option card is a radio that reports whether it is chosen`() {
        composeRule.setContent {
            MaterialTheme {
                androidx.compose.foundation.layout.Row {
                    SelectableOptionCard("Member", "Can listen", Icons.Outlined.Person, selected = true, onClick = {})
                    SelectableOptionCard("Admin", "Can manage", Icons.Outlined.Person, selected = false, onClick = {})
                }
            }
        }

        composeRule
            .onNode(isSelectable() and hasText("Member"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .assertIsSelected()
        composeRule.onNode(isSelectable() and hasText("Admin")).assertIsNotSelected()
    }

    @Test
    fun `a settings value picker marks the current value as the selected one`() {
        composeRule.setContent {
            MaterialTheme {
                SettingsContent(
                    state = SettingsUiState(isLoading = false, appVersion = "1.0"),
                    actions = NoOpActions,
                    showDynamicColors = false,
                    showSleepTimer = false,
                    onNavigateToDevices = null,
                    onNavigateToStorage = null,
                    onNavigateToLicenses = null,
                    onNavigateToNotificationSettings = null,
                    onSignOutClick = {},
                    onShareLogs = {},
                    onSendTestNotification = {},
                )
            }
        }

        // The Theme pill shows the current mode; opening it lists every mode.
        composeRule.onNode(hasText("System") and isNotSelectableOption()).performClick()

        composeRule
            .onNode(isSelectable() and hasText("System"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .assertIsSelected()
        composeRule.onNode(isSelectable() and hasText("Dark")).assertIsNotSelected()
    }

    @Test
    fun `the sort menu marks the current category, and the article toggle is a checkbox`() {
        composeRule.setContent {
            MaterialTheme {
                LibrarySortCard(
                    state = SortState(SortCategory.TITLE, SortDirection.ASCENDING),
                    categories = listOf(SortCategory.TITLE, SortCategory.AUTHOR),
                    count = 3,
                    unit = "titles",
                    ignoreArticles = true,
                    showArticleToggle = true,
                    onCategorySelected = {},
                    onDirectionToggle = {},
                    onToggleArticles = {},
                    visible = true,
                )
            }
        }

        composeRule.onNodeWithContentDescription("Title", substring = true).performClick()

        composeRule.onNode(isSelectable() and hasText("Title")).assertIsSelected()
        composeRule.onNode(isSelectable() and hasText("Author")).assertIsNotSelected()
        composeRule
            .onNode(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
    }

    private fun isNotSelectableOption() = SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected)

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
