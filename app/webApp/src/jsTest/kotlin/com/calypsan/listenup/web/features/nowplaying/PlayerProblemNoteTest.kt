package com.calypsan.listenup.web.features.nowplaying

import androidx.compose.runtime.Composable
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.BookError
import com.calypsan.listenup.api.error.PlaybackError
import com.calypsan.listenup.client.playback.PlayerPhase
import com.calypsan.listenup.client.presentation.player.PlayerUiState
import com.calypsan.listenup.client.presentation.player.PrimaryControl
import com.calypsan.listenup.web.MountRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement

/**
 * A problem's cause and Dismiss, inside Now Playing. Ported verbatim from the notice strip that sat over the bar
 * until the mini player redesign: the bar now says what is wrong and offers the way out, and this carries the
 * sentence the bar has no room for, until PR 3's error card replaces it.
 */
class PlayerProblemNoteTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        fun note(content: @Composable () -> Unit): HTMLElement = mounts.mount { content() }

        test("a failed book's note names the cause and offers Retry") {
            var retries = 0
            val host =
                note {
                    PlayerProblemNote(
                        error = PlaybackError.ConnectionLost(),
                        primary = PrimaryControl.Retry,
                        onRetry = { retries++ },
                        onDismiss = {},
                    )
                }

            val text = host.querySelector(".np-note")!!.textContent.orEmpty()
            text shouldContain "Couldn't play this book."
            text shouldContain "The connection to your server dropped."
            host.buttonSaying("Retry").shouldNotBeNull().click()
            retries shouldBe 1
        }

        test("a held book's note names what unlocks it, and offers no Retry") {
            // The refusal's own words, not the connection-failure notice, which would send the listener checking
            // a network that is fine. Retrying cannot release a book from the Inbox.
            var dismissals = 0
            val held: AppError = BookError.HeldForReview()
            val host =
                note {
                    PlayerProblemNote(error = held, primary = PrimaryControl.Close, onRetry = {}, onDismiss = { dismissals++ })
                }

            val text = host.querySelector(".np-note")!!.textContent.orEmpty()
            text shouldContain held.playerTitle()
            text shouldNotContain "Couldn't play"
            host.buttonSaying("Retry").shouldBeNull()
            (host.querySelector(".np-note-x") as HTMLButtonElement).click()
            dismissals shouldBe 1
        }

        test("a note with nothing to report renders nothing") {
            val host = note { PlayerProblemNote(error = null, primary = null, onRetry = {}, onDismiss = {}) }

            host.querySelector(".np-note") shouldBe null
        }

        test("the note reads a failure, or a known reason a book cannot play, and nothing else") {
            problemErrorOf(PlayerUiState.NothingLoaded).shouldBeNull()
            problemErrorOf(loadedPlayerState(phase = PlayerPhase.Playing)).shouldBeNull()
            problemErrorOf(loadedPlayerState(phase = PlayerPhase.Failed(PlaybackError.Stalled()))) shouldBe
                PlaybackError.Stalled()
            problemErrorOf(loadedPlayerState(phase = PlayerPhase.Unavailable(BookError.HeldForReview()))) shouldBe
                BookError.HeldForReview()
        }
    })
