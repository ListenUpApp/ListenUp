package com.calypsan.listenup.client.features.match

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
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

/** Person Find on a phone, from fixed state: results with every role's evidence, no profiles, a failure, a search. */
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
    fun `a Find names the person, what they did in your library, and the people found`() {
        setFind(PersonMatchFixtures.andyResults)

        composeRule.onNodeWithText("Match details").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Search for a person").assertIsDisplayed()
        composeRule
            .onNodeWithText("Wrote 3 of your books: Project Hail Mary, The Martian, Artemis")
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
    fun `there is no role to choose, no As author and no As narrator`() {
        setFind(PersonMatchFixtures.rayResults)

        composeRule.onAllNodes(hasText("As author") or hasContentDescription("As author")).assertCountEquals(0)
        composeRule.onAllNodes(hasText("As narrator") or hasContentDescription("As narrator")).assertCountEquals(0)
        composeRule.onAllNodes(hasText("Match as") or hasContentDescription("Match as")).assertCountEquals(0)
        actions.calls shouldBe emptyList()
    }

    @Test
    fun `a row's accessible name says who, what, what they did here and where it was found, and opens Review`() {
        setFind(PersonMatchFixtures.andyResults)
        scrollTo("Author · The Martian, Artemis")

        composeRule
            .onNode(
                hasClickAction() and
                    hasContentDescription(
                        "Andy Weir. Author · The Martian, Artemis. Wrote 3 of your books. Atlas and Beacon.",
                    ),
            ).performClick()

        actions.calls shouldContain "pick:andy"
    }

    @Test
    fun `every role they hold here is evidence, on the strip and on the row`() {
        setFind(PersonMatchFixtures.rayResults)

        composeRule
            .onNodeWithText(
                "Narrated 5 of your books · Translated 1: Project Hail Mary, We Are Legion, For We Are Many",
            ).assertIsDisplayed()
        composeRule.onNodeWithText("Started from the 5 books crediting Ray Porter in your library.").assertIsDisplayed()
        scrollTo("Narrated 4 of your books · Translated 1")
        composeRule.onNodeWithText("Narrator · Project Hail Mary, Bobiverse").assertIsDisplayed()
        composeRule.onNodeWithText("Narrated 4 of your books · Translated 1").assertIsDisplayed()
        scrollTo("Author · 1 book")
        composeRule.onNodeWithText("Author · 1 book").assertIsDisplayed()
    }

    @Test
    fun `no profiles anywhere says so, for a person not a role, and offers Edit by hand`() {
        setFind(PersonMatchFixtures.noProfiles)

        scrollTo("No source has a profile")
        composeRule.onNode(hasText("No source has a profile for this person") and isHeading()).assertIsDisplayed()
        composeRule.onNodeWithText("You can add their photo and biography yourself.").assertIsDisplayed()
        composeRule.onNodeWithText("Edit by hand").performClick()
        editedByHand shouldBe 1
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
