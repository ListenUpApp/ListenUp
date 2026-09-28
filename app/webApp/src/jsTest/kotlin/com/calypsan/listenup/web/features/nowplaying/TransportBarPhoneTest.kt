package com.calypsan.listenup.web.features.nowplaying

import com.calypsan.listenup.client.playback.SleepTimerMode
import com.calypsan.listenup.client.playback.SleepTimerState
import com.calypsan.listenup.web.ViewportFrame
import com.calypsan.listenup.web.ViewportFrames
import com.calypsan.listenup.web.awaitFrame
import com.calypsan.listenup.web.design.ToastHost
import com.calypsan.listenup.web.design.ToastQueue
import com.calypsan.listenup.web.design.ToastTone
import com.calypsan.listenup.web.design.WebIcon
import com.calypsan.listenup.web.shell.NavEntry
import com.calypsan.listenup.web.shell.NavSection
import com.calypsan.listenup.web.shell.Shell
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.jetbrains.compose.web.css.height
import org.jetbrains.compose.web.css.px
import org.jetbrains.compose.web.dom.Div
import org.w3c.dom.HTMLElement

private const val BOOK_MS = 600_000L

private const val PHONE = 320

private const val LARGE_PHONE = 390

private const val TABLET = 768

private const val MIN_TARGET_PX = 44.0

/** Taller than any phone, so the content region genuinely scrolls. */
private const val TALL_PAGE_PX = 2_000

private val NAV =
    NavSection(
        listOf(
            NavEntry("home", "Home", WebIcon.Home, href = "/"),
            NavEntry("library", "Library", WebIcon.Book),
            NavEntry("discover", "Discover", WebIcon.Compass),
            NavEntry("search", "Search", WebIcon.Search),
        ),
    )

/**
 * The transport bar on a phone: the one surface a listener reaches for with a thumb, often without
 * looking. It pins above the tab bar, gives the scrub a row of its own, keeps play, the time, the
 * chapters and the way into the full player — and fits at 320px without a sideways scroll.
 *
 * Rendered inside the real [Shell] at real widths (see [ViewportFrames]), because every claim here
 * is about where things land against each other, which only a laid-out page can answer.
 */
