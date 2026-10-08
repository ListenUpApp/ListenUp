package com.calypsan.listenup.client.features.contributordetail

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A contributor's overflow menu offers only what the user may do: Edit and Match details under Edit
 * metadata, Delete under Curate library — and with neither, there is no menu to open at all.
 */
@RunWith(RobolectricTestRunner::class)
class ContributorOverflowMenuPermissionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun show(
        canEditMetadata: Boolean,
        canCurateLibrary: Boolean,
    ) {
        composeRule.setContent {
            MaterialTheme {
                NavigationBar(
                    onBackClick = {},
                    onEditClick = {},
                    onMatchDetails = {},
                    onDeleteClick = {},
                    canEditMetadata = canEditMetadata,
                    canCurateLibrary = canCurateLibrary,
                )
            }
        }
    }

    @Test
    fun `a listener who may neither edit nor curate gets no overflow menu`() {
        show(canEditMetadata = false, canCurateLibrary = false)
        composeRule.onNodeWithContentDescription(MORE_OPTIONS).assertDoesNotExist()
    }

    @Test
    fun `an editor who may not curate gets Edit and Match details, not Delete`() {
        show(canEditMetadata = true, canCurateLibrary = false)
        composeRule.onNodeWithContentDescription(MORE_OPTIONS).performClick()
        composeRule.onNodeWithText("Edit").assertIsDisplayed()
        composeRule.onNodeWithText("Match details").assertIsDisplayed()
        composeRule.onNodeWithText("Delete").assertDoesNotExist()
    }

    @Test
    fun `a curator who may not edit gets Delete only`() {
        show(canEditMetadata = false, canCurateLibrary = true)
        composeRule.onNodeWithContentDescription(MORE_OPTIONS).performClick()
        composeRule.onNodeWithText("Delete").assertIsDisplayed()
        composeRule.onNodeWithText("Edit").assertDoesNotExist()
        composeRule.onNodeWithText("Match details").assertDoesNotExist()
    }

    private companion object {
        const val MORE_OPTIONS = "More options"
    }
}
