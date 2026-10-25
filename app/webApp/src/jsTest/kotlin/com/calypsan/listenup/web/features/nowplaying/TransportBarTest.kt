package com.calypsan.listenup.web.features.nowplaying

import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.PlaybackError
import com.calypsan.listenup.client.playback.PlaybackState
import com.calypsan.listenup.client.playback.PlayerPhase
import com.calypsan.listenup.client.playback.SleepTimerMode
import com.calypsan.listenup.client.playback.SleepTimerState
import com.calypsan.listenup.client.presentation.player.PlayerChapterRow
import com.calypsan.listenup.client.presentation.player.PlayerContributor
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
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLDialogElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.asList
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

/** Two titled chapters, the second current, 300 s each: the bar's chapter-scoped controls read these. */
private fun chaptered(
    phase: PlayerPhase = PlayerPhase.Playing,
    primary: PrimaryControl? = null,
    sleepTimer: SleepTimerState = SleepTimerState.Inactive,
): PlayerUiState.Loaded =
    loadedPlayerState(
        title = "The Way of Kings",
        phase = phase,
        primary =
            primary ?: if (phase == PlayerPhase.Playing || phase == PlayerPhase.SleepFading) PrimaryControl.Pause else PrimaryControl.Play,
        authors = listOf(PlayerContributor("c1", "Brandon Sanderson", 1)),
        durationMs = 600_000,
        chapters =
            listOf(
                PlayerChapterRow(0, 1, "Prelude", 0, 300_000, isCurrent = false),
                PlayerChapterRow(1, 2, "Bridge Four", 300_000, 300_000, isCurrent = false),
            ),
        currentChapterIndex = 1,
        skipBackSec = 10,
        skipForwardSec = 30,
        sleepTimer = sleepTimer,
        showsSkipBack = phase == PlayerPhase.Playing || phase == PlayerPhase.Paused || phase == PlayerPhase.SleepFading,
    )

/** 60 s into the second chapter: 4 minutes left in it. */
private val CHAPTER_PROGRESS =
    PlayerProgress(chapterPositionMs = 60_000, chapterDurationMs = 300_000, bookPositionMs = 360_000, bookDurationMs = 600_000)

