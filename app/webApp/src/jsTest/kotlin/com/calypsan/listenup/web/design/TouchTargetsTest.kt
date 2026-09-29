package com.calypsan.listenup.web.design

import com.calypsan.listenup.web.MIN_TARGET_PX
import com.calypsan.listenup.web.PHONE
import com.calypsan.listenup.web.Pointer
import com.calypsan.listenup.web.ViewportFrames
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.assertions.withClue
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Text

/**
 * The small controls, under a finger.
 *
 * On a mouse a 20–30px icon button is fine — a pointer lands where it is aimed. A fingertip is
 * roughly 44px across, so on a touchscreen each of these takes a tap anywhere within 22px of its
 * centre. The hit area grows; the drawing does not, so a coarse pointer gets reach without the
 * layout shifting under it.
 *
 * A representative set from every sheet, not the whole list: each is one class in the same
 * `(pointer: coarse)` rule, so a class that is present there is covered by the same mechanism.
 */
class TouchTargetsTest :
    FunSpec({
        val frames = ViewportFrames()
        afterTest { frames.disposeAll() }

        /** Each control on its own, spaced far enough apart that no neighbour can take its taps. */
        fun controls(pointer: Pointer) =
            frames.mount(PHONE, height = 1800, pointer = pointer) {
                Div(attrs = {
                    style {
                        property("display", "flex")
                        property("flex-direction", "column")
                        property("align-items", "flex-start")
                        property("gap", "40px")
                        property("padding", "40px")
                    }
                }) {
                    SMALL_CONTROLS.forEach { cls ->
                        Button(attrs = { classes(cls) }) { Text("×") }
                    }
                    // The shared Button at the sizes drawn under 44px: every kind and size is one
                    // `.btn`, so these three stand for all of them.
                    Button(kind = ButtonKind.Icon, size = ButtonSize.Sm, label = "Close") { Text("×") }
                    Button(kind = ButtonKind.Secondary, size = ButtonSize.Sm) { Text("Undo") }
                    Button(kind = ButtonKind.Primary) { Text("Save") }
                    Div(attrs = { classes("seg") }) { Button { Text("A") } }
                    Div(attrs = { classes("menu") }) { Button(attrs = { classes("menu-i") }) { Text("Open") } }
                }
            }

        test("on a touchscreen every small control takes a tap within 22px of its centre") {
            val frame = controls(Pointer.Touch)

            (SMALL_CONTROLS.map { ".$it" } + BUTTONS + ".seg button").forEach { selector ->
                withClue(selector) {
                    frame.takesTapsWithin(frame.find(selector), MIN_TARGET_PX / 2 - 1) shouldBe true
                }
            }
        }

        test("the hit area grows but the drawing does not") {
            val touch = controls(Pointer.Touch)
            val mouse = controls(Pointer.Mouse)

            (SMALL_CONTROLS.map { ".$it" } + BUTTONS).forEach { selector ->
                withClue(selector) {
                    touch.rect(touch.find(selector)).width shouldBe mouse.rect(mouse.find(selector)).width
                    touch.rect(touch.find(selector)).height shouldBe mouse.rect(mouse.find(selector)).height
                }
            }
        }

        test("on a mouse the controls keep their drawn size, so a desktop layout is untouched") {
            val frame = controls(Pointer.Mouse)

            frame.takesTapsWithin(frame.find(".rel-x"), MIN_TARGET_PX / 2 - 1) shouldBe false
        }

        test("a menu item is a full thumb's height on a touchscreen") {
            // Stacked items cannot borrow room from each other the way an isolated icon can, so the
            // item itself grows — a menu is a popover, and a taller one moves nothing else.
            val frame = controls(Pointer.Touch)

            frame.rect(frame.find(".menu-i")).height shouldBeGreaterThanOrEqual MIN_TARGET_PX
        }
    })

/** The undersized controls the audit listed, from 20px (`.rel-x`) to 36px. */
private val SMALL_CONTROLS =
    listOf(
        "rel-x",
        "pill",
        "inbox-notice-x",
        "srv-err-x",
        "cat-err-x",
        "shelf-notice-x",
        "shelf-grip",
        "ctl-zoom",
        "tport-note-x",
        "lset-x",
        "chr-a",
        "bd-pick-x",
        "bke-clear",
        "np-chip",
    )

/** The shared [Button]'s undersized drawings: a 30px icon, a 32px small button and a 40px one. */
private val BUTTONS = listOf(".btn-icon.btn-sm", ".btn-secondary.btn-sm", ".btn-primary.btn-md")
