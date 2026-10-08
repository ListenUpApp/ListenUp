package com.calypsan.listenup.client.features.match

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.calypsan.listenup.client.presentation.match.PersonFindUiState
import com.calypsan.listenup.client.testing.Windows
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Person Find on a phone, from fixed state: author and narrator results, no profiles, a failure, a search. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Windows.PHONE)
class PersonMatchFindTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val actions = RecordingPersonMatchActions()
    private var editedByHand = 0

    private fun setFind(state: PersonFindUiState) {
        composeRule.setContent {
            MaterialTheme {
                PersonFindPane(
                    state = state,
                    highlightPicked = false,
                    actions = actions,
                    onBack = {},
                    onEditByHand = { editedByHand++ },
                )
            }
        }
    }

    private fun scrollTo(text: String) {
        composeRule
            .onNode(hasScrollAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.CollectionInfo))
            .performScrollToNode(hasText(text, substring = true))
    }

    @Test
    fun `an author Find names the person, the role, their books here and the people found`() {
        setFind(PersonMatchFixtures.authorResults)

        composeRule.onNodeWithText("Match details").assertIsDisplayed()
        composeRule.onNodeWithText("Andy Weir · author").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("As author").assertIsOn()
        composeRule.onNodeWithContentDescription("Search for a person").assertIsDisplayed()
        composeRule
            .onNodeWithText("Wrote 3 books in your library: Project Hail Mary, The Martian, Artemis")
            .assertIsDisplayed()
        scrollTo("Strong match")
        composeRule.onNode(hasText("Strong match") and isHeading()).assertIsDisplayed()
        composeRule.onNodeWithText("Author · The Martian, Artemis").assertIsDisplayed()
        composeRule.onNodeWithText("Atlas · Beacon").assertIsDisplayed()
        scrollTo("Author · 1 book")
        composeRule.onNodeWithText("No books in your library").assertIsDisplayed()
        composeRule.onNodeWithText("Author · 1 book").assertIsDisplayed()
    }

    @Test
    fun `a row's accessible name says who, what, how many here and where it was found, and opens Review`() {
        setFind(PersonMatchFixtures.authorResults)
        scrollTo("Author · The Martian, Artemis")

        composeRule
            .onNode(
                hasClickAction() and
                    hasContentDescription(
                        "Andy Weir. Author · The Martian, Artemis. Wrote 3 books in your library. Atlas and Beacon.",
                    ),
            ).performClick()

        actions.calls shouldContain "pick:andy"
    }

    @Test
    fun `As narrator switches the role`() {
        setFind(PersonMatchFixtures.authorResults)

        composeRule.onNodeWithContentDescription("As narrator").performClick()

        actions.calls shouldContain "role:narrator"
    }

    @Test
    fun `a narrator Find explains the coverage, starts from your books, and flags a different role`() {
        setFind(PersonMatchFixtures.narratorResults)

        composeRule.onNodeWithText("Ray Porter · narrator").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("As narrator").assertIsOn()
        composeRule.onNodeWithContentDescription("Search for a narrator").assertIsDisplayed()
        composeRule.onNodeWithText("Atlas has no narrator profiles, so this search uses Beacon.").assertIsDisplayed()
        composeRule.onNodeWithText("Started from the 5 books Ray Porter narrates in your library.").assertIsDisplayed()
        scrollTo("Narrated 5 books in your library")
        composeRule.onNodeWithText("Narrator · Project Hail Mary, Bobiverse").assertIsDisplayed()
        scrollTo("Different role")
        composeRule.onNodeWithText("Different role").assertIsDisplayed()
        composeRule.onNodeWithText("Author · 1 book · Not a narrator").assertIsDisplayed()
    }

    @Test
    fun `no profiles anywhere says so and offers Edit by hand, with the role switch still there`() {
        setFind(PersonMatchFixtures.noProfiles)

        scrollTo("No source has a profile")
        composeRule.onNode(hasText("No source has a profile for this narrator") and isHeading()).assertIsDisplayed()
        composeRule.onNodeWithText("You can add their photo and biography yourself.").assertIsDisplayed()
        composeRule.onNodeWithText("Edit by hand").performClick()
        editedByHand shouldBe 1

        composeRule.onNodeWithContentDescription("As author").performClick()
        actions.calls shouldContain "role:author"
    }

    @Test
    fun `a failure explains itself and offers Retry`() {
        setFind(PersonMatchFixtures.timedOut)

        scrollTo("Beacon didn't answer in time")
        composeRule.onNodeWithText("Retry").performClick()

        actions.calls shouldContain "retry"
    }

    @Test
    fun `a running search says so`() {
        setFind(PersonMatchFixtures.searching)

        composeRule.onNodeWithText("Searching…").assertIsDisplayed()
    }
}
