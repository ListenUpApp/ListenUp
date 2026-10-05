package com.calypsan.listenup.client.features.seriesedit.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.client.presentation.seriesedit.AddSubSeriesEvent
import com.calypsan.listenup.client.presentation.seriesedit.AddSubSeriesUiState
import com.calypsan.listenup.client.presentation.seriesedit.ExistingSeriesMatch
import com.calypsan.listenup.client.presentation.seriesedit.NewSeriesDraft
import com.calypsan.listenup.client.presentation.seriesedit.PendingSubSeriesMove
import com.calypsan.listenup.client.presentation.seriesedit.SubSeriesCandidateUi
import com.calypsan.listenup.client.presentation.seriesedit.SubSeriesPlacement
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * "Add sub-series to Cosmere": each candidate says where it sits today, the one already here can't
 * be chosen, a move out of another parent asks first, and a taken name offers the existing series.
 */
@RunWith(RobolectricTestRunner::class)
class AddSubSeriesSheetTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val events = mutableListOf<AddSubSeriesEvent>()

    private fun open(
        pendingMove: PendingSubSeriesMove? = null,
        newSeries: NewSeriesDraft? = null,
    ) = AddSubSeriesUiState.Open(
        parentName = "Cosmere",
        query = "",
        candidates = listOf(WHITE_SAND, CITY_WATCH, MISTBORN),
        pendingMove = pendingMove,
        newSeries = newSeries,
        isBusy = false,
        error = null,
    )

    private fun renderContent(state: AddSubSeriesUiState.Open) {
        composeRule.setContent { MaterialTheme { AddSubSeriesSheetContent(state = state, onEvent = { events += it }) } }
    }

    private fun renderSheet(state: AddSubSeriesUiState) {
        composeRule.setContent { MaterialTheme { AddSubSeriesSheet(state = state, onEvent = { events += it }) } }
    }

    @Test
    fun `each candidate says where it sits today`() {
        renderContent(open())

        composeRule.onNodeWithText("Add sub-series to Cosmere").assertExists()
        composeRule.onNodeWithText("Top level · 3 books").assertExists()
        composeRule.onNodeWithText("In Discworld · moves it here").assertExists()
        composeRule.onNodeWithText("Already in Cosmere").assertExists()
    }

    @Test
    fun `choosing a candidate sends it, and the one already here does nothing`() {
        renderContent(open())

        composeRule.onNodeWithText("City Watch").performClick()
        composeRule.onNodeWithText("Mistborn").performClick()

        events shouldContain AddSubSeriesEvent.Chosen("city-watch")
        events shouldNotContain AddSubSeriesEvent.Chosen("mistborn")
    }

    @Test
    fun `New series opens the name dialog`() {
        renderContent(open())

        composeRule.onNodeWithText("New series…").performClick()

        events shouldContain AddSubSeriesEvent.NewSeriesStarted
    }

    @Test
    fun `a move out of another parent asks first`() {
        renderSheet(open(pendingMove = PendingSubSeriesMove("city-watch", "City Watch", "Discworld", "Cosmere")))

        composeRule.onNodeWithText("Move City Watch out of Discworld into Cosmere?").assertExists()
        composeRule.onNodeWithText("Move").performClick()

        events shouldContain AddSubSeriesEvent.MoveConfirmed
    }

    @Test
    fun `the new series dialog says what Create does`() {
        renderSheet(open(newSeries = NewSeriesDraft(name = "Warbreaker")))

        composeRule.onNodeWithText("Creates “Warbreaker” inside Cosmere.").assertExists()
        composeRule.onNodeWithText("Create").performClick()

        events shouldContain AddSubSeriesEvent.NewSeriesConfirmed
    }

    @Test
    fun `a taken name offers the existing series instead of creating`() {
        val existing = ExistingSeriesMatch(id = "white-sand", name = "White Sand", isSelectable = true)
        renderSheet(open(newSeries = NewSeriesDraft(name = "white sand", existing = existing)))

        composeRule.onNodeWithText("“White Sand” already exists.").assertExists()
        composeRule.onNodeWithText("Create").assertIsNotEnabled()
        composeRule.onNodeWithText("Add it instead").performClick()

        events shouldContain AddSubSeriesEvent.Chosen("white-sand")
    }

    @Test
    fun `a refusal is acknowledged once`() {
        renderSheet(
            AddSubSeriesUiState.Closed(
                error =
                    com.calypsan.listenup.api.error.TransportError
                        .NetworkUnavailable(),
            ),
        )

        composeRule.waitForIdle()
        events shouldContain AddSubSeriesEvent.ErrorDismissed
    }

    private companion object {
        val WHITE_SAND =
            SubSeriesCandidateUi("white-sand", "White Sand", null, 3, 0, SubSeriesPlacement.TOP_LEVEL, null)
        val CITY_WATCH =
            SubSeriesCandidateUi("city-watch", "City Watch", null, 8, 0, SubSeriesPlacement.IN_OTHER_PARENT, "Discworld")
        val MISTBORN =
            SubSeriesCandidateUi("mistborn", "Mistborn", null, 8, 2, SubSeriesPlacement.ALREADY_HERE, "Cosmere")
    }
}
