package com.calypsan.listenup.client.features.seriesedit.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.client.presentation.seriesedit.ExistingSeriesMatch
import com.calypsan.listenup.client.presentation.seriesedit.NewSeriesDraft
import com.calypsan.listenup.client.presentation.seriesedit.ParentPickerDisabledReason
import com.calypsan.listenup.client.presentation.seriesedit.ParentPickerRow
import com.calypsan.listenup.client.presentation.seriesedit.SeriesEditUiEvent
import com.calypsan.listenup.client.presentation.seriesedit.SeriesEditUiState
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * "Move “Mistborn” into…": the tree keeps what would loop, greyed and saying why, and a taken name in
 * "New parent series" offers to move into the existing one instead of failing.
 */
@RunWith(RobolectricTestRunner::class)
class MoveIntoPickerTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val events = mutableListOf<SeriesEditUiEvent>()

    private fun render(
        state: SeriesEditUiState = MISTBORN,
        rows: List<ParentPickerRow> = TREE,
    ) {
        composeRule.setContent {
            MaterialTheme { MoveIntoPicker(state = state, rows = rows, onEvent = { events += it }) }
        }
    }

    @Test
    fun `disabled rows stay in the tree and carry their reason`() {
        render()

        composeRule.onNodeWithText("Move “Mistborn” into…").assertExists()
        composeRule.onNodeWithContentDescription("Cosmere, Current", substring = true).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Mistborn, This series", substring = true).assertIsNotEnabled()
        composeRule
            .onNodeWithContentDescription("Mistborn Era 1, Inside Mistborn", substring = true)
            .assertIsNotEnabled()
            .performClick()

        events shouldNotContain SeriesEditUiEvent.ParentSelected("era1")
    }

    @Test
    fun `choosing an open row moves the series there`() {
        render()

        composeRule.onNodeWithContentDescription("The Stormlight Archive", substring = true).performClick()

        events shouldContain SeriesEditUiEvent.ParentSelected("stormlight")
    }

    @Test
    fun `a chevron expands its node`() {
        render()

        composeRule.onNodeWithContentDescription("Collapse Cosmere").performClick()

        events shouldContain SeriesEditUiEvent.ParentPickerNodeToggled("cosmere")
    }

    @Test
    fun `the pinned rows clear the parent or start a new one`() {
        render()

        composeRule.onNodeWithContentDescription("Top level (no parent)").performClick()
        composeRule.onNodeWithContentDescription("New parent series…").performClick()

        events shouldContainExactly listOf(SeriesEditUiEvent.ParentCleared, SeriesEditUiEvent.NewParentStarted)
    }

    @Test
    fun `a search result says where it sits`() {
        render(
            state = MISTBORN.copy(parentQuery = "era"),
            rows = listOf(TREE[2].copy(depth = 0)),
        )

        composeRule.onNodeWithContentDescription("in Cosmere › Mistborn · 4 books", substring = true).assertExists()
    }

    @Test
    fun `a search with no match says so`() {
        render(state = MISTBORN.copy(parentQuery = "zzz"), rows = emptyList())

        composeRule.onNodeWithText("No series match that search.").assertExists()
    }

    @Test
    fun `a taken parent name offers to move into it instead`() {
        val existing = ExistingSeriesMatch(id = "stormlight", name = "The Stormlight Archive", isSelectable = true)
        composeRule.setContent {
            MaterialTheme {
                NewParentDialog(
                    state = MISTBORN.copy(newParent = NewSeriesDraft("the stormlight archive", existing)),
                    onEvent = { events += it },
                )
            }
        }

        composeRule.onNodeWithText("Create and move").assertIsNotEnabled()
        composeRule.onNodeWithText("Move into it instead").performClick()

        events shouldContainExactly
            listOf(SeriesEditUiEvent.ParentSelected("stormlight"), SeriesEditUiEvent.NewParentDismissed)
    }

    @Test
    fun `a new parent name says what Create and move does`() {
        composeRule.setContent {
            MaterialTheme {
                NewParentDialog(state = MISTBORN.copy(newParent = NewSeriesDraft("Scadrial")), onEvent = { events += it })
            }
        }

        composeRule.onNodeWithText("Creates “Scadrial” and moves Mistborn into it.").assertExists()
        composeRule.onNodeWithText("Create and move").performClick()

        events shouldContain SeriesEditUiEvent.NewParentConfirmed
    }

    private companion object {
        val MISTBORN =
            SeriesEditUiState(
                isLoading = false,
                seriesId = "mistborn",
                name = "Mistborn",
                parentId = "cosmere",
                parentName = "Cosmere",
                parentPickerVisible = true,
            )

        fun row(
            id: String,
            name: String,
            depth: Int,
            path: List<String>,
            subSeries: Int = 0,
            reason: ParentPickerDisabledReason? = null,
        ) = ParentPickerRow(
            id = id,
            name = name,
            depth = depth,
            pathNames = path,
            bookCount = 4,
            subSeriesCount = subSeries,
            isExpanded = subSeries > 0,
            disabledReason = reason,
        )

        val TREE =
            listOf(
                row("cosmere", "Cosmere", 0, emptyList(), subSeries = 2, reason = ParentPickerDisabledReason.CURRENT_PARENT),
                row("mistborn", "Mistborn", 1, listOf("Cosmere"), subSeries = 1, reason = ParentPickerDisabledReason.THIS_SERIES),
                row("era1", "Mistborn Era 1", 2, listOf("Cosmere", "Mistborn"), reason = ParentPickerDisabledReason.INSIDE_THIS_SERIES),
                row("stormlight", "The Stormlight Archive", 1, listOf("Cosmere")),
            )
    }
}
