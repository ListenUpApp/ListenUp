package com.calypsan.listenup.client.features.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Spec §2.3 NothingInProgress and NewListener, and H17 (Browse the Library in both) on Android. */
@RunWith(RobolectricTestRunner::class)
class HomeQuietSlotsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `a series candidate plays from Home and Browse the Library sits beside it`() {
        var played: String? = null
        var browsed = false
        composeRule.setContent {
            MaterialTheme {
                HomeNothingInProgress(
                    nothingSlot(nextBook("wor")),
                    HomeActions.None.copy(onPlay = { played = it }, onOpenLibrary = { browsed = true }),
                )
            }
        }
        composeRule.onNodeWithText("Nothing in progress").assertIsDisplayed()
        composeRule.onNodeWithText("Next in a series you're reading").assertIsDisplayed()
        composeRule.onNodeWithText("Read by Michael Kramer & Kate Reading").assertIsDisplayed()
        composeRule.onNodeWithText("Stormlight Archive, Book 2 · 48h 15m").assertIsDisplayed()
        composeRule.onNodeWithText("Play").performClick()
        composeRule.onNodeWithText("Browse the Library").performClick()
        played shouldBe "wor"
        browsed shouldBe true
    }

    @Test
    fun `a candidate without a number reads its series and length`() {
        composeRule.setContent {
            MaterialTheme { HomeNothingInProgress(nothingSlot(nextBook(sequenceLabel = null)), HomeActions.None) }
        }
        composeRule.onNodeWithText("Stormlight Archive · 48h 15m").assertIsDisplayed()
    }

    @Test
    fun `the candidate's cover and title open the book`() {
        var opened: String? = null
        composeRule.setContent {
            MaterialTheme { HomeNothingInProgress(nothingSlot(), HomeActions.None.copy(onOpenBook = { opened = it })) }
        }
        composeRule.onNodeWithText("Words of Radiance").performClick()
        opened shouldBe "wor"
    }

    @Test
    fun `without a candidate it offers the Library and Discover`() {
        var discovered = false
        composeRule.setContent {
            MaterialTheme { HomeNothingInProgress(nothingSlot(null), HomeActions.None.copy(onOpenDiscover = { discovered = true })) }
        }
        composeRule.onAllNodesWithText("Play").assertCountEquals(0)
        composeRule.onNodeWithText("Browse the Library").assertHasClickAction()
        composeRule.onNodeWithText("Discover").performClick()
        discovered shouldBe true
    }

    @Test
    fun `the welcome invites without counting the library`() {
        var browsed = false
        composeRule.setContent {
            MaterialTheme { HomeWelcome(welcomeSlot(covers = 4), HomeActions.None.copy(onOpenLibrary = { browsed = true })) }
        }
        composeRule.onNodeWithContentDescription("Getting started").assertExists()
        composeRule.onNodeWithText("Pick something new").assertIsDisplayed()
        composeRule.onNodeWithText("Start one and it lives here, ready to pick up where you left off.").assertIsDisplayed()
        composeRule.onAllNodesWithText("audiobooks are waiting", substring = true).assertCountEquals(0)
        composeRule.onNodeWithText("Discover").assertHasClickAction()
        composeRule.onNodeWithText("Browse the Library").performClick()
        browsed shouldBe true
    }
}
