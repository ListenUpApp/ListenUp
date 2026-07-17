package com.calypsan.listenup.client.features.admin

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Verifies the admin Management section surfaces a Library Settings tile and that
 * tapping it routes to the navigation callback. This is the hub entry point that
 * makes the (previously unreachable) [LibrarySettingsScreen] reachable.
 *
 * JUnit4 + Robolectric — the canonical shape for Compose UI tests in this module
 * (a `createComposeRule()` rule requires JUnit4). Robolectric supplies the real
 * Android resource environment so `stringResource` resolves the packaged English
 * strings (per [com.calypsan.listenup.client.presentation.error.AppErrorLocalizationTest]).
 */
@RunWith(RobolectricTestRunner::class)
class ManagementSectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `library settings tile is shown`() {
        composeRule.setContent {
            MaterialTheme {
                ManagementSection(
                    onInviteClick = {},
                    onCollectionsClick = {},
                    onCategoriesClick = {},
                    onBackupClick = {},
                    onImportClick = {},
                    onInboxClick = {},
                    onLibrarySettingsClick = {},
                    inboxEnabled = false,
                )
            }
        }

        composeRule.onNodeWithText(LIBRARY_SETTINGS_TITLE).assertIsDisplayed()
    }

    @Test
    fun `tapping the library settings tile invokes its callback`() {
        var clicked = false
        composeRule.setContent {
            MaterialTheme {
                ManagementSection(
                    onInviteClick = {},
                    onCollectionsClick = {},
                    onCategoriesClick = {},
                    onBackupClick = {},
                    onImportClick = {},
                    onInboxClick = {},
                    onLibrarySettingsClick = { clicked = true },
                    inboxEnabled = false,
                )
            }
        }

        composeRule.onNodeWithText(LIBRARY_SETTINGS_TITLE).performClick()

        clicked shouldBe true
    }

    private companion object {
        const val LIBRARY_SETTINGS_TITLE = "Library Settings"
    }
}
