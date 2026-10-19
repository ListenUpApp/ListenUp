package com.calypsan.listenup.client.features.discover.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.error.UnexpectedClientError
import com.calypsan.listenup.client.features.discover.HOUR_MS
import com.calypsan.listenup.client.features.discover.NOW_MS
import com.calypsan.listenup.client.features.discover.WithAvatarDependencies
import com.calypsan.listenup.client.features.discover.listener
import com.calypsan.listenup.client.features.discover.readyListening
import com.calypsan.listenup.client.presentation.discover.CommunitySize
import com.calypsan.listenup.client.presentation.discover.CurrentlyListeningUiState
import com.calypsan.listenup.client.presentation.discover.DiscoverViewer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Spec §3.8 for Listening now: the section keeps its heading in every state, waits with a skeleton,
 * owns up to a failure with its own Try again, never claims "just you" before the roster has synced,
 * invites (admin only) when you are alone, and dates a roster it cannot refresh.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w412dp-h3000dp")
class ListeningNowStatesTest {
    @get:Rule
    val composeRule = createComposeRule()

    @After
    fun stopGlobalKoin() {
        stopKoin()
    }

    private fun show(
        state: CurrentlyListeningUiState,
        community: CommunitySize = CommunitySize.Others,
        viewer: DiscoverViewer? = DiscoverViewer("me", "Simon", canInvite = true),
        onInvite: () -> Unit = {},
        onRetry: () -> Unit = {},
    ) = composeRule.setContent {
        WithAvatarDependencies {
            ListeningNowSection(
                state = state,
                community = community,
                viewer = viewer,
                onInvite = onInvite,
                onRetry = onRetry,
                onBookClick = {},
                onUserClick = {},
                isInSelectionMode = false,
                selectedBookIds = emptySet(),
                onBookLongPress = null,
                nowMs = NOW_MS,
            )
        }
    }

    @Test
    fun `loading keeps the heading and shows a skeleton`() {
        show(CurrentlyListeningUiState.Loading)
        composeRule.onNode(hasText("Listening now") and isHeading()).assertIsDisplayed()
        composeRule.onNodeWithTag("listening-skeleton").assertIsDisplayed()
    }

    @Test
    fun `an error offers Try again that restarts this section`() {
        var retried = 0
        show(CurrentlyListeningUiState.Error(UnexpectedClientError()), onRetry = { retried++ })
        composeRule.onNode(hasText("Listening now") and isHeading()).assertIsDisplayed()
        composeRule.onNodeWithText("Couldn't load who's listening").assertIsDisplayed()
        composeRule.onNodeWithText("Try again").performClick()
        assertEquals(1, retried)
    }

    @Test
    fun `offline with nothing cached says it needs a connection, with Try again`() {
        var retried = 0
        show(CurrentlyListeningUiState.Error(TransportError.NetworkUnavailable()), onRetry = { retried++ })
        composeRule.onNodeWithText("Who's listening needs a connection").assertIsDisplayed()
        composeRule.onNodeWithTag("listening-skeleton").assertDoesNotExist()
        composeRule.onNodeWithText("Try again").performClick()
        assertEquals(1, retried)
    }

    @Test
    fun `empty with others on the server says nobody is listening`() {
        show(readyListening(emptyList()))
        composeRule
            .onNodeWithText("Nobody's listening right now. When someone starts, they'll show up here.")
            .assertIsDisplayed()
    }

    @Test
    fun `empty before the roster has synced is a skeleton, never a claim`() {
        show(readyListening(emptyList()), community = CommunitySize.Unknown)
        composeRule.onNodeWithTag("listening-skeleton").assertIsDisplayed()
        composeRule.onNodeWithText("It's just you here so far").assertDoesNotExist()
        composeRule.onNodeWithText("Nobody's", substring = true).assertDoesNotExist()
    }

    @Test
    fun `only you, as an admin, is an invitation with Invite someone`() {
        var invited = 0
        show(readyListening(emptyList()), community = CommunitySize.JustYou, onInvite = { invited++ })
        composeRule.onNode(hasText("It's just you here so far") and isHeading()).assertIsDisplayed()
        composeRule
            .onNodeWithText(
                "When other people join, you'll see what they're listening to, the shelves they share and what they finish.",
            ).assertIsDisplayed()
        composeRule.onNode(hasText("Invite someone") and hasClickAction()).performClick()
        assertEquals(1, invited)
    }

    @Test
    fun `only you, as a member, is the invitation without a button`() {
        show(
            readyListening(emptyList()),
            community = CommunitySize.JustYou,
            viewer = DiscoverViewer("me", "Simon", canInvite = false),
        )
        composeRule.onNodeWithText("It's just you here so far").assertIsDisplayed()
        composeRule.onNodeWithText("Invite someone").assertDoesNotExist()
    }

    @Test
    fun `a stale roster says how old it is`() {
        show(
            CurrentlyListeningUiState.Ready(
                listOf(listener("live", "Piranesi", isLive = true, lastActiveAt = NOW_MS)),
                observedAtMs = NOW_MS - 2 * HOUR_MS,
                isStale = true,
            ),
        )
        composeRule.onNodeWithText("As of", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a fresh roster carries no As of line`() {
        show(readyListening(listOf(listener("live", "Piranesi", isLive = true))))
        composeRule.onNodeWithText("As of", substring = true).assertDoesNotExist()
    }
}
