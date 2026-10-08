package com.calypsan.listenup.client.features.contributoredit.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Merging and unmerging contributors is Curate library's: a member without it sees their aliases
 * read-only, and a rename that collides offers only Keep separate — never a merge the server would refuse.
 */
@RunWith(RobolectricTestRunner::class)
class ContributorEditCurateTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun showAliases(canCurate: Boolean) {
        composeRule.setContent {
            MaterialTheme {
                AliasesSection(aliases = listOf("Robert Galbraith"), onUnmerge = {}, onMergeClick = {}, canCurate = canCurate)
            }
        }
    }

    @Test
    fun `a curator can unmerge an alias and add one`() {
        showAliases(canCurate = true)
        composeRule.onNodeWithContentDescription("Remove Robert Galbraith").assertIsDisplayed()
        composeRule.onNodeWithText("Add an alias").assertIsDisplayed()
    }

    @Test
    fun `without Curate library the aliases are read-only`() {
        showAliases(canCurate = false)
        composeRule.onNodeWithText("Robert Galbraith").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Remove Robert Galbraith").assertDoesNotExist()
        composeRule.onNodeWithText("Add an alias").assertDoesNotExist()
    }

    @Test
    fun `without Curate library a colliding rename offers Keep separate, not Merge`() {
        val presses = mutableListOf<String>()
        composeRule.setContent {
            MaterialTheme {
                RenameCollisionDialog(
                    newName = "George R. R. Martin",
                    existingName = "George R.R. Martin",
                    onMerge = { presses += "merge" },
                    onKeepSeparate = { presses += "keep" },
                    onDismiss = { presses += "dismiss" },
                    canMerge = false,
                )
            }
        }
        composeRule.onNodeWithText("Merge").assertDoesNotExist()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.onNodeWithText("Keep separate").performClick()
        assertEquals(listOf("dismiss", "keep"), presses)
    }
}
