package com.calypsan.listenup.web.features.nowplaying

import androidx.compose.runtime.Composable
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.BookError
import com.calypsan.listenup.api.error.PlaybackError
import com.calypsan.listenup.client.playback.PlaybackState
import com.calypsan.listenup.client.playback.PlayerPhase
import com.calypsan.listenup.client.presentation.player.PlayerProgress
import com.calypsan.listenup.client.presentation.player.PlayerUiState
import com.calypsan.listenup.client.presentation.player.PrimaryControl
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.awaitFrame
import com.calypsan.listenup.web.playback.HtmlAudioPlayer
import com.calypsan.listenup.web.playback.awaitState
import com.calypsan.listenup.web.playback.silentSegment
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLDialogElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import org.w3c.dom.url.URL

/** A book long enough that the elapsed label crosses into hours. */
private const val LONG_BOOK_MS = 9_000_000L

private const val ONE_HOUR_MS = 3_600_000L

private const val UNDER_AN_HOUR_MS = 125_000L

private const val SHORT_BOOK_MS = 600_000L

private const val SEEK_TARGET_MS = 420_000L

/** Long enough for the element to run a measurable clock, short enough to keep the lane quick. */
private const val AUDIO_SEGMENT_MS = 1_500L

/** How many `input` events one drag is stood in for. Any number above one makes the point. */
private const val DRAG_SAMPLES = 5

/** A skip pair deliberately unlike the stock 10/30, so a hard-coded default cannot pass. */
private const val CUSTOM_SKIP_BACK_SEC = 15

private const val CUSTOM_SKIP_FORWARD_SEC = 45

private const val FASTER_SPEED = 1.5f

private const val QUARTER_SPEED_STEP = 1.25f

private const val HALF_SPEED = 0.5f

private const val TRIPLE_SPEED = 3.0f

/**
 * The transport bar's own contract, over the shared model's state.
 *
 * The bar is presentational: what it shows is [PlayerUiState.Loaded] and [PlayerProgress], and what a press
 * means is the shared model's ([PlayerUiState.Loaded.primary], `onPrimary`). What the presses DO, all the way
 * to audible playback, is `LivePlaybackWiringTest`'s; the rules behind them are sharedLogic's
 * `PlayerSession*Test`.
 */
class TransportBarTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        /** The bar over [state] at [progress], with every callback a spec does not name inert. */
        fun bar(
            state: PlayerUiState.Loaded = loadedPlayerState(durationMs = SHORT_BOOK_MS),
            progress: PlayerProgress = playerProgress(durationMs = SHORT_BOOK_MS),
            onPrimary: () -> Unit = {},
            onSeek: (Long) -> Unit = {},
            onSkipBack: () -> Unit = {},
            onSkipForward: () -> Unit = {},
            onSetSpeed: (Float) -> Unit = {},
        ): HTMLElement =
            mounts.mount {
                TransportBar(
                    state = state,
                    progress = progress,
                    onPrimary = onPrimary,
                    onSeek = onSeek,
                    onSkipBack = onSkipBack,
                    onSkipForward = onSkipForward,
                    onSetSpeed = onSetSpeed,
                )
            }

        fun notice(content: @Composable () -> Unit): HTMLElement = mounts.mount { content() }

        test("a paused book offers Play, and a playing one offers Pause") {
            val paused = bar(loadedPlayerState(phase = PlayerPhase.Paused))
            val playing = bar(loadedPlayerState(phase = PlayerPhase.Playing))

            paused.querySelector(".tport-b")!!.getAttribute("aria-label") shouldBe "Play"
            playing.querySelector(".tport-b")!!.getAttribute("aria-label") shouldBe "Pause"
        }

        test("the play control reports exactly one click") {
            var clicks = 0
            val host = bar(loadedPlayerState(phase = PlayerPhase.Paused), onPrimary = { clicks++ })

            (host.querySelector(".tport-b") as HTMLElement).click()

            clicks shouldBe 1
        }

        test("the bar names the new book while it prepares (gap 7), with a spinner and no back skip") {
            // The bar used to vanish for the whole prepare: a tap on Play had no visible consequence on it at all.
            val host =
                bar(loadedPlayerState(title = "Words of Radiance", phase = PlayerPhase.Preparing, primary = PrimaryControl.Busy))

            host.textContent.orEmpty() shouldContain "Words of Radiance"
            host.querySelector(".tport-wait").shouldNotBeNull()
            host.querySelector(".tport-b")!!.getAttribute("aria-label") shouldBe "Loading"
            // §3.5: there is no settled position to go back from yet.
            host.querySelector("[aria-label^='Back ']").shouldBeNull()
        }

        test("a refused autoplay offers Play with a ring, and the press goes to the model") {
            var clicks = 0
            val host =
                bar(
                    loadedPlayerState(phase = PlayerPhase.Failed(PlaybackError.AutoplayBlocked()), primary = PrimaryControl.TapToPlay),
                    onPrimary = { clicks++ },
                )

            val play = host.querySelector(".tport-b") as HTMLElement
            play.getAttribute("aria-label") shouldBe "Play"
            play.classList.contains("ring") shouldBe true
            play.click()
            clicks shouldBe 1
        }

        test("the skip controls announce the listener's own interval, not a stock one") {
            // The number is the whole point of the control. A bar that says "30" to someone who set
            // 15 is worse than one that says nothing, because it looks authoritative.
            val host =
                bar(
                    loadedPlayerState(skipBackSec = CUSTOM_SKIP_BACK_SEC, skipForwardSec = CUSTOM_SKIP_FORWARD_SEC),
                )

            val skips = host.querySelectorAll(".tport-skip")
            skips.length shouldBe 2
            (skips.item(0) as HTMLElement).getAttribute("aria-label") shouldBe "Back $CUSTOM_SKIP_BACK_SEC seconds"
            (skips.item(1) as HTMLElement).getAttribute("aria-label") shouldBe "Forward $CUSTOM_SKIP_FORWARD_SEC seconds"
            host.querySelectorAll(".tport-skip-n").let { numerals ->
                (numerals.item(0) as HTMLElement).textContent shouldBe CUSTOM_SKIP_BACK_SEC.toString()
                (numerals.item(1) as HTMLElement).textContent shouldBe CUSTOM_SKIP_FORWARD_SEC.toString()
            }
        }

        test("each skip control reports its own press, and neither is the other") {
            var back = 0
            var forward = 0
            val host = bar(onSkipBack = { back++ }, onSkipForward = { forward++ })

            val skips = host.querySelectorAll(".tport-skip")
            (skips.item(0) as HTMLElement).click()

            back shouldBe 1
            forward shouldBe 0

            (skips.item(1) as HTMLElement).click()

            back shouldBe 1
            forward shouldBe 1
        }

        test("the speed control shows the current rate and offers to change it") {
            val host = bar(loadedPlayerState(speed = FASTER_SPEED))

            host.querySelector(".tport-speed")!!.textContent shouldBe "$FASTER_SPEED\u00D7"
            // The visible text is terse by necessity; the label is what a screen reader gets.
            host.querySelector(".tport-speed")!!.getAttribute("aria-label") shouldContain FASTER_SPEED.toString()
        }

        test("the speed control opens the picker rather than changing the rate under you") {
            // It used to cycle. A press now offers the whole ladder plus the rates between rungs,
            // and changes nothing until one is chosen.
            var set = 0
            val host = bar(onSetSpeed = { set++ })

            (host.querySelector(".tport-speed") as HTMLElement).click()
            awaitFrame()

            (host.querySelector(".speed-dlg") as? HTMLDialogElement)?.isModal() shouldBe true
            set shouldBe 0

            host.closeDialogs()
        }

        test("a speed renders as short as it can without lying about itself") {
            // 40px of bar. "1.00x" spends a third of it saying nothing, and "1.2x" would be a
            // different speed from the one playing.
            formatSpeed(1.0f) shouldBe "1"
            formatSpeed(FASTER_SPEED) shouldBe "1.5"
            formatSpeed(QUARTER_SPEED_STEP) shouldBe "1.25"
            formatSpeed(HALF_SPEED) shouldBe "0.5"
            formatSpeed(TRIPLE_SPEED) shouldBe "3"
        }

        test("elapsed time reads H:MM:SS at an hour and M:SS below it") {
            // The boundary is the whole claim: one second either side of an hour must not render
            // the same shape, or a nine-hour book reads as nine minutes.
            formatElapsed(UNDER_AN_HOUR_MS) shouldBe "2:05"
            formatElapsed(ONE_HOUR_MS) shouldBe "1:00:00"
            formatElapsed(ONE_HOUR_MS - 1_000) shouldBe "59:59"
            formatElapsed(LONG_BOOK_MS) shouldBe "2:30:00"
        }

        test("with no book loaded the bar renders nothing at all") {
            // An empty transport bar is chrome that lies about there being something to play.
            val host = mounts.mount { TransportBarHost(fixedPlayback()(), {}, {}, {}) }

            host.querySelector(".tport") shouldBe null
            host.textContent.orEmpty() shouldBe ""
        }

        test("the scrubber seeks once on release, not once per pointer sample") {
            // `input` fires continuously during a drag. Seeking on each one would have
            // HtmlAudioPlayer re-run `attach` per sample — and on the HLS path that destroys and
            // rebuilds an hls.js instance, playlists and all, tens of times a second.
            var seeks = 0
            var seekedTo = -1L
            val host =
                bar(
                    onSeek = {
                        seeks++
                        seekedTo = it
                    },
                )

            val scrubber = host.querySelector(".tport-scrub") as HTMLInputElement
            scrubber.getAttribute("max") shouldBe SHORT_BOOK_MS.toString()
            repeat(DRAG_SAMPLES) { sample ->
                scrubber.value = (SEEK_TARGET_MS + sample).toString()
                scrubber.dispatchEvent(Event("input"))
            }

            seeks shouldBe 0

            scrubber.value = SEEK_TARGET_MS.toString()
            scrubber.dispatchEvent(Event("change"))

            seeks shouldBe 1
            seekedTo shouldBe SEEK_TARGET_MS
        }

        test("a book loaded after the gesture actually plays, rather than settling on Paused") {
            // The regression this whole lane exists for. `HtmlAudioPlayerLifecycleTest`
            // already pins the other half — a bare `load()` settles on Paused — so if priming
            // stopped carrying the listener's intent across the prepare, this is the only thing
            // in the suite that would notice, and it would notice as silence.
            val player = HtmlAudioPlayer()
            val segment = silentSegment(AUDIO_SEGMENT_MS)

            player.primeForPlayback()
            player.load(listOf(segment))

            player.awaitState(PlaybackState.Playing)

            player.releasePlayer()
            URL.revokeObjectURL(segment.url)
        }

        test("the bar renders elapsed on the left of the scrubber and total on the right") {
            // The two labels are deliberately different values, and asserted in order. Counting
            // them proves nothing: swapping position and duration at the call site renders
            // two labels too, and would tell every listener their book was nearly over.
            val host =
                bar(
                    loadedPlayerState(phase = PlayerPhase.Paused, durationMs = LONG_BOOK_MS),
                    playerProgress(positionMs = UNDER_AN_HOUR_MS, durationMs = LONG_BOOK_MS),
                )

            host.querySelector(".tport-t")!!.textContent shouldBe "Dune"
            val times = host.querySelectorAll(".tport-time")
            times.length shouldBe 2
            times.item(0)!!.textContent shouldBe formatElapsed(UNDER_AN_HOUR_MS)
            times.item(1)!!.textContent shouldBe formatElapsed(LONG_BOOK_MS)
            // …and not the same string, or the assertions above would survive a swap.
            formatElapsed(UNDER_AN_HOUR_MS) shouldNotBe formatElapsed(LONG_BOOK_MS)
        }

        test("a failed book's notice names the cause and offers Retry") {
            var retries = 0
            val host =
                notice {
                    PlaybackNotice(
                        error = PlaybackError.ConnectionLost(),
                        primary = PrimaryControl.Retry,
                        onRetry = { retries++ },
                        onDismiss = {},
                    )
                }

            val text = host.querySelector(".tport-note")!!.textContent.orEmpty()
            text shouldContain "Couldn't play this book."
            text shouldContain "The connection to your server dropped."
            host.buttonSaying("Retry").shouldNotBeNull().click()
            retries shouldBe 1
        }

        test("a held book's notice names what unlocks it, and offers no Retry") {
            // The refusal's own words, not the connection-failure notice, which would send the listener checking
            // a network that is fine. Retrying cannot release a book from the Inbox.
            var dismissals = 0
            val held: AppError = BookError.HeldForReview()
            val host =
                notice {
                    PlaybackNotice(error = held, primary = PrimaryControl.Close, onRetry = {}, onDismiss = { dismissals++ })
                }

            val text = host.querySelector(".tport-note")!!.textContent.orEmpty()
            text shouldContain held.playerTitle()
            text shouldNotContain "Couldn't play"
            host.buttonSaying("Retry").shouldBeNull()
            (host.querySelector(".tport-note-x") as HTMLButtonElement).click()
            dismissals shouldBe 1
        }

        test("a notice with nothing to report renders nothing") {
            val host = notice { PlaybackNotice(error = null, primary = null, onRetry = {}, onDismiss = {}) }

            host.querySelector(".tport-note") shouldBe null
        }

        test("the notice reads a failure, or a known reason a book cannot play, and nothing else") {
            noticeErrorOf(PlayerUiState.NothingLoaded).shouldBeNull()
            noticeErrorOf(loadedPlayerState(phase = PlayerPhase.Playing)).shouldBeNull()
            noticeErrorOf(loadedPlayerState(phase = PlayerPhase.Failed(PlaybackError.Stalled()))) shouldBe
                PlaybackError.Stalled()
            noticeErrorOf(loadedPlayerState(phase = PlayerPhase.Unavailable(BookError.HeldForReview()))) shouldBe
                BookError.HeldForReview()
        }

        test("the scrubber steps by a second, not a millisecond, and says the time out loud") {
            // step defaults to 1 on a range input — one millisecond here, so an arrow key would
            // need thirty thousand presses to move half a minute. aria-valuenow is equally raw.
            val host = bar(progress = playerProgress(positionMs = SEEK_TARGET_MS, durationMs = SHORT_BOOK_MS))

            val scrubber = host.querySelector(".tport-scrub") as HTMLInputElement
            scrubber.getAttribute("step") shouldBe "1000"
            // Where you are, against the whole: "3:30 of 10:00", not a bare "3:30".
            scrubber.getAttribute("aria-valuetext") shouldBe
                "${formatElapsed(SEEK_TARGET_MS)} of ${formatElapsed(SHORT_BOOK_MS)}"
        }
    })
