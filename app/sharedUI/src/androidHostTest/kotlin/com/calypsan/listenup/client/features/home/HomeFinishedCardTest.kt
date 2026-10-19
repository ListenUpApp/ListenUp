package com.calypsan.listenup.client.features.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import com.calypsan.listenup.client.presentation.home.FinishedCard
import io.kotest.matchers.shouldBe
import kotlinx.datetime.TimeZone
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Spec §2.3 JustFinished and §8.2 on Android, with the half-star control Simon chose (Q2). */
@RunWith(RobolectricTestRunner::class)
class HomeFinishedCardTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun render(
        card: FinishedCard,
        actions: HomeActions = HomeActions.None,
    ) = composeRule.setContent {
        MaterialTheme {
            // Scrollable, as Home is, so the lower controls can be scrolled to on a small test window.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                HomeFinishedCard(card, nowMs = BOARD_NOW, zone = TimeZone.UTC, actions = actions)
            }
        }
    }

    @Test
    fun `the card is one group named for what happened`() {
        render(finishedSlot().card)
        composeRule.onNodeWithContentDescription("You finished a book").assertExists()
    }

    @Test
    fun `Play next book plays the next book, not the finished one`() {
        var played: String? = null
        render(finishedSlot(next = nextBook("wor")).card, HomeActions.None.copy(onPlay = { played = it }))
        composeRule.onNodeWithContentDescription("Play next book: Words of Radiance").performScrollTo().performClick()
        played shouldBe "wor"
    }

    @Test
    fun `without a next book there is no primary button and the rating is the action`() {
        render(finishedSlot(next = null).card)
        composeRule.onAllNodesWithText("Play next book").assertCountEquals(0)
        composeRule.onNodeWithText("How was it?").assertIsDisplayed()
    }

    @Test
    fun `the card says when it was finished, how long and when it was read`() {
        render(finishedSlot().card)
        composeRule.onNodeWithText("Finished today").assertIsDisplayed()
        composeRule.onNodeWithText("45h 30m · 6 Sep – 10 Oct").assertIsDisplayed()
    }

    @Test
    fun `an unknown start leaves the length alone`() {
        render(finishedSlot(startedAtMs = null, finishedAtMs = BOARD_NOW - DAY).card)
        composeRule.onNodeWithText("Finished yesterday").assertIsDisplayed()
        composeRule.onNodeWithText("45h 30m").assertIsDisplayed()
    }

    @Test
    fun `dismiss is one labelled button that dismisses everywhere`() {
        var dismissed: String? = null
        render(finishedSlot().card, HomeActions.None.copy(onDismissFinished = { dismissed = it }))
        composeRule
            .onNodeWithContentDescription("Dismiss on all your devices")
            .assertHasClickAction()
            .performClick()
        dismissed shouldBe "wok"
    }

    @Test
    fun `committing the half-star control rates the finished book`() {
        var rated: Pair<String, Int>? = null
        render(finishedSlot().card, HomeActions.None.copy(onRateFinished = { id, h -> rated = id to h }))
        composeRule
            .onNodeWithContentDescription("Rating")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(8f) }
        rated shouldBe ("wok" to 8)
    }

    @Test
    fun `the finishers line names the most recent two and counts the rest, and opens the readers`() {
        var readers: String? = null
        render(
            finishedSlot(names = listOf("Maya", "Tom"), more = 3).card,
            HomeActions.None.copy(onOpenReaders = { readers = it }),
        )
        composeRule.onNodeWithText("Maya, Tom and 3 more finished it too").performScrollTo().performClick()
        readers shouldBe "wok"
    }

    @Test
    fun `one, two and one-plus-more finishers read naturally`() {
        render(finishedSlot(names = listOf("Maya"), more = 2).card)
        composeRule.onNodeWithText("Maya and 2 more finished it too").assertExists()
    }

    @Test
    fun `no names means no finishers line`() {
        render(finishedSlot(names = emptyList(), more = 2).card)
        composeRule.onAllNodesWithText("finished it too", substring = true).assertCountEquals(0)
    }

    @Test
    fun `cover and title are one link into the finished book`() {
        var opened: String? = null
        render(finishedSlot().card, HomeActions.None.copy(onOpenBook = { opened = it }))
        composeRule.onNodeWithTag(HOME_FINISHED_IDENTITY_TAG).assertHasClickAction().performClick()
        opened shouldBe "wok"
    }
}
