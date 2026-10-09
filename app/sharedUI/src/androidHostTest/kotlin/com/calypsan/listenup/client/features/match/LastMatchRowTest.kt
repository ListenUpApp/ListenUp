package com.calypsan.listenup.client.features.match

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.client.presentation.bookdetail.LastMatchUi
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.time.Clock

/** Book Detail's last-match row: when it was matched and by whom, See what changed, and Undo last match. */
@RunWith(RobolectricTestRunner::class)
class LastMatchRowTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var seen = 0
    private var undos = 0

    private val now = Clock.System.now().toEpochMilliseconds()
    private val row =
        LastMatchUi(
            receipt = MatchFixtures.receipt,
            appliedAtMs = now,
            matchedBy = null,
            showingChanges = false,
            undoing = false,
            undoError = null,
        )

    private fun setRow(lastMatch: LastMatchUi) {
        composeRule.setContent {
            MaterialTheme {
                LastMatchRow(
                    lastMatch = lastMatch,
                    onSeeWhatChanged = { seen++ },
                    onCloseWhatChanged = {},
                    onUndo = { undos++ },
                )
            }
        }
    }

    @Test
    fun `a match you just made reads Details matched just now, with both actions`() {
        setRow(row)

        composeRule.onNodeWithText("Details matched just now").assertIsDisplayed()
        composeRule.onNodeWithText("See what changed").performClick()
        composeRule.onNodeWithText("Undo last match").performClick()

        seen shouldBe 1
        undos shouldBe 1
    }

    @Test
    fun `an older match says how long ago`() {
        setRow(row.copy(appliedAtMs = now - 2 * 24 * 60 * 60 * 1000L))

        composeRule.onNodeWithText("Details matched 2d ago").assertIsDisplayed()
    }

    @Test
    fun `the row names whoever matched the book when it wasn't you`() {
        setRow(row.copy(appliedAtMs = now - 3 * 60 * 60 * 1000L, matchedBy = "Sam"))

        composeRule.onNodeWithText("Details matched 3h ago by Sam").assertIsDisplayed()
    }

    @Test
    fun `while undoing, Undo says so and neither action can be tapped`() {
        setRow(row.copy(undoing = true))

        composeRule.onNodeWithText("Undoing…").assertIsNotEnabled()
        composeRule.onNodeWithText("See what changed").assertIsNotEnabled()
    }

    @Test
    fun `a failed Undo says why and keeps the row`() {
        setRow(row.copy(undoError = TransportError.NetworkUnavailable()))

        composeRule.onNodeWithText("Can't reach the server. Check your connection.").assertIsDisplayed()
        composeRule.onNodeWithText("Undo last match").assertIsDisplayed()
    }

    @Test
    fun `See what changed opens the receipt's own list of changes`() {
        setRow(row.copy(showingChanges = true))

        composeRule.onNodeWithText("What changed").assertIsDisplayed()
        composeRule.onNodeWithText("Description · from Atlas").assertIsDisplayed()
        composeRule.onNodeWithText("Cover · from Beacon").assertIsDisplayed()
        composeRule.onNodeWithText("16 chapter names · from Atlas").assertIsDisplayed()
    }
}