class TransportBarPhoneTest :
    FunSpec({
        val frames = ViewportFrames()
        afterTest { frames.disposeAll() }

        fun player(
            width: Int = PHONE,
            sleepTimer: SleepTimerState = SleepTimerState.Inactive,
            toasts: ToastQueue = ToastQueue(),
        ): ViewportFrame =
            frames.mount(width) {
                Shell(sections = listOf(NAV), footer = listOf(NavEntry("settings", "Settings", WebIcon.Cog)), active = "home") {
                    Div(attrs = {
                        classes("tall-page")
                        style { height(TALL_PAGE_PX.px) }
                    })
                    TransportBar(
                        state = TransportState("The Way of Kings", isPlaying = false, positionMs = 0, durationMs = BOOK_MS),
                        onPlayPause = {},
                        onSeek = {},
                        onSkipBack = {},
                        onSkipForward = {},
                        onSetSpeed = {},
                        chapters = listOf(TransportChapter("Prelude", 0), TransportChapter("One", BOOK_MS / 2)),
                        currentChapterIndex = 0,
                        sleepTimer = sleepTimer,
                    )
                }
                ToastHost(toasts)
            }

        test("the bar pins directly above the tab bar") {
            val frame = player()
            val bar = frame.find(".tport")

            frame.css(bar, "position") shouldBe "fixed"
            frame.rect(bar).width shouldBe PHONE.toDouble()
            frame.rect(bar).bottom shouldBe frame.rect(frame.find(".sidebar")).top
        }

        test("the scrub has a full-width row of its own, above the controls") {
            val frame = player()
            val bar = frame.rect(frame.find(".tport"))
            val scrub = frame.rect(frame.find(".tport-scrub"))
            val play = frame.rect(frame.find(".tport-b"))

            scrub.bottom shouldBeLessThanOrEqual play.top
            // Edge to edge less the bar's own gutters: a thumb needs every pixel of a seek.
            scrub.width shouldBeGreaterThanOrEqual bar.width - 2 * 16
        }

        test("play, the time, chapters and the way into the player all stay, and all fit at 320px") {
            val frame = player()
            val bar = frame.find(".tport")
            val box = frame.rect(bar)

            val kept = listOf(".tport-b", ".tport-time", ".tport-ch", ".tport-expand", ".tport-scrub")
            kept.forEach { selector ->
                val control = frame.find(selector)
                frame.isShown(control) shouldBe true
                frame.rect(control).left shouldBeGreaterThanOrEqual box.left
                frame.rect(control).right shouldBeLessThanOrEqual box.right
            }
            listOf(".tport-b", ".tport-ch", ".tport-expand").forEach { selector ->
                frame.rect(frame.find(selector)).height shouldBeGreaterThanOrEqual MIN_TARGET_PX
                frame.rect(frame.find(selector)).width shouldBeGreaterThanOrEqual MIN_TARGET_PX
            }
            bar.scrollWidth shouldBeLessThanOrEqual bar.clientWidth
            frame.horizontalOverflow() shouldBe 0
        }

        test("the chapters control is not a skip, so it survives the phone layout") {
            val frame = player()
            val chapters = frame.find("[aria-label='Chapters']")

            chapters.classList.contains("tport-ch") shouldBe true
            chapters.classList.contains("tport-skip") shouldBe false
            frame.isShown(chapters) shouldBe true
        }

        test("the session controls step aside on a phone, because the full player holds them") {
            val frame = player()

            listOf(".tport-speed", ".tport-boost", ".tport-sleep", ".tport-skip").forEach { selector ->
                frame.findAll(selector).forEach { frame.isShown(it) shouldBe false }
            }
        }

        test("an armed sleep timer stays on the bar, and the bar still fits") {
            // ⛔ A timer nobody can see is one the listener forgets setting — so the one session
            // control that returns to a phone's bar is the sleep timer, and only while it runs.
            val frame =
                player(
                    sleepTimer =
                        SleepTimerState.Active(
                            mode = SleepTimerMode.Duration(minutes = 30),
                            remainingMs = 1_200_000L,
                            totalMs = 1_800_000L,
                            startedAt = 0L,
                        ),
                )
            val bar = frame.find(".tport")
            val sleep = frame.find("[aria-label='Sleep timer, set']")

            frame.isShown(sleep) shouldBe true
            frame.rect(sleep).width shouldBeGreaterThanOrEqual MIN_TARGET_PX
            frame.rect(sleep).right shouldBeLessThanOrEqual frame.rect(bar).right
            bar.scrollWidth shouldBeLessThanOrEqual bar.clientWidth
            frame.horizontalOverflow() shouldBe 0
        }

        test("the bar keeps its names and its spoken position on a phone") {
            val frame = player()

            frame.find(".tport-b").getAttribute("aria-label") shouldBe "Play"
            frame.find(".tport-expand").getAttribute("aria-label") shouldBe "Open the player"
            frame.find(".tport-scrub").getAttribute("aria-valuetext") shouldBe
                "${formatElapsed(0)} of ${formatElapsed(BOOK_MS)}"
        }

        test("speed, boost and sleep are one tap into the full player, and it fills the phone") {
            val frame = player()
            frame.find(".tport-expand").click()
            awaitFrame()

            val panel = frame.rect(frame.find(".np-dlg"))
            panel.width shouldBe PHONE.toDouble()
            panel.height shouldBe frame.height.toDouble()

            val chips = frame.findAll(".np-chip").filter(frame::isShown)
            chips.map { it.textContent.orEmpty().trim() } shouldBe listOf("1×", "Chapters", "Boost", "Sleep")
            chips.forEach { frame.rect(it).height shouldBeGreaterThanOrEqual MIN_TARGET_PX }
            frame.horizontalOverflow() shouldBe 0
        }

        test("the full player's sleep chip opens the sleep timer on a phone") {
            val frame = player()
            frame.find(".tport-expand").click()
            awaitFrame()

            frame.findAll(".np-chip").single { it.textContent.orEmpty().trim() == "Sleep" }.click()
            awaitFrame()

            frame.findAll("dialog[open]").size shouldBe 2
        }

        test("the end of a page scrolls clear of the bar instead of hiding under it") {
            val frame = player(width = LARGE_PHONE)
            val main = frame.find(".shell-main")

            main.scrollTop = main.scrollHeight.toDouble()
            val pageEnd = frame.rect(frame.find(".tall-page")).bottom

            pageEnd shouldBeLessThanOrEqual frame.rect(frame.find(".tport")).top
            // Focus moving to something under the bar scrolls it clear too.
            frame.css(main, "scroll-padding-bottom").removeSuffix("px").toDouble() shouldBeGreaterThanOrEqual
                frame.rect(frame.find(".tport")).height
        }

        test("a toast lands above the player and the tab bar, not on top of them") {
            val toasts = ToastQueue()
            val frame = player(toasts = toasts)

            toasts.show("Could not reach the server.", ToastTone.Failure)
            awaitFrame()

            frame.rect(frame.find(".toastwrap")).bottom shouldBeLessThanOrEqual frame.rect(frame.find(".tport")).top
        }

        test("a tablet's bar fits its row as well") {
            val frame = player(width = TABLET)
            val bar = frame.find(".tport") as HTMLElement

            bar.scrollWidth shouldBeLessThanOrEqual bar.clientWidth
            frame.horizontalOverflow() shouldBe 0
        }
    })
