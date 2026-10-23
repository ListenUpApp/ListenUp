package com.calypsan.listenup.web.features.nowplaying

import androidx.compose.runtime.CompositionLocalProvider
import com.calypsan.listenup.client.playback.SleepTimerMode
import com.calypsan.listenup.client.playback.SleepTimerState
import com.calypsan.listenup.client.presentation.player.PlayerProgress
import com.calypsan.listenup.web.NoCoverThemes
import com.calypsan.listenup.web.PHONE
import com.calypsan.listenup.web.ViewportFrame
import com.calypsan.listenup.web.ViewportFrames
import com.calypsan.listenup.web.awaitFrame
import com.calypsan.listenup.web.design.LocalCoverThemes
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.w3c.dom.HTMLDialogElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import kotlin.math.abs

private const val WIDE = 1440

private const val WIDE_HEIGHT = 900

private const val PHONE_HEIGHT = 844

/** 68 one-minute chapters, the 39th current, with half of it played. */
private val SIXTY_EIGHT = chapterRows(68, 60_000L)

private const val LONG_BOOK_MS = 68 * 60_000L

private val LONG_BOOK = loadedPlayerState(durationMs = LONG_BOOK_MS, chapters = SIXTY_EIGHT, currentChapterIndex = 38)

private val LONG_PROGRESS =
    PlayerProgress(
        chapterPositionMs = 30_000,
        chapterDurationMs = 60_000,
        bookPositionMs = 38 * 60_000L + 30_000,
        bookDurationMs = LONG_BOOK_MS,
    )

/**
 * Who opens what, where (§5.1, §5.4, C9): the bar's controls hang popovers from themselves at every width from 760px;
 * the phone page's pills open bottom sheets; More → Volume boost hangs from More when wide and is a sheet on a phone.
 */
class PanelOpenersTest :
    FunSpec({
        val frames = ViewportFrames()
        afterTest { frames.disposeAll() }

        fun wideBar(isNowPlayingOpen: Boolean = false): ViewportFrame =
            frames.mount(WIDE, WIDE_HEIGHT) {
                TransportBar(
                    state = LONG_BOOK.copy(sleepTimer = SleepTimerState.Active(SleepTimerMode.Duration(30), 1_421_000, 1_800_000, 0)),
                    progress = LONG_PROGRESS,
                    onPrimary = {},
                    onSeek = {},
                    onSkipBack = {},
                    onSkipForward = {},
                    onSetSpeed = {},
                    isNowPlayingOpen = isNowPlayingOpen,
                )
            }

        fun page(
            width: Int,
            height: Int,
        ): ViewportFrame =
            frames.mount(width, height) {
                CompositionLocalProvider(LocalCoverThemes provides NoCoverThemes) {
                    NowPlayingPage(state = LONG_BOOK, progress = LONG_PROGRESS, actions = NowPlayingActions())
                }
            }

        fun ViewportFrame.active(): HTMLElement? = host.ownerDocument!!.activeElement as? HTMLElement

        suspend fun ViewportFrame.pressEscape() {
            active()!!.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = "Escape", bubbles = true, cancelable = true)))
            awaitFrame()
        }

        /** The popover [panel] hangs against [opener]: within 8px of its top or bottom edge, and not modal. */
        fun ViewportFrame.shouldHangFrom(
            panel: HTMLElement,
            opener: HTMLElement,
        ) {
            panel.matches(":popover-open") shouldBe true
            panel.matches(":modal") shouldBe false
            val box = rect(panel)
            val anchor = rect(opener)
            minOf(abs(box.bottom - anchor.top), abs(box.top - anchor.bottom)) shouldBeLessThanOrEqual 8.0
        }

        listOf(".tport-speed" to ".speed-dlg", ".tport-sleep" to ".sleep-dlg").forEach { (control, panelClass) ->
            test("the bar's $control opens a popover hung from it, and Escape returns focus to it") {
                val frame = wideBar()
                val opener = frame.find(control)
                opener.focus()
                opener.click()
                awaitFrame()

                frame.shouldHangFrom(frame.find("$panelClass.panel-pop"), opener)
                opener.getAttribute("aria-expanded") shouldBe "true"

                frame.pressEscape()
                frame.findAll("$panelClass.panel-pop").size shouldBe 0
                frame.active() shouldBe opener
            }
        }

        test("on a non-player page the bar's Chapters opens the chapter popover centred on the current row") {
            val frame = wideBar(isNowPlayingOpen = false)
            val opener = frame.find(".tport-ch")
            opener.click()
            awaitFrame()
            awaitFrame()

            frame.shouldHangFrom(frame.find(".chap-dlg.panel-pop"), opener)
            val list = frame.rect(frame.find(".chap-dlg .chap-list"))
            val current = frame.rect(frame.find(".chap-dlg .chap-row.on"))
            abs((current.top + current.height / 2) - (list.top + list.height / 2)) shouldBeLessThan current.height
        }

        test("on the phone page the Speed pill opens a bottom sheet") {
            val frame = page(PHONE, PHONE_HEIGHT)
            frame.find(".np-mob .np-pill").click()
            awaitFrame()

            val sheet = frame.find("dialog.speed-dlg") as HTMLDialogElement
            sheet.isModal() shouldBe true
            frame.rect(sheet).bottom shouldBe (PHONE_HEIGHT.toDouble() plusOrMinus 1.0)
            sheet.close()
        }

        test("More → Volume boost opens beside More at 1440, and as a sheet at 390") {
            suspend fun ViewportFrame.chooseBoost(): HTMLElement {
                val more = find(".np-more button[aria-haspopup=menu]")
                more.click()
                awaitFrame()
                findAll(".menu-i").single { it.textContent.orEmpty().startsWith("Volume boost") }.click()
                awaitFrame()
                return more
            }

            val wide = page(WIDE, WIDE_HEIGHT)
            val more = wide.chooseBoost()
            wide.shouldHangFrom(wide.find(".boost-dlg.panel-pop"), more)
            frames.disposeAll()

            val phone = page(PHONE, PHONE_HEIGHT)
            phone.chooseBoost()
            val sheet = phone.find("dialog.boost-dlg") as HTMLDialogElement
            sheet.isModal() shouldBe true
            phone.rect(sheet).bottom shouldBe (PHONE_HEIGHT.toDouble() plusOrMinus 1.0)
            sheet.close()
        }
    })
