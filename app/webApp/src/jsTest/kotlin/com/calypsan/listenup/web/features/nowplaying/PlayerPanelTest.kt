package com.calypsan.listenup.web.features.nowplaying

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.calypsan.listenup.web.PHONE
import com.calypsan.listenup.web.ViewportFrame
import com.calypsan.listenup.web.ViewportFrames
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLDialogElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import kotlin.math.abs

/** Compose HTML listens for bubbling events; a default-constructed `Event` does not bubble. */
private fun bubbling(): dynamic = js("({ bubbles: true })")

private const val WIDE = 1440

private const val WIDE_HEIGHT = 900

private const val PHONE_HEIGHT = 844

/** One opened panel in a frame, and how many times it asked to be dismissed. */
private class OpenedPanel(
    val frame: ViewportFrame,
) {
    var dismissals = 0

    fun panel(): HTMLElement? = frame.findAll(".test-dlg").firstOrNull()

    fun opener(): HTMLElement = frame.find(".opener")
}

/**
 * [PlayerPanel], the one shell every player panel wears (§3.8): a modal bottom sheet on the phone page, a non-modal
 * popover hung from its opener on wide. Measured in frames, so "docked to the bottom" and "beside its opener" are
 * boxes rather than class names.
 */
class PlayerPanelTest :
    FunSpec({
        val frames = ViewportFrames()
        afterTest { frames.disposeAll() }

        suspend fun opened(
            width: Int,
            height: Int,
            sheet: Boolean,
            bodyHeight: Int = 120,
        ): OpenedPanel {
            var open by mutableStateOf(false)
            var placement by mutableStateOf<PanelPlacement>(PanelPlacement.Sheet)
            lateinit var opened: OpenedPanel
            val frame =
                frames.mount(width, height) {
                    Div(attrs = { style { property("padding", "120px 40px") } }) {
                        Button(attrs = {
                            classes("opener")
                            onClick { event ->
                                val opener = event.currentTarget as HTMLElement
                                placement = if (sheet) PanelPlacement.Sheet else PanelPlacement.Anchored(opener)
                                open = true
                            }
                        }) { Text("Open") }
                        Button(attrs = { classes("after") }) { Text("After") }
                    }
                    PlayerPanel(
                        open = open,
                        title = "Test panel",
                        panelClass = "test-dlg",
                        placement = placement,
                        onDismiss = {
                            opened.dismissals++
                            open = false
                        },
                    ) {
                        Div(attrs = { style { property("height", "${bodyHeight}px") } }) {
                            Button(attrs = { classes("inside") }) { Text("Inside") }
                        }
                    }
                }
            opened = OpenedPanel(frame)
            opened.opener().focus()
            opened.opener().click()
            awaitFrame()
            return opened
        }

        test("Sheet opens modally, docked to the bottom, with Close in its header") {
            val opened = opened(PHONE, PHONE_HEIGHT, sheet = true)
            val dialog = opened.panel() as HTMLDialogElement

            dialog.isModal() shouldBe true
            opened.frame.rect(dialog).bottom shouldBe (PHONE_HEIGHT.toDouble() plusOrMinus 1.0)
            val close = opened.frame.find(".test-dlg .dlg-head .dlg-close")
            close.tagName shouldBe "BUTTON"
            close.textContent shouldBe "Close"
            opened.frame.rect(close).height shouldBeGreaterThanOrEqual 44.0

            dialog.close()
        }

        test("Anchored opens in the top layer beside its opener, not modally") {
            val opened = opened(WIDE, WIDE_HEIGHT, sheet = false)
            val panel = opened.panel()!!

            panel.matches(":popover-open") shouldBe true
            panel.matches(":modal") shouldBe false
            panel.getAttribute("role") shouldBe "dialog"
            val opener = opened.frame.rect(opened.opener())
            val box = opened.frame.rect(panel)
            abs(box.top - opener.bottom) shouldBeLessThanOrEqual 8.0
            abs(box.left - opener.left) shouldBeLessThanOrEqual 8.0
        }

        test("Anchored puts focus on its heading as it opens") {
            val opened = opened(WIDE, WIDE_HEIGHT, sheet = false)

            opened.frame.host.ownerDocument!!
                .activeElement
                ?.id shouldBe "test-dlg-title"
        }

        test("Escape closes the popover and returns focus to the opener") {
            val opened = opened(WIDE, WIDE_HEIGHT, sheet = false)
            val heading = opened.frame.find("#test-dlg-title")

            heading.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = "Escape", bubbles = true, cancelable = true)))
            awaitFrame()

            opened.dismissals shouldBe 1
            opened.panel() shouldBe null
            opened.frame.host.ownerDocument!!
                .activeElement shouldBe opened.opener()
        }

        test("Close closes the popover and returns focus to the opener") {
            val opened = opened(WIDE, WIDE_HEIGHT, sheet = false)

            opened.frame.find(".test-dlg .dlg-close").click()
            awaitFrame()

            opened.panel() shouldBe null
            opened.frame.host.ownerDocument!!
                .activeElement shouldBe opened.opener()
        }

        test("an outside press closes the popover") {
            val opened = opened(WIDE, WIDE_HEIGHT, sheet = false)

            opened.frame.find(".after").dispatchEvent(Event("pointerdown", bubbling()))
            awaitFrame()

            opened.dismissals shouldBe 1
            opened.panel() shouldBe null
        }

        test("a press inside the popover, or on its opener, leaves it open") {
            val opened = opened(WIDE, WIDE_HEIGHT, sheet = false)

            opened.frame.find(".inside").dispatchEvent(Event("pointerdown", bubbling()))
            opened.opener().dispatchEvent(Event("pointerdown", bubbling()))
            awaitFrame()

            opened.dismissals shouldBe 0
        }

        test("Tab out of the popover moves on in the page order, closing it rather than trapping focus") {
            val opened = opened(WIDE, WIDE_HEIGHT, sheet = false)
            opened.frame.find(".inside").focus()

            val after = opened.frame.find(".after")
            after.focus()
            awaitFrame()

            opened.panel() shouldBe null
            opened.frame.host.ownerDocument!!
                .activeElement shouldBe after
        }

        test("a panel taller than the window scrolls inside itself") {
            val opened = opened(PHONE, 600, sheet = true, bodyHeight = 2_000)
            val dialog = opened.panel() as HTMLDialogElement

            dialog.scrollHeight shouldBeGreaterThan dialog.clientHeight
            opened.frame.css(dialog, "overflow-y") shouldBe "auto"
            opened.frame.rect(dialog).top shouldBeGreaterThanOrEqual 0.0

            dialog.close()
        }

        test("the placement follows the opener's viewport: a popover from 760px, a sheet below") {
            val wide = frames.mount(WIDE, WIDE_HEIGHT) { Button(attrs = { classes("probe") }) { Text("Probe") } }
            (panelPlacementFor(wide.find(".probe")) is PanelPlacement.Anchored) shouldBe true
            val narrow = frames.mount(PHONE, PHONE_HEIGHT) { Button(attrs = { classes("probe") }) { Text("Probe") } }
            panelPlacementFor(narrow.find(".probe")) shouldBe PanelPlacement.Sheet
            panelPlacementFor(null) shouldBe PanelPlacement.Sheet
        }
    })
