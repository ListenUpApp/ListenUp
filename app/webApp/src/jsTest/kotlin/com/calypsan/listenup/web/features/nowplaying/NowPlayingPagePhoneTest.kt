package com.calypsan.listenup.web.features.nowplaying

import com.calypsan.listenup.api.error.PlaybackError
import com.calypsan.listenup.client.playback.PlayerPhase
import com.calypsan.listenup.client.playback.SleepTimerMode
import com.calypsan.listenup.client.playback.SleepTimerState
import com.calypsan.listenup.client.presentation.player.PlayerProgress
import com.calypsan.listenup.client.presentation.player.PlayerUiState
import com.calypsan.listenup.client.presentation.player.PrimaryControl
import com.calypsan.listenup.web.NoCoverThemes
import com.calypsan.listenup.web.PHONE
import com.calypsan.listenup.web.ViewportFrame
import com.calypsan.listenup.web.ViewportFrames
import com.calypsan.listenup.web.awaitFrame
import com.calypsan.listenup.web.design.LocalCoverThemes
import com.calypsan.listenup.web.design.WebIcon
import com.calypsan.listenup.web.shell.NavEntry
import com.calypsan.listenup.web.shell.NavSection
import com.calypsan.listenup.web.shell.Shell
import androidx.compose.runtime.CompositionLocalProvider
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.jetbrains.compose.web.dom.Div
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement

/**
 * Now Playing below 760px (§5.2's web phone column, `NP-WebPhone`): the page is the whole screen and draws its own
 * transport — previous, back N, a 76px play, forward N, next — over the chapter's scrubber with the book sentence
 * between its times, and the settings as pills. Measured in a real 390×844 frame.
 */
