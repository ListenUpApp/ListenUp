package com.calypsan.listenup.web.features.nowplaying

import com.calypsan.listenup.api.error.BookError
import com.calypsan.listenup.api.error.PlaybackError
import com.calypsan.listenup.client.domain.model.NextInSeries
import com.calypsan.listenup.client.playback.PlayerPhase
import com.calypsan.listenup.client.presentation.player.FinishedInfo
import com.calypsan.listenup.client.presentation.player.PlayerCardAction
import com.calypsan.listenup.client.presentation.player.PlayerCards
import com.calypsan.listenup.client.presentation.player.PlayerUiState
import com.calypsan.listenup.client.presentation.player.PrimaryControl
import com.calypsan.listenup.web.MountRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList

/**
 * Now Playing's state card on web (P32, §4.6): the shared [PlayerCards] decides which card and which ways out; this
 * draws it with the browser's words. Ports `PlayerProblemNoteTest` (the note it replaces).
 */
class PlayerStateCardTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        fun card(
            state: PlayerUiState.Loaded,
            actions: MutableList<PlayerCardAction> = mutableListOf(),
            rated: MutableList<Int> = mutableListOf(),
        ): HTMLElement =
            mounts.mount {
                PlayerStateCard(
                    card = PlayerCards.of(state).shouldNotBeNull(),
                    onAction = { actions += it },
                    onRate = { rated += it },
                    nowMs = NOW_MS,
                )
            }

        fun buttons(host: HTMLElement): List<String> =
            host
                .querySelectorAll(".np-card-actions button")
                .asList()
                .map { (it as HTMLElement).textContent.orEmpty().trim() }

        test("a dropped connection: title, cause, place saved, Retry · Dismiss, role=alert") {
            val actions = mutableListOf<PlayerCardAction>()
            val host =
                card(
                    wayOfKings(phase = PlayerPhase.Failed(PlaybackError.ConnectionLost()), primary = PrimaryControl.Retry),
                    actions,
                )
            val alert = host.querySelector(".np-card.error[role=alert]") as HTMLElement
            alert.querySelector(".np-card-t")?.textContent shouldBe "Couldn't play this book."
            alert.textContent.orEmpty() shouldContain "The connection to your server dropped."
            alert.textContent.orEmpty() shouldContain "Your place is saved."
            buttons(host) shouldBe listOf("Retry", "Dismiss")
            (host.querySelectorAll(".np-card-actions button").item(1) as HTMLElement).click()
            actions shouldBe listOf(PlayerCardAction.Dismiss)
        }

        test("unreachable: the browser's cause, Retry · Close book") {
            val host =
                card(wayOfKings(phase = PlayerPhase.Unavailable(PlaybackError.ServerUnreachable()), primary = PrimaryControl.Retry))
            host.textContent.orEmpty() shouldContain "Books stream from your server in the browser."
            buttons(host) shouldBe listOf("Retry", "Close book")
        }

        test("held: Open Inbox · Close book for an admin; no Retry") {
            val host =
                card(
                    wayOfKings(phase = PlayerPhase.Unavailable(BookError.HeldForReview()), primary = PrimaryControl.OpenInbox),
                )
            buttons(host) shouldBe listOf("Open Inbox", "Close book")
            host.querySelector(".np-card.held").shouldNotBeNull()
        }

        test("revoked: Close book alone, and no claim that the place is saved") {
            val host =
                card(wayOfKings(phase = PlayerPhase.Unavailable(PlaybackError.NoLongerAvailable()), primary = PrimaryControl.Close))
            buttons(host) shouldBe listOf("Close book")
            host.textContent.orEmpty() shouldNotContain "Your place is saved."
        }

        test("a reference shows when the error has one") {
            val host =
                card(
                    wayOfKings(
                        phase = PlayerPhase.Failed(PlaybackError.CouldNotStart(correlationId = "req-7f3a")),
                        primary = PrimaryControl.Retry,
                    ),
                )
            host.textContent.orEmpty() shouldContain "Reference req-7f3a"
        }

        test("autoplay refused: one ringed Play") {
            val actions = mutableListOf<PlayerCardAction>()
            val host =
                card(
                    wayOfKings(phase = PlayerPhase.Failed(PlaybackError.AutoplayBlocked()), primary = PrimaryControl.TapToPlay),
                    actions,
                )
            val play = host.querySelector(".np-card .np-play.ring") as HTMLElement
            play.getAttribute("aria-label") shouldBe "Play"
            play.click()
            actions shouldBe listOf(PlayerCardAction.TapToPlay)
        }

        test("finished with a next book: the span, stars, the next book, Play next book · Listen again") {
            val actions = mutableListOf<PlayerCardAction>()
            val host =
                card(
                    wayOfKings(phase = PlayerPhase.Finished, primary = PrimaryControl.ListenAgain, finished = finishedInfo(nextBook)),
                    actions,
                )
            host.textContent.orEmpty() shouldContain "Finished today"
            host.textContent.orEmpty() shouldContain "How was it?"
            host.textContent.orEmpty() shouldContain "Words of Radiance"
            host.textContent.orEmpty() shouldContain "Book 2"
            buttons(host) shouldBe listOf("Play next book", "Listen again")
            (host.querySelectorAll(".np-card-actions button").item(0) as HTMLElement).click()
            actions shouldBe listOf(PlayerCardAction.PlayNextBook)
        }

        test("finished with no next book: Listen again alone") {
            val host =
                card(wayOfKings(phase = PlayerPhase.Finished, primary = PrimaryControl.ListenAgain, finished = finishedInfo(null)))
            buttons(host) shouldBe listOf("Listen again")
        }

        test("the fade card: gold, the moon, its words, one Keep listening") {
            val actions = mutableListOf<PlayerCardAction>()
            val host = card(wayOfKings(phase = PlayerPhase.SleepFading), actions)

            val fade = host.querySelector(".np-card") as HTMLElement
            // Polite: nothing failed, so it is a status, not an alert.
            fade.getAttribute("role") shouldBe "status"
            val probe = document.createElement("div") as HTMLElement
            probe.style.setProperty("background-color", "var(--sleep-container)")
            host.appendChild(probe)
            window.getComputedStyle(fade).backgroundColor shouldBe window.getComputedStyle(probe).backgroundColor
            fade.querySelector(".np-card-t svg").shouldNotBeNull()
            fade.textContent.orEmpty() shouldContain "Fading out…"
            fade.textContent.orEmpty() shouldContain "Your sleep timer is ending. Playback pauses in a moment."
            buttons(host) shouldBe listOf("Keep listening")

            (host.querySelector(".np-card button") as HTMLElement).click()
            actions shouldBe listOf(PlayerCardAction.KeepListening)
        }

        test("playing has no card") {
            PlayerCards.of(wayOfKings(phase = PlayerPhase.Playing)) shouldBe null
        }
    })

private const val NOW_MS = 1_760_000_000_000L

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

private fun finishedInfo(next: NextInSeries?) =
    FinishedInfo(
        finishedAtMs = NOW_MS - 60_000,
        startedAtMs = NOW_MS - 3_000_000_000,
        durationMs = KINGS_MS,
        yourHalfStars = null,
        nextBook = next,
    )
