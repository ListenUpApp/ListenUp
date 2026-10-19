package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.calypsan.listenup.api.dto.hardcover.HardcoverBookSync
import com.calypsan.listenup.client.presentation.hardcover.BookHardcoverUiState
import com.calypsan.listenup.client.presentation.hardcover.HardcoverMatchedBook
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Sync with Hardcover is a switch to TalkBack, named by its label, with its on/off state (spec §5.2). */
@RunWith(RobolectricTestRunner::class)
class BookHardcoverSwitchSemanticsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `Sync with Hardcover is one switch, named and stateful`() {
        val match = HardcoverMatchedBook(427_578L, "Project Hail Mary", listOf("Andy Weir"), 2021, chosenByYou = true)
        composeRule.setContent {
            MaterialTheme {
                BookHardcoverContent(
                    state = BookHardcoverUiState.Linked(match, HardcoverBookSync.UP_TO_DATE),
                    onFindMatch = {},
                    onRemoveMatch = {},
                    onSetSynced = {},
                )
            }
        }
        val switch = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)
        composeRule.onAllNodes(switch).assertCountEquals(1)
        composeRule
            .onNode(switch)
            .assert(hasText("Sync with Hardcover"))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState))
    }
}
