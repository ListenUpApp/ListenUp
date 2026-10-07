package com.calypsan.listenup.client.features.match

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasScrollAction
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.client.features.match.MatchFixtures.ATLAS
import com.calypsan.listenup.client.presentation.match.FindFailure
import com.calypsan.listenup.client.presentation.match.FindUiState
import com.calypsan.listenup.client.testing.Windows
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Find on a phone, rendered from fixed state: results, a running search, the partial banner, every failure. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Windows.PHONE)
class BookMatchFindTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val actions = RecordingMatchActions()

    private fun setFind(state: FindUiState) {
        composeRule.setContent {
            MaterialTheme {
                FindPane(state = state, bookId = MatchFixtures.BOOK_ID, highlightPicked = false, actions = actions, onBack = {})
            }
        }
    }

    private fun scrollTo(text: String) {
        composeRule
            .onNode(
                hasScrollAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.CollectionInfo),
            ).performScrollToNode(hasText(text, substring = true))
    }

    @Test
    fun `results show the store, your copy, then Strong and Maybe groups with badges and reasons`() {
        setFind(MatchFixtures.results)

        composeRule.onNodeWithText("Atlas store: ", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Started from your Atlas link, then title, author and length.").assertIsDisplayed()
        scrollTo("Strong match")
        composeRule.onNodeWithText("Strong match").assertIsDisplayed()
        composeRule.onNodeWithText("Best match").assertIsDisplayed()
        composeRule.onNodeWithText("Your current link").assertIsDisplayed()
        composeRule.onNodeWithText("Atlas · Beacon · Compass").assertIsDisplayed()
        scrollTo("Maybe")
        composeRule.onNodeWithText("Maybe").assertIsDisplayed()
    }

    @Test
    fun `tapping a row opens its Review, named for the match and where it was found`() {
        setFind(MatchFixtures.results)
        scrollTo("Best match")

        composeRule
            .onNode(hasClickAction() and hasText("Best match"))
            .performClick()

        actions.calls shouldContain "pick:best"
    }

    @Test
    fun `a running search keeps the previous results on screen and says it is searching`() {
        setFind(FindUiState.Searching(MatchFixtures.yourCopy, "Hail Mary", previous = MatchFixtures.results))

        composeRule.onNodeWithText("Searching…").assertIsDisplayed()
        scrollTo("Best match")
        composeRule.onNodeWithText("Best match").assertIsDisplayed()
    }

    @Test
    fun `a partial answer names the silent source and retries it`() {
        setFind(MatchFixtures.results.copy(partialFailure = MatchFixtures.partial))
        scrollTo("Retry Compass")

        composeRule
            .onNodeWithText("Compass didn't answer, so these results are from Atlas and Beacon.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Retry Compass").performClick()

        actions.calls shouldContain "retry"
    }

    @Test
    fun `offline says so and offers Retry`() {
        setFind(failed(FindFailure.Offline))

        composeRule.onNodeWithText("You're offline").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()
        actions.calls shouldContain "retry"
    }

    @Test
    fun `a timeout names the source`() {
        setFind(failed(FindFailure.TimedOut(ATLAS)))

        composeRule.onNodeWithText("Atlas didn't answer in time").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").assertIsEnabled()
    }

    @Test
    fun `a rate limit holds Retry disabled with a countdown`() {
        setFind(failed(FindFailure.RateLimited(ATLAS, secondsRemaining = 30)))

        composeRule.onNodeWithText("Atlas asked us to slow down").assertIsDisplayed()
        composeRule.onNodeWithText("Retry in 0:30").assertIsNotEnabled()
    }

    @Test
    fun `a rate limit that has run out lets you retry`() {
        setFind(failed(FindFailure.RateLimited(ATLAS, secondsRemaining = 0)))

        composeRule.onNodeWithText("Retry").assertIsEnabled()
    }

    @Test
    fun `not found in a store offers at most two other stores and a title search`() {
        val suggestions = listOf(MetadataLocale(region = "uk"), MetadataLocale(region = "ca"), MetadataLocale(region = "au"))
        setFind(failed(FindFailure.NotFoundInStore(ATLAS, MetadataLocale(region = "us"), suggestions)))

        val uk = MetadataLocale(region = "uk").displayName
        composeRule.onNodeWithText("Try $uk").performClick()
        composeRule.onNodeWithText("Try ${MetadataLocale(region = "ca").displayName}").assertIsDisplayed()
        composeRule.onAllNodes(hasText("Try ", substring = true)).fetchSemanticsNodes().size shouldBe 2
        composeRule.onNodeWithText("Search by title").performClick()

        actions.calls shouldBe listOf("chooseStore:uk", "searchByTitle")
    }

    @Test
    fun `a failed source names it`() {
        setFind(failed(FindFailure.SourceFailed(ATLAS)))

        composeRule.onNodeWithText("Atlas didn't answer").assertIsDisplayed()
    }

    @Test
    fun `nothing found offers a title search`() {
        setFind(failed(FindFailure.NothingFound))

        composeRule.onNodeWithText("No matches").assertIsDisplayed()
        composeRule.onNodeWithText("Search by title").assertIsDisplayed()
    }

    @Test
    fun `an unexpected failure says so and offers Retry`() {
        setFind(failed(FindFailure.Unexpected(TransportError.NetworkUnavailable())))

        composeRule.onNodeWithText("Something went wrong").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").assertIsEnabled()
    }

    @Test
    fun `Compare puts your copy beside the match from Find's data and leads to Review`() {
        setFind(MatchFixtures.results)
        scrollTo("Maybe")

        composeRule.onNode(hasContentDescription("Compare Project Hail Mary (Abridged) with your copy")).performClick()

        composeRule.onNodeWithText("Compare with your copy").assertIsDisplayed()
        composeRule.onAllNodes(hasText("Not listed")).fetchSemanticsNodes().isNotEmpty() shouldBe true
        composeRule.onNodeWithText("Review this match").performClick()

        actions.calls shouldContain "pick:maybe"
    }

    private fun failed(failure: FindFailure) =
        FindUiState.Failed(yourCopy = MatchFixtures.yourCopy, query = "Hail Mary", failure = failure, region = MatchFixtures.region)
}
