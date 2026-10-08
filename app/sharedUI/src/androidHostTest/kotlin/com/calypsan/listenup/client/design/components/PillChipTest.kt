package com.calypsan.listenup.client.design.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins [PillChip]'s selection semantics: a pill in a choice set says whether it is the chosen one
 * (not just by colour), and a pill used as a plain action does not claim a selection state at all.
 */
@RunWith(RobolectricTestRunner::class)
class PillChipTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `a chosen pill announces selected`() {
        composeRule.setContent {
            MaterialTheme {
                PillChip(label = "1.5×", onClick = {}, selected = true)
            }
        }

        composeRule
            .onNodeWithText("1.5×")
            .assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
    }

    @Test
    fun `an unchosen pill announces not selected`() {
        composeRule.setContent {
            MaterialTheme {
                PillChip(label = "2×", onClick = {}, selected = false)
            }
        }

        composeRule.onNodeWithText("2×").assertIsNotSelected()
    }

    @Test
    fun `a filter pill can be a checkbox`() {
        composeRule.setContent {
            MaterialTheme {
                PillChip(label = "Books", onClick = {}, selected = true, selectionRole = Role.Checkbox)
            }
        }

        composeRule
            .onNodeWithText("Books")
            .assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
    }

    @Test
    fun `an action pill claims no selection`() {
        composeRule.setContent {
            MaterialTheme {
                PillChip(label = "Invite", onClick = {})
            }
        }

        composeRule
            .onNodeWithText("Invite")
            .assert(isSelectable().not())
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
    }
}
