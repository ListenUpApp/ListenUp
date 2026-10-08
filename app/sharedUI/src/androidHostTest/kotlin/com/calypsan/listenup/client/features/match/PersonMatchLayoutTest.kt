package com.calypsan.listenup.client.features.match

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.calypsan.listenup.client.presentation.match.PersonFindUiState
import com.calypsan.listenup.client.presentation.match.PersonReviewUiState
import com.calypsan.listenup.client.testing.AtFontScale
import com.calypsan.listenup.client.testing.Windows
import com.calypsan.listenup.client.testing.assertNoMidWordBreaks
import com.calypsan.listenup.client.testing.assertSideBySide
import com.calypsan.listenup.client.testing.assertStacked
import com.calypsan.listenup.client.testing.textLayout
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Person Match details lays out by width, renders dark, and holds at the largest text. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PersonMatchLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val actions = RecordingPersonMatchActions()

    private fun setScreen(
        review: PersonReviewUiState,
        find: PersonFindUiState = PersonMatchFixtures.rayResults.copy(pickedKey = PersonMatchFixtures.rayPorter.key),
        dark: Boolean = false,
        largeText: Boolean = false,
    ) {
        composeRule.setContent {
            val screen = @androidx.compose.runtime.Composable {
                MaterialTheme(colorScheme = if (dark) darkColorScheme() else MaterialTheme.colorScheme) {
                    PersonMatchScreen(
                        contributorId = PersonMatchFixtures.CONTRIBUTOR_ID,
                        findState = find,
                        reviewState = review,
                        viewerId = MatchFixtures.VIEWER_ID,
                        actions = actions,
                        onBack = {},
                        onEditByHand = {},
                    )
                }
            }
            if (largeText) AtFontScale { screen() } else screen()
        }
    }

    @Test
    @Config(qualifiers = Windows.TABLET)
    fun `on a tablet the people sit beside Review and the session hears two panes`() {
        setScreen(PersonMatchFixtures.ready)
        composeRule.waitForIdle()

        assertSideBySide(composeRule.onNodeWithTag(FIND_PANE_TAG), composeRule.onNodeWithTag(REVIEW_PANE_TAG))
        composeRule.onNodeWithTag(APPLY_SUMMARY_TAG).assertIsDisplayed()
        actions.twoPaneReports shouldContainExactly listOf(true)
    }

    @Test
    @Config(qualifiers = Windows.PHONE)
    fun `on a phone Find stands alone until a person is picked`() {
        setScreen(PersonReviewUiState.NoneChosen, find = PersonMatchFixtures.rayResults)

        composeRule.onNodeWithTag(FIND_PANE_TAG).assertIsDisplayed()
        composeRule.onAllNodes(hasTestTag(REVIEW_PANE_TAG)).fetchSemanticsNodes().size shouldBe 0
        actions.twoPaneReports shouldContainExactly listOf(false)
    }

    @Test
    @Config(qualifiers = Windows.PHONE)
    fun `on a phone a picked person shows Review in place of the results`() {
        setScreen(PersonMatchFixtures.ready)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(REVIEW_PANE_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Ray Porter · Review").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = Windows.TABLET)
    fun `the two panes render in the dark theme`() {
        setScreen(PersonMatchFixtures.ready, dark = true)

        composeRule.onNodeWithText("Started from the 5 books crediting Ray Porter in your library.").assertIsDisplayed()
        composeRule.onNodeWithTag(APPLY_SUMMARY_TAG).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = Windows.PHONE)
    fun `at the largest text the Apply bar stacks and keeps Apply changes on one line`() {
        setScreen(PersonMatchFixtures.ready, largeText = true)
        composeRule.waitForIdle()

        assertStacked(composeRule.onNodeWithTag(APPLY_SUMMARY_TAG), composeRule.onNodeWithTag(APPLY_BUTTON_TAG))
        val label = composeRule.onNode(hasText("Apply changes"), useUnmergedTree = true)
        label.assertNoMidWordBreaks()
        label.textLayout().lineCount shouldBe 1
    }

    @Test
    @Config(qualifiers = Windows.PHONE)
    fun `at the largest text the evidence strip reads without mid-word breaks`() {
        setScreen(PersonReviewUiState.NoneChosen, find = PersonMatchFixtures.rayResults, largeText = true)

        composeRule
            .onNode(hasText("Narrated 5 of your books · Translated 1", substring = true), useUnmergedTree = true)
            .assertNoMidWordBreaks()
    }
}
