package com.calypsan.listenup.client.presentation.player

import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.BookError
import com.calypsan.listenup.api.error.PlaybackError
import com.calypsan.listenup.client.domain.model.NextInSeries
import com.calypsan.listenup.client.playback.PlayerPhase
import com.calypsan.listenup.client.playback.SleepTimerState
import com.calypsan.listenup.client.presentation.player.PlayerCardAction.CloseBook
import com.calypsan.listenup.client.presentation.player.PlayerCardAction.Dismiss
import com.calypsan.listenup.client.presentation.player.PlayerCardAction.ListenAgain
import com.calypsan.listenup.client.presentation.player.PlayerCardAction.OpenInbox
import com.calypsan.listenup.client.presentation.player.PlayerCardAction.PlayNextBook
import com.calypsan.listenup.client.presentation.player.PlayerCardAction.Resume
import com.calypsan.listenup.client.presentation.player.PlayerCardAction.Retry
import com.calypsan.listenup.client.presentation.player.PlayerCardAction.SignIn
import com.calypsan.listenup.client.presentation.player.PlayerCardAction.TapToPlay
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class PlayerCardsTest :
    FunSpec({
        fun problem(state: PlayerUiState.Loaded): PlayerCard.Problem = PlayerCards.of(state).shouldBeInstanceOf<PlayerCard.Problem>()

        test("the sleep fade puts the gold card in the transport's place, with Keep listening alone") {
            PlayerCards.of(loaded(PlayerPhase.SleepFading)) shouldBe PlayerCard.SleepFading
        }

        test("playing, paused, restored, preparing, buffering and reconnecting have no card") {
            listOf(
                PlayerPhase.Playing,
                PlayerPhase.Paused,
                PlayerPhase.Restored,
                PlayerPhase.Preparing,
                PlayerPhase.Buffering,
                PlayerPhase.Reconnecting,
            ).forEach { PlayerCards.of(loaded(it)).shouldBeNull() }
        }

        test("a dropped connection, a stall and a failed start: Retry · Dismiss, error tone, place saved") {
            listOf(PlaybackError.ConnectionLost(), PlaybackError.Stalled(), PlaybackError.CouldNotStart()).forEach { e ->
                val card = problem(loaded(PlayerPhase.Failed(e), PrimaryControl.Retry))
                card.primaryAction shouldBe Retry
                card.secondaryAction shouldBe Dismiss
                card.tone shouldBe PlayerCardTone.Error
                card.placeSaved shouldBe true
                card.error shouldBe e
            }
        }

        test("unreachable, offline and an undecodable format: Retry · Close book") {
            problem(loaded(PlayerPhase.Unavailable(PlaybackError.ServerUnreachable()), PrimaryControl.Retry)).let { card ->
                card.primaryAction shouldBe Retry
                card.secondaryAction shouldBe CloseBook
                card.tone shouldBe PlayerCardTone.Neutral
            }
            problem(loaded(PlayerPhase.Unavailable(PlaybackError.OfflineNotDownloaded()), PrimaryControl.Retry))
                .secondaryAction shouldBe CloseBook
            problem(loaded(PlayerPhase.Failed(PlaybackError.UnsupportedFormat()), PrimaryControl.Retry)).let { card ->
                card.secondaryAction shouldBe CloseBook
                card.tone shouldBe PlayerCardTone.Error
            }
        }

        test("a lapsed session: Sign in · Dismiss when a stream returned it, Sign in · Close book when known up front") {
            problem(loaded(PlayerPhase.Failed(AuthError.SessionExpired()), PrimaryControl.SignIn)).let { card ->
                card.primaryAction shouldBe SignIn
                card.secondaryAction shouldBe Dismiss
                card.tone shouldBe PlayerCardTone.Neutral
            }
            problem(loaded(PlayerPhase.Unavailable(AuthError.SessionExpired()), PrimaryControl.SignIn))
                .secondaryAction shouldBe CloseBook
        }

        test("revoked: Close book alone, and the place is not claimed saved") {
            problem(loaded(PlayerPhase.Unavailable(PlaybackError.NoLongerAvailable()), PrimaryControl.Close)).let { card ->
                card.primaryAction shouldBe CloseBook
                card.secondaryAction.shouldBeNull()
                card.placeSaved shouldBe false
            }
        }

        test("held: Open Inbox · Close book for an admin, Close book alone for a member; amber") {
            val held = PlayerPhase.Unavailable(BookError.HeldForReview())
            problem(loaded(held, PrimaryControl.OpenInbox)).let { card ->
                card.primaryAction shouldBe OpenInbox
                card.secondaryAction shouldBe CloseBook
                card.tone shouldBe PlayerCardTone.Held
            }
            problem(loaded(held, PrimaryControl.Close)).let { card ->
                card.primaryAction shouldBe CloseBook
                card.secondaryAction.shouldBeNull()
            }
        }

        test("the two refusals offer their one way on, untinted") {
            problem(loaded(PlayerPhase.Failed(PlaybackError.BlockedInBackground()), PrimaryControl.Play)).let { card ->
                card.primaryAction shouldBe Resume
                card.secondaryAction.shouldBeNull()
                card.tone shouldBe PlayerCardTone.Neutral
            }
            problem(loaded(PlayerPhase.Failed(PlaybackError.AutoplayBlocked()), PrimaryControl.TapToPlay))
                .primaryAction shouldBe TapToPlay
        }

        test("finished with a next book leads with Play next book, Listen again beside it") {
            val card =
                PlayerCards
                    .of(loaded(PlayerPhase.Finished, PrimaryControl.ListenAgain, finished(next = nextBook)))
                    .shouldBeInstanceOf<PlayerCard.Finished>()
            card.primaryAction shouldBe PlayNextBook
            card.secondaryAction shouldBe ListenAgain
            card.info.nextBook shouldBe nextBook
        }

        test("finished with no next book leads with Listen again alone") {
            val card =
                PlayerCards
                    .of(loaded(PlayerPhase.Finished, PrimaryControl.ListenAgain, finished(next = null)))
                    .shouldBeInstanceOf<PlayerCard.Finished>()
            card.primaryAction shouldBe ListenAgain
            card.secondaryAction.shouldBeNull()
        }

        test("Now Playing closes for nothing loaded and for a revoked book, and only then") {
            PlayerCards.closesNowPlaying(PlayerUiState.NothingLoaded) shouldBe true
            PlayerCards.closesNowPlaying(loaded(PlayerPhase.Unavailable(PlaybackError.NoLongerAvailable()))) shouldBe true
            PlayerCards.closesNowPlaying(loaded(PlayerPhase.Unavailable(BookError.HeldForReview()))) shouldBe false
            PlayerCards.closesNowPlaying(loaded(PlayerPhase.Playing)) shouldBe false
        }
    })

