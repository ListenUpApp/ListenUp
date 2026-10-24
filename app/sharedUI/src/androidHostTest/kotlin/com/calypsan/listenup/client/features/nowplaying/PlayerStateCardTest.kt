package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.api.error.BookError
import com.calypsan.listenup.api.error.PlaybackError
import com.calypsan.listenup.client.domain.model.NextInSeries
import com.calypsan.listenup.client.features.nowplaying.components.PlayerStateCard
import com.calypsan.listenup.client.playback.PlayerPhase
import com.calypsan.listenup.client.presentation.player.FinishedInfo
import com.calypsan.listenup.client.presentation.player.PlayerCard
import com.calypsan.listenup.client.presentation.player.PlayerCardAction
import com.calypsan.listenup.client.presentation.player.PlayerCards
import com.calypsan.listenup.client.presentation.player.PrimaryControl
import io.kotest.matchers.shouldBe
import kotlinx.datetime.TimeZone
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** P32 and §3.7: the card per state, its words, and that each button sends its action. */
@Config(qualifiers = "w412dp-h915dp")
@RunWith(RobolectricTestRunner::class)
class PlayerStateCardTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val actions = mutableListOf<PlayerCardAction>()
    private val rated = mutableListOf<Int>()

    private fun card(card: PlayerCard) {
        composeRule.setContent {
            MaterialTheme {
                PlayerStateCard(
                    card = card,
                    onAction = { actions += it },
                    onRate = { rated += it },
                    nowMs = NOW,
                    zone = TimeZone.UTC,
                )
            }
        }
    }

    private fun cardFor(
        phase: PlayerPhase,
        primary: PrimaryControl,
        finished: FinishedInfo? = null,
    ): PlayerCard = PlayerCards.of(loadedPlayerState(phase = phase, primary = primary, finished = finished))!!

    @Test
    fun `a dropped connection - title, cause, place saved, Retry and Dismiss, announced assertively`() {
        card(cardFor(PlayerPhase.Failed(PlaybackError.ConnectionLost()), PrimaryControl.Retry))
        composeRule
            .onNodeWithText("Couldn't play this book.")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Assertive))
        composeRule.onNodeWithText("The connection to your server dropped.").assertExists()
        composeRule.onNodeWithText("Your place is saved.").assertExists()
        composeRule.onNodeWithText("Retry").performClick()
        composeRule.onNodeWithText("Dismiss").performClick()
        actions shouldBe listOf(PlayerCardAction.Retry, PlayerCardAction.Dismiss)
    }

    @Test
    fun `a reference shows when the error carries one`() {
        card(cardFor(PlayerPhase.Failed(PlaybackError.CouldNotStart(correlationId = "req-7f3a")), PrimaryControl.Retry))
        composeRule.onNodeWithText("Reference req-7f3a").assertExists()
    }

    @Test
    fun `revoked - Close book alone, and no place-saved line`() {
        card(cardFor(PlayerPhase.Unavailable(PlaybackError.NoLongerAvailable()), PrimaryControl.Close))
        composeRule.onNodeWithText("No longer in your library.").assertExists()
        composeRule.onNodeWithText("Your place is saved.").assertDoesNotExist()
        composeRule.onNodeWithText("Close book").performClick()
        actions shouldBe listOf(PlayerCardAction.CloseBook)
    }

    @Test
    fun `held, for an admin - Open Inbox and Close book`() {
        card(cardFor(PlayerPhase.Unavailable(BookError.HeldForReview()), PrimaryControl.OpenInbox))
        composeRule.onNodeWithText("Waiting in the Inbox.").assertExists()
        composeRule.onNodeWithText("Open Inbox").assertExists()
        composeRule.onNodeWithText("Close book").assertExists()
    }

    @Test
    fun `finished with a next book - the span, How was it, the next book, Play next book then Listen again`() {
        card(cardFor(PlayerPhase.Finished, PrimaryControl.ListenAgain, finished(next = NEXT)))
        composeRule.onNodeWithText("Finished today · 45h 30m · 6 Sep – 10 Oct").assertExists()
        composeRule.onNodeWithText("How was it?").assertExists()
        composeRule.onNodeWithText("Words of Radiance").assertExists()
        composeRule.onNodeWithText("Play next book").performClick()
        composeRule.onNodeWithText("Listen again").performClick()
        actions shouldBe listOf(PlayerCardAction.PlayNextBook, PlayerCardAction.ListenAgain)
    }

    @Test
    fun `finished with no next book leads with Listen again`() {
        card(cardFor(PlayerPhase.Finished, PrimaryControl.ListenAgain, finished(next = null)))
        composeRule.onNodeWithText("Play next book").assertDoesNotExist()
        composeRule.onNodeWithText("Listen again").assertExists()
    }

    private companion object {
        // 10 Oct 2026 18:00 UTC; started 6 Sep 2026.
        const val NOW = 1_791_655_200_000L
        val NEXT = NextInSeries("b2", "Words of Radiance", NO_COVER, null, null, "The Stormlight Archive", "2", 172_800_000)

        fun finished(next: NextInSeries?) =
            FinishedInfo(
                finishedAtMs = NOW - 3_600_000,
                startedAtMs = 1_788_652_800_000,
                durationMs = 163_800_000,
                yourHalfStars = null,
                nextBook = next,
            )
    }

    @Test
    fun `the sleep fade - Fading out, what is happening, and Keep listening, announced politely`() {
        card(PlayerCard.SleepFading)
        composeRule
            .onNodeWithText("Fading out…")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        composeRule.onNodeWithText("Your sleep timer is ending. Playback pauses in a moment.").assertExists()
        composeRule.onNodeWithText("Keep listening").performClick()
        actions shouldBe listOf(PlayerCardAction.KeepListening)
    }
}