/** The body's accessible name over [chaptered] at [CHAPTER_PROGRESS]. */
private const val KINGS_BODY_LABEL = "Open Now Playing: The Way of Kings, Bridge Four, 4 minutes left in the chapter"

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
            onCancelSleepTimer: () -> Unit = {},
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
                    onCancelSleepTimer = onCancelSleepTimer,
                )
            }

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
            host.querySelector(".tport-b")!!.getAttribute("aria-label") shouldBe "Loading. Pause"
            // §3.5: there is no settled position to go back from yet; the control keeps its slot, hidden.
            host.querySelector(".tport-back")!!.classList.contains("off") shouldBe true
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
            (skips.item(0) as HTMLElement).getAttribute("aria-label") shouldBe "Skip back $CUSTOM_SKIP_BACK_SEC seconds"
            (skips.item(1) as HTMLElement).getAttribute("aria-label") shouldBe "Skip forward $CUSTOM_SKIP_FORWARD_SEC seconds"
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
            host.querySelector(".tport-speed")!!.getAttribute("aria-label") shouldBe "Playback speed, ${formatSpeed(FASTER_SPEED)} times"
        }

        test("the speed control opens the picker rather than changing the rate under you") {
            // It used to cycle. A press now offers the whole ladder plus the rates between rungs,
            // and changes nothing until one is chosen.
            var set = 0
            val host = bar(onSetSpeed = { set++ })

            (host.querySelector(".tport-speed") as HTMLElement).click()
            awaitFrame()

            // A popover hung from the control (C9): the runner's page is wider than 760px.
            (host.querySelector(".speed-dlg") as HTMLElement).matches(":popover-open") shouldBe true
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
            val host = mounts.mount { TransportBarHost(fixedPlayback()(), onOpenBook = {}) }

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

        test("the bar renders elapsed on the left of the scrubber and the chapter's remaining on the right") {
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
            times.item(1)!!.textContent shouldBe "\u2212" + formatElapsed(LONG_BOOK_MS - UNDER_AN_HOUR_MS)
            // …and not the same string, or the assertions above would survive a swap.
            formatElapsed(UNDER_AN_HOUR_MS) shouldNotBe formatElapsed(LONG_BOOK_MS - UNDER_AN_HOUR_MS)
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

        test("the body is one link to /playing, named for the book and its line") {
            val opened = mutableListOf<HTMLElement?>()
            val host =
                mounts.mount {
                    TransportBar(
                        state = chaptered(),
                        progress = CHAPTER_PROGRESS,
                        onPrimary = {},
                        onSeek = {},
                        onSkipBack = {},
                        onSkipForward = {},
                        onSetSpeed = {},
                        onOpenNowPlaying = { opened += it },
                    )
                }
            val body = host.querySelector("a.tport-body[href='/playing']") as HTMLAnchorElement
            body.getAttribute("aria-label") shouldBe KINGS_BODY_LABEL
            host.querySelector(".tport-sub.desk")!!.textContent shouldBe "Bridge Four · Brandon Sanderson"
            body.click()
            opened shouldBe listOf<HTMLElement?>(body)
        }

        test("the bar's focus order is pinned: body, back, play, forward, scrubber, speed, sleep, chapters, shortcuts, open") {
            // ⛔ Pinned (spec §11). Shortcuts was the one sanctioned insertion, between Chapters and Open.
            val host = bar(chaptered(), CHAPTER_PROGRESS)
            val bar = host.querySelector(".tport") as HTMLElement
            val order =
                bar
                    .querySelectorAll("a, button, input")
                    .asList()
                    .map { it as HTMLElement }
                    .filter { it.tabIndex >= 0 }
                    .map { it.getAttribute("aria-label") }
            order shouldBe
                listOf(
                    KINGS_BODY_LABEL,
                    "Skip back 10 seconds",
                    "Pause",
                    "Skip forward 30 seconds",
                    "Chapter position",
                    "Playback speed, 1 times",
                    "Sleep timer",
                    "Chapters",
                    "Keyboard shortcuts",
                    "Open Now Playing",
                )
        }

        test("every control a key presses names its key, to a screen reader and in its tooltip") {
            val host = bar(chaptered(), CHAPTER_PROGRESS)

            fun control(selector: String) = host.querySelector(selector) as HTMLElement

            mapOf(
                ".tport-b" to ("Space" to "Pause (Space)"),
                ".tport-back" to ("ArrowLeft" to "Skip back 10 seconds (←)"),
                ".tport-fwd" to ("ArrowRight" to "Skip forward 30 seconds (→)"),
                ".tport-speed" to ("< >" to "Playback speed (< >)"),
                ".tport-sleep" to ("T" to "Sleep timer (T)"),
                ".tport-ch" to ("C" to "Chapters (C)"),
                ".tport-keys" to ("?" to "Keyboard shortcuts (?)"),
                ".tport-expand" to ("P" to "Open Now Playing (P)"),
            ).forEach { (selector, expected) ->
                control(selector).getAttribute("aria-keyshortcuts") shouldBe expected.first
                control(selector).getAttribute("title") shouldBe expected.second
            }
        }

        test("the Shortcuts button opens the keyboard shortcuts, one row per key, as pairs") {
            val host = bar(chaptered(), CHAPTER_PROGRESS)
            (host.querySelector(".tport-keys") as HTMLElement).click()
            awaitFrame()

            val sheet = host.ownerDocument!!.querySelector(".keys-dlg").shouldNotBeNull()
            sheet.querySelector("h2")!!.textContent shouldBe "Keyboard shortcuts"
            sheet.querySelectorAll("dl dt").length shouldBe PLAYER_SHORTCUTS.size
            sheet.querySelectorAll("dl dd").length shouldBe PLAYER_SHORTCUTS.size
            sheet.querySelector("dt kbd")!!.textContent shouldBe "Space"
            (host.querySelector(".tport-keys") as HTMLElement).getAttribute("aria-expanded") shouldBe "true"
        }

        test("a hidden back N keeps its slot, so the main button never moves, and leaves the tab order") {
            val host = bar(chaptered(phase = PlayerPhase.Buffering, primary = PrimaryControl.Busy), CHAPTER_PROGRESS)
            val back = host.querySelector(".tport-back") as HTMLButtonElement
            back.classList.contains("off") shouldBe true
            back.getAttribute("aria-hidden") shouldBe "true"
            back.tabIndex shouldBe -1
            back.disabled shouldBe true
            host.querySelector(".tport-b")!!.getAttribute("aria-label") shouldBe "Loading. Pause"
        }

        test("the scrubber is the chapter's: its range is the chapter, and a release seeks from the chapter's start") {
            var sought: Long? = null
            val host = bar(chaptered(), CHAPTER_PROGRESS, onSeek = { sought = it })
            val range = host.querySelector(".tport-head input") as HTMLInputElement
            range.getAttribute("aria-label") shouldBe "Chapter position"
            range.getAttribute("max") shouldBe "300000"
            range.value shouldBe "60000"
            range.value = "120000"
            range.dispatchEvent(Event("input"))
            range.dispatchEvent(Event("change"))
            sought shouldBe 420_000L
        }

        test("the right-hand time is the chapter's remaining, signed") {
            val host = bar(chaptered(), CHAPTER_PROGRESS)
            val times = host.querySelectorAll(".tport-head .tport-time").asList().map { it.textContent.orEmpty() }
            times shouldBe listOf("1:00", "\u22124:00")
        }

        test("volume boost is not on the bar") {
            bar(chaptered(), CHAPTER_PROGRESS).querySelector(".tport-boost").shouldBeNull()
        }

        test("an armed timer shows its countdown on the sleep control, in gold") {
            val timer = SleepTimerState.Active(SleepTimerMode.Duration(30), remainingMs = 1_380_000, totalMs = 1_800_000, startedAt = 0)
            val host = bar(chaptered(sleepTimer = timer), CHAPTER_PROGRESS)
            val sleep = host.querySelector(".tport-sleep") as HTMLElement
            sleep.classList.contains("armed") shouldBe true
            sleep.textContent.orEmpty() shouldContain "23:00"
            sleep.getAttribute("aria-label") shouldBe "Sleep timer, 23:00 left"
        }

        test("Open is a chevron that says whether Now Playing is open, and on the page it closes it") {
            val opened = mutableListOf<HTMLElement?>()
            var closes = 0

            fun chevronBar(isOpen: Boolean): HTMLElement =
                mounts.mount {
                    TransportBar(
                        state = chaptered(),
                        progress = CHAPTER_PROGRESS,
                        onPrimary = {},
                        onSeek = {},
                        onSkipBack = {},
                        onSkipForward = {},
                        onSetSpeed = {},
                        isNowPlayingOpen = isOpen,
                        onOpenNowPlaying = { opened += it },
                        onCloseNowPlaying = { closes++ },
                    )
                }
            val open = chevronBar(isOpen = false).querySelector(".tport-expand") as HTMLButtonElement
            open.getAttribute("aria-label") shouldBe "Open Now Playing"
            open.getAttribute("aria-expanded") shouldBe "false"
            open.click()
            opened shouldBe listOf<HTMLElement?>(open)

            val close = chevronBar(isOpen = true).querySelector(".tport-expand") as HTMLButtonElement
            close.getAttribute("aria-label") shouldBe "Close Now Playing"
            close.getAttribute("aria-expanded") shouldBe "true"
            close.click()
            closes shouldBe 1
        }

        test("on the page, Chapters moves focus to the page's list instead of opening the picker") {
            var focused = 0
            val host =
                mounts.mount {
                    TransportBar(
                        state = chaptered(),
                        progress = CHAPTER_PROGRESS,
                        onPrimary = {},
                        onSeek = {},
                        onSkipBack = {},
                        onSkipForward = {},
                        onSetSpeed = {},
                        isNowPlayingOpen = true,
                        onFocusChapters = { focused++ },
                    )
                }
            (host.querySelector(".tport-ch") as HTMLElement).click()
            awaitFrame()
            focused shouldBe 1
            host.querySelector("dialog.chap-dlg[open]") shouldBe null
        }

        test("during the sleep fade the main button is Keep listening, which cancels the timer; back N stays") {
            // Simon 2026-10-14: the fade's way out sits on the mini player itself, not one tap away in Now Playing.
            var cancels = 0
            var presses = 0
            val host =
                bar(
                    chaptered(phase = PlayerPhase.SleepFading),
                    CHAPTER_PROGRESS,
                    onPrimary = { presses++ },
                    onCancelSleepTimer = { cancels++ },
                )
            host.querySelector(".tport-sub.desk")!!.textContent shouldBe "Sleep timer · fading out"
            host.querySelector(".tport-b").shouldBeNull()
            val keep = host.querySelector(".tport-pill") as HTMLButtonElement
            keep.textContent shouldBe "Keep listening"
            keep.click()
            cancels shouldBe 1
            presses shouldBe 0
            host.querySelector(".tport-back")!!.classList.contains("off") shouldBe false
        }

        test("a failure tints the bar, says why, and offers Retry; no back skip") {
            var presses = 0
            val host =
                bar(
                    chaptered(phase = PlayerPhase.Failed(PlaybackError.ConnectionLost()), primary = PrimaryControl.Retry),
                    CHAPTER_PROGRESS,
                    onPrimary = { presses++ },
                )
            val bar = host.querySelector(".tport") as HTMLElement
            bar.classList.contains("problem") shouldBe true
            bar.textContent.orEmpty() shouldContain "Couldn't play this book."
            (host.querySelector(".tport-pill") as HTMLButtonElement).apply { textContent shouldBe "Retry" }.click()
            presses shouldBe 1
            host.querySelector(".tport-back")!!.classList.contains("off") shouldBe true
        }

        test("an unreachable server is not tinted and still offers Retry (S6)") {
            val host =
                bar(
                    chaptered(phase = PlayerPhase.Unavailable(PlaybackError.ServerUnreachable()), primary = PrimaryControl.Retry),
                    CHAPTER_PROGRESS,
                )
            host.querySelector(".tport")!!.classList.contains("problem") shouldBe false
            host.textContent.orEmpty() shouldContain "Can't reach your server"
            host.querySelector(".tport-pill")!!.textContent shouldBe "Retry"
        }

        test("a lapsed session offers Sign in; a revoked book offers Close") {
            val lapsed =
                bar(
                    chaptered(phase = PlayerPhase.Unavailable(AuthError.SessionExpired()), primary = PrimaryControl.SignIn),
                    CHAPTER_PROGRESS,
                )
            lapsed.querySelector(".tport-pill")!!.textContent shouldBe "Sign in"
            val revoked =
                bar(
                    chaptered(phase = PlayerPhase.Unavailable(PlaybackError.NoLongerAvailable()), primary = PrimaryControl.Close),
                    CHAPTER_PROGRESS,
                )
            revoked.querySelector(".tport-close")!!.getAttribute("aria-label") shouldBe "Close book"
        }

        test("finished offers Listen again, named for the book, and never a different book (F3)") {
            val host = bar(chaptered(phase = PlayerPhase.Finished, primary = PrimaryControl.ListenAgain), CHAPTER_PROGRESS)
            val pill = host.querySelector(".tport-pill.quiet") as HTMLElement
            pill.textContent shouldBe "Listen again"
            pill.getAttribute("aria-label") shouldBe "Listen again to The Way of Kings"
            host.textContent.orEmpty() shouldContain "Finished"
        }

        test("there is no separate notice strip any more") {
            bar(chaptered(phase = PlayerPhase.Failed(PlaybackError.ConnectionLost()), primary = PrimaryControl.Retry), CHAPTER_PROGRESS)
                .querySelector(".tport-note")
                .shouldBeNull()
        }

        test("a dropped connection says Reconnecting in the line and once in a status region; back keeps its slot") {
            val host = bar(chaptered(phase = PlayerPhase.Reconnecting, primary = PrimaryControl.Busy), CHAPTER_PROGRESS)
            host.querySelector(".tport-sub.desk")!!.textContent shouldBe "Reconnecting…"
            host.querySelector(".tport [role=status]")!!.textContent shouldBe "Reconnecting…"
            host.querySelector(".tport-b")!!.getAttribute("aria-label") shouldBe "Reconnecting. Pause"
            host.querySelector(".tport-back")!!.classList.contains("off") shouldBe true
        }

        test("the status region is empty in every other state, so it speaks only when reconnecting starts") {
            val host = bar(chaptered(), CHAPTER_PROGRESS)
            host.querySelector(".tport [role=status]")!!.textContent shouldBe ""
        }
    })
