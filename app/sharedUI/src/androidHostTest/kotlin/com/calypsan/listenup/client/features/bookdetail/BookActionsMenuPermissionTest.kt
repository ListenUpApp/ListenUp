package com.calypsan.listenup.client.features.bookdetail

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.calypsan.listenup.client.features.bookdetail.components.BookActionsMenu
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Edit book, Match details and Edit chapters belong to Edit metadata, not to admins: a member who may edit
 * sees all three (the chapter editor was admin-only on Android before), and one who may not sees none.
 */
@RunWith(RobolectricTestRunner::class)
class BookActionsMenuPermissionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun show(
        canEditMetadata: Boolean,
        isAdmin: Boolean = false,
    ) {
        composeRule.setContent {
            MaterialTheme {
                BookActionsMenu(
                    expanded = true,
                    onDismiss = {},
                    isComplete = false,
                    hasProgress = false,
                    isAdmin = isAdmin,
                    canEditMetadata = canEditMetadata,
                    onEditClick = {},
                    onFindMetadataClick = {},
                    onEditChaptersClick = {},
                    onMarkCompleteClick = {},
                    onMarkNotStartedClick = {},
                    onRestartClick = {},
                    onAddToShelfClick = {},
                    onAddToCollectionClick = {},
                    onShareClick = {},
                    onDeleteClick = {},
                )
            }
        }
    }

    @Test
    fun `a member who may edit metadata gets all three edit actions, chapters included`() {
        show(canEditMetadata = true)
        composeRule.onNodeWithText("Edit book").assertIsDisplayed()
        composeRule.onNodeWithText("Match details").assertIsDisplayed()
        composeRule.onNodeWithText("Edit chapters").assertIsDisplayed()
    }

    @Test
    fun `a member who may not edit metadata gets none of them`() {
        show(canEditMetadata = false)
        composeRule.onNodeWithText("Edit book").assertDoesNotExist()
        composeRule.onNodeWithText("Match details").assertDoesNotExist()
        composeRule.onNodeWithText("Edit chapters").assertDoesNotExist()
    }
}