class NowPlayingPagePhoneTest :
    FunSpec({
        val frames = ViewportFrames()
        afterTest { frames.disposeAll() }

        fun phonePage(
            state: PlayerUiState.Loaded,
            progress: PlayerProgress = PlayerProgress.Zero,
            actions: NowPlayingActions = NowPlayingActions(),
        ): ViewportFrame =
            frames.mount(width = PHONE, height = PHONE_HEIGHT) {
                CompositionLocalProvider(LocalCoverThemes provides NoCoverThemes) {
                    NowPlayingPage(state = state, progress = progress, actions = actions)
                }
            }

        test("the phone page draws its own transport: previous, back, a 76px play, forward, next") {
            val frame = phonePage(wayOfKings(chapters = chapterRows(75, 1_235_000), currentChapterIndex = 5))
            val play = frame.find(".np-mob .np-play")
            play.getBoundingClientRect().width shouldBe 76.0
            play.getAttribute("aria-label") shouldBe "Pause"
            frame.find(".np-mob .np-prev").getAttribute("aria-description") shouldBe "Goes to the start of this chapter first."
            frame.find(".np-mob .np-skip-back .np-skip-n").getAttribute("aria-hidden") shouldBe "true"
            frame.find(".np-mob .np-skip-back").getAttribute("aria-label") shouldBe "Skip back 10 seconds"
            frame.find(".np-mob .np-skip-fwd").getAttribute("aria-label") shouldBe "Skip forward 30 seconds"
        }

        test("the scrubber is the chapter's, with the book sentence between its times") {
            val frame =
                phonePage(
                    wayOfKings(chapters = chapterRows(75, 1_235_000), currentChapterIndex = 5),
                    progress = PlayerProgress(724_000, 1_235_000, 18_000_000, KINGS_MS),
                )
            val range = frame.find(".np-mob input[type=range]") as HTMLInputElement
            range.getAttribute("aria-label") shouldBe "Chapter position"
            range.max shouldBe "1235000"
            range.getAttribute("aria-valuetext") shouldBe "12:04 of 20:35"
            frame.find(".np-mob .np-sentence").textContent shouldBe "Book 10% · 40h 30m left"
            frame.find(".np-mob .np-ch").textContent shouldBe "Chapter 6 of 75 · Chapter 6"
        }

        test("Next is disabled in the last chapter; Previous is not, even in chapter 1") {
            val frame = phonePage(wayOfKings(chapters = chapterRows(3), currentChapterIndex = 2))
            (frame.find(".np-mob .np-next") as HTMLButtonElement).disabled shouldBe true
            (frame.find(".np-mob .np-prev") as HTMLButtonElement).disabled shouldBe false
            val first = phonePage(wayOfKings(chapters = chapterRows(3), currentChapterIndex = 0))
            (first.find(".np-mob .np-prev") as HTMLButtonElement).disabled shouldBe false
        }

        test("a chapterless book disables Previous rather than jump to 0:00") {
            val frame = phonePage(wayOfKings())
            (frame.find(".np-mob .np-prev") as HTMLButtonElement).disabled shouldBe true
        }

        test("previous and next report one press each to the shared model, which decides where they land") {
            var previous = 0
            var next = 0
            val frame =
                phonePage(
                    wayOfKings(chapters = chapterRows(5), currentChapterIndex = 2),
                    actions = NowPlayingActions(onPreviousChapter = { previous++ }, onNextChapter = { next++ }),
                )
            frame.find(".np-mob .np-prev").click()
            frame.find(".np-mob .np-next").click()
            previous shouldBe 1
            next shouldBe 1
        }

        test("busy shows a spinner in the play circle and it still pauses") {
            var presses = 0
            val frame = phonePage(wayOfKings(phase = PlayerPhase.Buffering), actions = NowPlayingActions(onPrimary = { presses++ }))
            frame.find(".np-mob .np-play .tport-wait").shouldNotBeNull()
            frame.find(".np-mob .np-play").getAttribute("aria-label") shouldBe "Loading. Pause"
            frame.find(".np-mob .np-play").click()
            presses shouldBe 1
        }

        test("Reconnecting draws as busy and says so") {
            val frame = phonePage(wayOfKings(phase = PlayerPhase.Reconnecting))
            frame.find(".np-mob .np-play .tport-wait").shouldNotBeNull()
            frame.find(".np-mob .np-play").getAttribute("aria-label") shouldBe "Reconnecting. Pause"
        }

        test("playing shows no spinner") {
            val frame = phonePage(wayOfKings(phase = PlayerPhase.Playing))
            frame.host.querySelector(".np-mob .np-play .tport-wait") shouldBe null
        }

        test("a stall keeps the skips' slots but disables them") {
            val frame = phonePage(wayOfKings(phase = PlayerPhase.Buffering))
            (frame.find(".np-mob .np-skip-back") as HTMLButtonElement).disabled shouldBe true
            (frame.find(".np-mob .np-skip-fwd") as HTMLButtonElement).disabled shouldBe true
        }

        test("the settings are Speed, Sleep and Chapters; boost is not among them") {
            val frame = phonePage(wayOfKings(chapters = chapterRows(3), currentChapterIndex = 0, boostDb = 6f))
            frame.findAll(".np-mob .np-pill").map { it.textContent } shouldBe listOf("1×", "Sleep", "Chapters")
        }

        test("Speed opens its picker over the page, and closing it leaves the page") {
            val frame = phonePage(wayOfKings())
            frame.find(".np-mob .np-pill").click()
            awaitFrame()
            frame.find("dialog.speed-dlg[open]").shouldNotBeNull()
            (frame.find("dialog.speed-dlg .dlg-close") as HTMLElement).click()
            awaitFrame()
            frame.host.ownerDocument!!.querySelector("dialog.speed-dlg[open]") shouldBe null
            frame.find(".np-page").shouldNotBeNull()
        }

        test("a problem card takes the transport's place, and the scrubber is disabled under it; the settings stay") {
            val frame =
                phonePage(
                    wayOfKings(
                        phase = PlayerPhase.Failed(PlaybackError.ConnectionLost()),
                        primary = PrimaryControl.Retry,
                        chapters = chapterRows(3),
                        currentChapterIndex = 0,
                    ),
                )
            frame.find(".np-mob .np-card[role=alert]").shouldNotBeNull()
            frame.host.querySelector(".np-mob .np-transport") shouldBe null
            (frame.find(".np-mob input[type=range]") as HTMLInputElement).disabled shouldBe true
            frame.findAll(".np-mob .np-pill").size shouldBe 3
        }

        test("an armed end-of-chapter timer's pill reads its real countdown, not 0:00") {
            val frame =
                phonePage(
                    wayOfKings(chapters = chapterRows(3, 1_200_000), currentChapterIndex = 1)
                        .copy(sleepTimer = SleepTimerState.Active(SleepTimerMode.EndOfChapter, 0, 0, 0)),
                    progress = PlayerProgress(720_000, 1_200_000, 1_920_000, 3_600_000),
                )
            val pill = frame.findAll(".np-mob .np-pill")[1]
            pill.textContent shouldBe "8:00"
            pill.getAttribute("aria-label") shouldBe "Sleep timer, 8:00 left"
        }

        test("at phone width an immersive shell hides the tabs, the header and the bar") {
            val frame =
                frames.mount(width = PHONE, height = PHONE_HEIGHT) {
                    Shell(
                        sections = listOf(NavSection(listOf(NavEntry("library", "Library", WebIcon.Book)))),
                        active = "library",
                        immersive = true,
                    ) { Div(attrs = { classes("tport") }) }
                }
            frame.css(frame.find(".tport"), "display") shouldBe "none"
            frame.css(frame.find(".sidebar"), "display") shouldBe "none"
            frame.findAll(".np-mob").size shouldBe 0
        }
    })

private const val PHONE_HEIGHT = 844
