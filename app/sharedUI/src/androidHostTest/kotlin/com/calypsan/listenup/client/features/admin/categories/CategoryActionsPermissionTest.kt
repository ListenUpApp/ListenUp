package com.calypsan.listenup.client.features.admin.categories

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A genre's actions follow the two permissions: editing (add, rename, move) and curating (merge, history, delete). */
@RunWith(RobolectricTestRunner::class)
class CategoryActionsPermissionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun show(
        canEdit: Boolean,
        canCurate: Boolean,
    ) {
        composeRule.setContent {
            MaterialTheme {
                CategoryActions(
                    canEdit = canEdit,
                    canCurate = canCurate,
                    onAddChild = {},
                    onRename = {},
                    onMove = {},
                    onMerge = {},
                    onMergeHistory = {},
                    onDelete = {},
                )
            }
        }
    }

    @Test
    fun `a curator who may not edit sees merge, history and delete only`() {
        show(canEdit = false, canCurate = true)
        composeRule.onNodeWithText("Merge into…").assertIsDisplayed()
        composeRule.onNodeWithText("Merge history").assertIsDisplayed()
        composeRule.onNodeWithText("Delete").assertIsDisplayed()
        composeRule.onNodeWithText("Add sub-genre").assertDoesNotExist()
        composeRule.onNodeWithText("Rename").assertDoesNotExist()
        composeRule.onNodeWithText("Move to…").assertDoesNotExist()
    }

    @Test
    fun `an editor who may not curate sees add, rename and move only`() {
        show(canEdit = true, canCurate = false)
        composeRule.onNodeWithText("Add sub-genre").assertIsDisplayed()
        composeRule.onNodeWithText("Rename").assertIsDisplayed()
        composeRule.onNodeWithText("Move to…").assertIsDisplayed()
        composeRule.onNodeWithText("Merge into…").assertDoesNotExist()
        composeRule.onNodeWithText("Merge history").assertDoesNotExist()
        composeRule.onNodeWithText("Delete").assertDoesNotExist()
    }
}
