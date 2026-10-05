package com.calypsan.listenup.client.features.seriesedit.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.client.presentation.seriesedit.SeriesCandidate
import com.calypsan.listenup.client.presentation.seriesedit.SeriesEditUiEvent
import com.calypsan.listenup.client.presentation.seriesedit.SeriesEditUiState
import com.calypsan.listenup.core.SeriesId
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The editor's "Place in library": the parent and Move into…, the sub-series in order with every way
 * to reorder them, and — offline — a banner and nothing that would need the server.
 */
@RunWith(RobolectricTestRunner::class)
class PlaceInLibraryTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val events = mutableListOf<SeriesEditUiEvent>()
    private var addClicks = 0

    private fun render(state: SeriesEditUiState) {
        composeRule.setContent {
            MaterialTheme {
                PlaceInLibrary(state = state, onEvent = { events += it }, onAddSubSeries = { addClicks++ })
            }
        }
    }

    @Test
    fun `it names the parent, and Move into opens the picker`() {
        render(MISTBORN)

        composeRule.onNodeWithText("Cosmere").assertExists()
        composeRule.onNodeWithText("Move into…").performClick()
        composeRule.onNodeWithText("Saved as soon as you choose. Needs a connection to the server.").assertExists()

        events shouldContain SeriesEditUiEvent.ParentPickerOpened
    }

    @Test
    fun `a top-level series says so`() {
        render(MISTBORN.copy(parentId = null, parentName = null))

        composeRule.onNodeWithText("Top level").assertExists()
    }

    @Test
    fun `Move later sends the whole new order and announces it`() {
        render(MISTBORN)

        composeRule.onAllNodesWithContentDescription("Move later").onFirst().performClick()

        events shouldContain SeriesEditUiEvent.ChildSeriesReordered(listOf("era2", "era1"))
        composeRule.onNode(hasContentDescription("Mistborn Era 1 moved to position 2 of 2")).assertExists()
    }

    @Test
    fun `the first row cannot move earlier and the last cannot move later`() {
        render(MISTBORN)

        composeRule.onAllNodesWithContentDescription("Move earlier").onFirst().assertIsNotEnabled()
        composeRule.onAllNodesWithContentDescription("Move later").onLast().assertIsNotEnabled()
    }

    @Test
    fun `each row offers Move earlier and Move later to TalkBack`() {
        render(MISTBORN)

        val laterAction =
            composeRule
                .onNode(hasCustomAction("Move later"))
                .fetchSemanticsNode()
                .config[SemanticsActions.CustomActions]
                .single { it.label == "Move later" }
        laterAction.action() shouldBe true

        events shouldContain SeriesEditUiEvent.ChildSeriesReordered(listOf("era2", "era1"))
    }

    @Test
    fun `offline shows the banner and disables every hierarchy control`() {
        render(MISTBORN.copy(isOnline = false))

        composeRule.onNodeWithText("You're offline").assertExists()
        composeRule.onNodeWithText("Move into…").assertIsNotEnabled()
        composeRule.onNodeWithText("Add sub-series").assertIsNotEnabled()
        composeRule.onAllNodesWithContentDescription("Move later").onFirst().assertIsNotEnabled()
        composeRule.onNode(hasCustomAction("Move later")).assertDoesNotExist()
    }

    @Test
    fun `Add sub-series opens the sheet`() {
        render(MISTBORN)

        composeRule.onNodeWithText("Add sub-series").assertIsEnabled().performClick()

        addClicks shouldBe 1
    }

    private fun hasCustomAction(label: String) =
        SemanticsMatcher("has custom action $label") { node ->
            node.config.getOrElseNullable(SemanticsActions.CustomActions) { null }?.any { it.label == label } == true
        }

    private companion object {
        val MISTBORN =
            SeriesEditUiState(
                isLoading = false,
                seriesId = "mistborn",
                name = "Mistborn",
                parentId = "cosmere",
                parentName = "Cosmere",
                childSeries =
                    listOf(
                        SeriesCandidate(SeriesId("era1"), "Mistborn Era 1", 4),
                        SeriesCandidate(SeriesId("era2"), "Mistborn Era 2", 4),
                    ),
            )
    }
}
