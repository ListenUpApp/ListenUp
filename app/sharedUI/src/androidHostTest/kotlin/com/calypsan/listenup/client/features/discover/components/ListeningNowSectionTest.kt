package com.calypsan.listenup.client.features.discover.components

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.client.features.discover.DAY_MS
import com.calypsan.listenup.client.features.discover.NOW_MS
import com.calypsan.listenup.client.features.discover.WithAvatarDependencies
import com.calypsan.listenup.client.features.discover.listener
import com.calypsan.listenup.client.presentation.discover.CurrentlyListeningUiSession
import com.calypsan.listenup.client.presentation.discover.CommunitySize
import com.calypsan.listenup.client.presentation.discover.CurrentlyListeningUiState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * "Listening now" renders two kinds of row, and the difference has to be visible.
 *
 * The section used to show live presence only and hide itself whenever nobody was listening — which,
 * on a server with three people, is nearly always. A section that silently disappears is
 * indistinguishable from one that is broken, so it now fills with each other person's most recently
 * played book. That only helps if a reader can tell "right now" from "two days ago" at a glance:
 * hence the per-card marker and the Live chip asserted here. Each card names its person and is two
 * links: the book (art and title) and, beside it, the person.
 *
 * A genuinely empty roster no longer hides the section (that read as broken): it says nobody is
 * listening, and never invents a row. The other empty treatments live in [ListeningNowStatesTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ListeningNowSectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    /**
     * The composable KoinApplication registers its container as the global Koin context and never
     * stops it. Left running, the next spec in this sdk-34 Robolectric sandbox to start its own
     * (RecentlyListenedTest) fails with KoinApplicationAlreadyStartedException.
     */
    @After
    fun stopGlobalKoin() {
        stopKoin()
    }

    private fun show(
        rows: List<CurrentlyListeningUiSession>,
        onBookClick: (String) -> Unit = {},
        onUserClick: (String) -> Unit = {},
    ) {
        composeRule.setContent {
            WithAvatarDependencies {
                ListeningNowSection(
                    state = CurrentlyListeningUiState.Ready(sessions = rows, observedAtMs = null, isStale = false),
                    community = CommunitySize.Others,
                    viewer = null,
                    onInvite = null,
                    onRetry = {},
                    onBookClick = onBookClick,
                    onUserClick = onUserClick,
                    isInSelectionMode = false,
                    selectedBookIds = emptySet(),
                    onBookLongPress = null,
                    nowMs = NOW_MS,
                )
            }
        }
    }

    @Test
    fun `an empty roster with others on the server says nobody is listening`() {
        show(emptyList())
        composeRule.onNodeWithText("Listening now").assertIsDisplayed()
        composeRule.onNodeWithText("Nobody's listening right now", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a live listener is marked listening now`() {
        show(listOf(listener("live", "Wind and Truth", isLive = true, lastActiveAt = NOW_MS - 60_000L)))
        composeRule.onNode(hasText("Listening now") and isHeading()).assertIsDisplayed()
        composeRule.onAllNodesWithText("Wind and Truth", useUnmergedTree = true)[0].assertIsDisplayed()
        // The heading, and the marker under the person.
        composeRule.onAllNodesWithText("Listening now", useUnmergedTree = true).assertCountEquals(2)
    }

    @Test
    fun `a non-live row is marked with how long ago they listened`() {
        show(listOf(listener("recent", "The Way of Kings", isLive = false, lastActiveAt = NOW_MS - 3 * DAY_MS)))
        composeRule.onAllNodesWithText("The Way of Kings", useUnmergedTree = true)[0].assertIsDisplayed()
        // Reuses the shared relativeLastActive helper — no second relative-time vocabulary.
        composeRule.onNodeWithText("3 days ago", useUnmergedTree = true).assertIsDisplayed()
        // Only the heading says "Listening now".
        composeRule.onAllNodesWithText("Listening now", useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun theCountLineSaysHowManyAreLiveAndHowManyMore() {
        show(
            listOf(
                listener("a", "Mistborn", isLive = true),
                listener("b", "Dune", isLive = true),
                listener("c", "Piranesi", isLive = false, lastActiveAt = NOW_MS - 2 * DAY_MS),
            ),
        )
        composeRule.onNodeWithText("2 listening now · 1 more in the last two weeks").assertIsDisplayed()
    }

    @Test
    fun aLiveRowWearsTheLiveChipOnItsArtAndARecentOneDoesNot() {
        show(
            listOf(
                listener("a", "Mistborn", isLive = true),
                listener("c", "Piranesi", isLive = false, lastActiveAt = NOW_MS - 2 * DAY_MS),
            ),
        )
        composeRule.onAllNodesWithText("Live", useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun theTitleIsInsideTheBookLinkAndTheNameIsASeparateProfileLink() {
        var openedBook: String? = null
        var openedUser: String? = null
        show(
            listOf(listener("a", "Mistborn", isLive = true)),
            onBookClick = { openedBook = it },
            onUserClick = { openedUser = it },
        )

        // The book link: one node whose single label leads with the title.
        composeRule.onNode(hasContentDescription("Mistborn", substring = true) and hasClickAction()).performClick()
        assertEquals("book-a", openedBook)
        assertNull(openedUser)

        // The person row: its own node, never inside the book link.
        composeRule.onNode(hasText("Name a", substring = true) and hasClickAction()).performClick()
        assertEquals("a", openedUser)
    }

    @Test
    fun aLiveBookStopIsOneButtonNamingTheBookAndWhoIsListening() {
        show(listOf(listener("a", "Mistborn", isLive = true)))
        val book =
            composeRule
                .onNode(hasContentDescription("Mistborn, Name a is listening now") and hasClickAction())
                .fetchSemanticsNode()
        // One label per card (spec 8.2): the art's title, the caption and the Live chip are not read on top of it.
        assertFalse(book.config.contains(SemanticsProperties.Text))
        assertEquals(Role.Button, book.config[SemanticsProperties.Role])
    }

    @Test
    fun aRecentBookStopSaysWhenTheyListened() {
        show(listOf(listener("c", "Piranesi", isLive = false, lastActiveAt = NOW_MS - 3 * DAY_MS)))
        composeRule
            .onNode(hasContentDescription("Piranesi, Name c listened 3 days ago") and hasClickAction())
            .assertExists()
    }
}
