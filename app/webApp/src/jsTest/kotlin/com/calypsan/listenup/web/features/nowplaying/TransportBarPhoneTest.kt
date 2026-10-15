package com.calypsan.listenup.web.features.nowplaying

import com.calypsan.listenup.client.playback.PlayerPhase
import com.calypsan.listenup.client.playback.SleepTimerMode
import com.calypsan.listenup.client.playback.SleepTimerState
import com.calypsan.listenup.client.presentation.player.PlayerChapterRow
import com.calypsan.listenup.client.presentation.player.PlayerProgress
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

private fun row(
    index: Int,
    title: String,
    startMs: Long,
) = PlayerChapterRow(index, index + 1, title, startMs, BOOK_MS / 2, isCurrent = false)

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
 * The player bar on a phone (board Mini-WebPhone): the one surface a listener reaches for with a thumb, often
 * without looking. It pins above the tab bar: a chapter line along its top, then the book (one target into Now
 * Playing), back N and play — and it fits at 320px without a sideways scroll. Forward, the scrubber and the
 * settings are one tap away in the full player.
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
                        state =
                            loadedPlayerState(
                                title = "The Way of Kings",
                                phase = PlayerPhase.Paused,
                                durationMs = BOOK_MS,
                                chapters = listOf(row(0, "Prelude", 0), row(1, "One", BOOK_MS / 2)),
                                currentChapterIndex = 0,
                                sleepTimer = sleepTimer,
                            ),
                        // At the start of the first chapter, half the book long: "5m left".
                        progress = PlayerProgress(chapterPositionMs = 0, chapterDurationMs = BOOK_MS / 2, bookPositionMs = 0, bookDurationMs = BOOK_MS),
                        onPrimary = {},
                        onSeek = {},
                        onSkipBack = {},
                        onSkipForward = {},
                        onSetSpeed = {},
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

        test("a chapter line runs along the bar's top edge") {
            val frame = player()
            val bar = frame.rect(frame.find(".tport"))
            val line = frame.find(".tport-line")

            frame.isShown(line) shouldBe true
            frame.rect(line).top shouldBe bar.top
            frame.rect(line).height shouldBe 3.0
        }

        test("the phone bar is the book, back N and play: each a full target, and it fits at 320px") {
            val frame = player()
            val bar = frame.find(".tport")
            val box = frame.rect(bar)

            listOf(".tport-body", ".tport-back", ".tport-b").forEach { selector ->
                val control = frame.find(selector)
                frame.isShown(control) shouldBe true
                frame.rect(control).left shouldBeGreaterThanOrEqual box.left
                frame.rect(control).right shouldBeLessThanOrEqual box.right
            }
            frame.rect(frame.find(".tport-back")).width shouldBeGreaterThanOrEqual MIN_TARGET_PX
            frame.rect(frame.find(".tport-b")).width shouldBe 48.0
            listOf(".tport-fwd", ".tport-head", ".tport-speed", ".tport-sleep", ".tport-ch", ".tport-expand").forEach { selector ->
                frame.findAll(selector).forEach { frame.isShown(it) shouldBe false }
            }
            bar.scrollWidth shouldBeLessThanOrEqual bar.clientWidth
            frame.horizontalOverflow() shouldBe 0
        }

        test("the line under the title says the chapter and its time left") {
            val frame = player()

            frame.isShown(frame.find(".tport-sub.phone")) shouldBe true
            frame.isShown(frame.find(".tport-sub.desk")) shouldBe false
            frame.find(".tport-sub.phone").textContent shouldBe "Paused · Prelude · 5m left"
        }

        test("an armed sleep timer shows in the line, and the bar still fits") {
            // ⛔ A timer nobody can see is one the listener forgets setting (kept from the old armed-control spec).
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

            frame.find(".tport-sub.phone").textContent shouldBe "Paused · Prelude · Sleep · 20m"
            frame.horizontalOverflow() shouldBe 0
        }

        test("the bar keeps its names on a phone") {
            val frame = player()

            frame.find(".tport-b").getAttribute("aria-label") shouldBe "Play"
            frame.find(".tport-body").getAttribute("aria-label").orEmpty().startsWith("Open Now Playing: The Way of Kings") shouldBe true
        }

        test("speed, chapters, boost and sleep are one tap into the full player, and it fills the phone") {
            val frame = player()
            frame.find(".tport-body").click()
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
            frame.find(".tport-body").click()
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
