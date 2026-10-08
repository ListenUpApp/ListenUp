package com.calypsan.listenup.client.features.admin.organize

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.calypsan.listenup.client.presentation.admin.OrganizeSettingsUiState
import com.calypsan.listenup.client.testing.Windows
import com.calypsan.listenup.client.testing.assertSideBySide
import com.calypsan.listenup.client.testing.assertStacked
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The organizer's pickers on a tablet sit in columns across the width; on a phone they stack.
 */
@RunWith(RobolectricTestRunner::class)
class OrganizeSettingsWideLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(qualifiers = Windows.TABLET)
    fun `on a tablet the pickers sit side by side`() {
        setContent()

        assertSideBySide(
            composeRule.onNodeWithText("Folder structure"),
            composeRule.onNodeWithText("Series number style"),
        )
        assertSideBySide(
            composeRule.onNodeWithText("Series number style"),
            composeRule.onNodeWithText("Author name style"),
        )
    }

    @Test
    @Config(qualifiers = Windows.PHONE)
    fun `on a phone the pickers stack`() {
        setContent()

        assertStacked(
            composeRule.onNodeWithText("Folder structure"),
            composeRule.onNodeWithText("Series number style"),
        )
    }

    private fun setContent() {
        composeRule.setContent {
            MaterialTheme {
                OrganizeSettingsContent(
                    state = OrganizeSettingsUiState.Ready(),
                    onPresetChange = {},
                    onSeriesPrefixChange = {},
                    onAuthorFormChange = {},
                    onOrganize = {},
                    innerPadding = PaddingValues(),
                )
            }
        }
    }
}