private val nextBook =
    NextInSeries(
        bookId = "b2",
        title = "Words of Radiance",
        coverPath = null,
        coverHash = null,
        narratorNames = null,
        seriesName = "The Stormlight Archive",
        sequenceLabel = "2",
        durationMs = 172_800_000,
    )

private fun finished(next: NextInSeries?) =
    FinishedInfo(
        finishedAtMs = 1_760_000_000_000,
        startedAtMs = 1_757_000_000_000,
        durationMs = 163_800_000,
        yourHalfStars = null,
        nextBook = next,
    )

private fun loaded(
    phase: PlayerPhase,
    primary: PrimaryControl = PrimaryControl.Pause,
    finished: FinishedInfo? = null,
): PlayerUiState.Loaded =
    PlayerUiState.Loaded(
        book = PlayerBook("b1", "The Way of Kings", emptyList(), emptyList(), null, null, emptyList(), 163_800_000, null),
        phase = phase,
        chapter = PlayerChapter(5, 6, 75, "Bridge Four", isGenericTitle = false, isWholeBook = false, startMs = 0, durationMs = 1_235_000),
        chapters = emptyList(),
        source = AudioSource.Streaming,
        settings = PlayerSettings(1f, 1f, 0f, 0f, 10, 30),
        sleepTimer = SleepTimerState.Inactive,
        primary = primary,
        showsSkipBack = false,
        canSkipToNextChapter = true,
        finished = finished,
    )
