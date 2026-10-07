package com.calypsan.listenup.client.features.match

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.metadata.BookField
import com.calypsan.listenup.client.presentation.match.ApplySummary
import com.calypsan.listenup.client.presentation.match.ReviewUiState
import com.calypsan.listenup.client.testing.Windows
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Review on a phone from fixed state: sections in order, default ticks, the hand-edit flag, the Apply bar. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Windows.PHONE)
class BookMatchReviewTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val actions = RecordingMatchActions()

    private fun setReview(state: ReviewUiState) {
        composeRule.setContent {
            MaterialTheme {
                ReviewPane(
                    state = state,
                    bookId = MatchFixtures.BOOK_ID,
                    bookTitle = "Project Hail Mary",
                    viewerId = MatchFixtures.VIEWER_ID,
                    isTwoPane = false,
                    actions = actions,
                )
            }
        }
    }

    private fun scrollTo(text: String) {
        composeRule.onNode(hasScrollAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.CollectionInfo)).performScrollToNode(hasText(text, substring = true))
    }

    private fun scrollToTag(tag: String) {
        composeRule.onNode(hasScrollAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.CollectionInfo)).performScrollToNode(hasTestTag(tag))
    }

    @Test
    fun `every non-empty section renders as a heading, in the canvas order`() {
        setReview(MatchFixtures.ready)

        val headings =
            listOf("Cover", "Changes", "Fills a gap", "You edited this", "Genres & moods", "Chapter names", "Already the same")
        headings.forEach { heading ->
            scrollTo(heading)
            composeRule.onNode(isHeading() and hasText(heading)).assertIsDisplayed()
        }
    }

    @Test
    fun `an empty section is not rendered`() {
        setReview(MatchFixtures.ready.copy(fillsGap = emptyList()))

        composeRule.onAllNodes(isHeading() and hasText("Fills a gap")).fetchSemanticsNodes().size shouldBe 0
    }

    @Test
    fun `changes and gaps start ticked, a hand edit starts unticked and says who edited it`() {
        setReview(MatchFixtures.ready)

        scrollToTag(fieldTag(BookField.DESCRIPTION))
        composeRule.onNodeWithTag(fieldTag(BookField.DESCRIPTION)).assertIsOn()
        scrollToTag(fieldTag(BookField.PUBLISHER))
        composeRule.onNodeWithTag(fieldTag(BookField.PUBLISHER)).assertIsOn()
        scrollToTag(fieldTag(BookField.SUBTITLE))
        composeRule.onNodeWithTag(fieldTag(BookField.SUBTITLE)).assertIsOff()
        scrollTo("Edited by you")
        composeRule.onNodeWithText("Edited by you. Kept unless you tick it.", substring = true).assertIsDisplayed()
        composeRule.onAllNodes(hasText("You edited this")).fetchSemanticsNodes().size shouldBe 2
    }

    @Test
    fun `a field's accessible name says what it is, where it comes from and what it does`() {
        setReview(MatchFixtures.ready)
        scrollToTag(fieldTag(BookField.DESCRIPTION))

        composeRule
            .onNodeWithTag(fieldTag(BookField.DESCRIPTION))
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ContentDescription,
                    listOf("Description, proposed from Atlas, changes yours"),
                ),
            )
    }

    @Test
    fun `ticking a field asks the session to write it`() {
        setReview(MatchFixtures.ready)
        scrollToTag(fieldTag(BookField.SUBTITLE))

        composeRule.onNodeWithTag(fieldTag(BookField.SUBTITLE)).performClick()

        actions.calls shouldContain "tick:SUBTITLE:true"
    }

    @Test
    fun `description HTML reads as plain text`() {
        setReview(MatchFixtures.ready)
        scrollTo("Ryland Grace")

        composeRule.onNodeWithText("Ryland Grace is the sole survivor.").assertIsDisplayed()
    }

    @Test
    fun `the Apply bar counts what Apply writes`() {
        setReview(MatchFixtures.ready)

        composeRule.onNodeWithTag(APPLY_SUMMARY_TAG).assertTextEquals("2 fields · cover · 16 chapter names")
        composeRule.onNodeWithTag(APPLY_BUTTON_TAG).assertIsEnabled().performClick()
        actions.calls shouldContain "apply"
    }

    @Test
    fun `the Apply bar follows the ticks`() {
        setReview(MatchFixtures.ready.copy(applyBar = ApplySummary(fieldCount = 1, coverChanges = false, chapterNameCount = 0)))

        composeRule.onNodeWithTag(APPLY_SUMMARY_TAG).assertTextEquals("1 field")
    }

    @Test
    fun `nothing selected disables Apply`() {
        setReview(MatchFixtures.ready.copy(applyBar = ApplySummary(fieldCount = 0, coverChanges = false, chapterNameCount = 0)))

        composeRule.onNodeWithTag(APPLY_SUMMARY_TAG).assertTextEquals("Nothing selected")
        composeRule.onNodeWithTag(APPLY_BUTTON_TAG).assertIsNotEnabled()
    }

    @Test
    fun `while applying the bar says so and Apply is disabled`() {
        setReview(MatchFixtures.ready.copy(applying = true))

        composeRule.onNodeWithTag(APPLY_SUMMARY_TAG).assertTextEquals("Applying…")
        composeRule.onNodeWithTag(APPLY_BUTTON_TAG).assertIsNotEnabled()
    }

    @Test
    fun `an Apply error sits above the bar and says nothing was changed`() {
        setReview(MatchFixtures.ready.copy(applyError = TransportError.NetworkUnavailable()))

        composeRule.onNodeWithText("Nothing was changed.", substring = true).assertIsDisplayed()
    }

    @Test
    fun `chapter names say how many get names and how many already match`() {
        setReview(MatchFixtures.ready)
        scrollTo("chapters get names")

        composeRule.onNodeWithText("16 of 36 chapters get names from Atlas. The other 20 already match.").assertIsDisplayed()
    }

    @Test
    fun `already the same is one collapsed line that includes Length`() {
        setReview(MatchFixtures.ready)
        scrollTo("fields already match")

        composeRule.onNodeWithText("3 fields already match: Title, Authors, Length").assertIsDisplayed()
    }

    @Test
    fun `your genre is a removable chip named for what it removes`() {
        setReview(MatchFixtures.ready)
        scrollTo("Science Fiction")

        composeRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Remove Science Fiction"))).performClick()

        actions.calls shouldContain "remove:GENRES:Science Fiction"
    }

    @Test
    fun `the cover choices are one radio group with Keep current`() {
        setReview(MatchFixtures.ready)

        val radios =
            composeRule
                .onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
                .fetchSemanticsNodes()
        radios.size shouldBe 2
        composeRule.onNodeWithText("Keep current").assertIsDisplayed()
    }

    @Test
    fun `the chapter names checkbox reports its state`() {
        setReview(MatchFixtures.ready)
        scrollTo("Apply chapter names")

        composeRule
            .onNode(
                SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On) and
                    hasText("Apply chapter names", substring = true),
            ).assertIsDisplayed()
    }
}
