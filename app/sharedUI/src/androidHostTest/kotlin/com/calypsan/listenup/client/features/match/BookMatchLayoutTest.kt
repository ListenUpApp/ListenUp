package com.calypsan.listenup.client.features.match

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import com.calypsan.listenup.client.presentation.match.ReviewUiState
import com.calypsan.listenup.client.testing.Windows
import com.calypsan.listenup.client.testing.assertSideBySide
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Match details lays out by width: results | review side by side on a tablet, one pane on a phone. */
@RunWith(RobolectricTestRunner::class)
class BookMatchLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val actions = RecordingMatchActions()

    private fun setScreen(
        review: ReviewUiState,
        dark: Boolean = false,
    ) {
        composeRule.setContent {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else MaterialTheme.colorScheme) {
                BookMatchScreen(
                    bookId = MatchFixtures.BOOK_ID,
                    findState = MatchFixtures.results.copy(pickedKey = MatchFixtures.best.key),
                    reviewState = review,
                    viewerId = MatchFixtures.VIEWER_ID,
                    actions = actions,
                    onBack = {},
                )
            }
        }
    }

    @Test
    @Config(qualifiers = Windows.TABLET)
    fun `on a tablet the results sit beside Review and the session hears two panes`() {
        setScreen(MatchFixtures.ready)
        composeRule.waitForIdle()
        actions.twoPaneReports shouldContainExactly listOf(true)

        assertSideBySide(composeRule.onNodeWithTag(FIND_PANE_TAG), composeRule.onNodeWithTag(REVIEW_PANE_TAG))
        composeRule.onNodeWithTag(APPLY_SUMMARY_TAG).assertIsDisplayed()
        actions.twoPaneReports shouldContainExactly listOf(true)
    }

    @Test
    @Config(qualifiers = Windows.PHONE)
    fun `on a phone Find stands alone until a match is picked`() {
        setScreen(ReviewUiState.NoneChosen)

        composeRule.onNodeWithTag(FIND_PANE_TAG).assertIsDisplayed()
        composeRule
            .onAllNodes(
                androidx.compose.ui.test
                    .hasTestTag(REVIEW_PANE_TAG),
            ).fetchSemanticsNodes()
            .size shouldBe 0
        actions.twoPaneReports shouldContainExactly listOf(false)
    }

    @Test
    @Config(qualifiers = Windows.PHONE)
    fun `on a phone a picked match shows Review in place of the results`() {
        setScreen(MatchFixtures.ready)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(REVIEW_PANE_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(APPLY_SUMMARY_TAG).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = Windows.TABLET)
    fun `the two panes render in the dark theme`() {
        setScreen(MatchFixtures.ready, dark = true)

        composeRule.onNodeWithText("Strong match").assertIsDisplayed()
        composeRule.onNodeWithTag(APPLY_SUMMARY_TAG).assertIsDisplayed()
    }
}
