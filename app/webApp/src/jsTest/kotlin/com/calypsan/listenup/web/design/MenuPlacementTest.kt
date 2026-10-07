package com.calypsan.listenup.web.design

import com.calypsan.listenup.web.SMALL_PHONE
import com.calypsan.listenup.web.TABLET
import com.calypsan.listenup.web.ViewportFrames
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.jetbrains.compose.web.dom.Div

private val ITEMS =
    listOf(
        MenuAction("Add to a shelf you keep", WebIcon.Book, onSelect = {}),
        MenuAction("Remove this book from the library", WebIcon.Trash, onSelect = {}),
    )

/**
 * Where a menu opens. It hangs from its trigger's left edge — right for the account menu, which
 * sits at the content's left — but a trigger at the RIGHT edge (a row's trailing "more actions")
 * would hang a 216px menu off the side of the screen. So it measures itself on opening and, if it
 * would cross the viewport's edge, hangs from the trigger's right edge instead.
 */
class MenuPlacementTest :
    FunSpec({
        val frames = ViewportFrames()
        afterTest { frames.disposeAll() }

        fun menuAt(
            width: Int,
            edge: String,
        ) = frames.mount(width) {
            Div(attrs = {
                style {
                    property("display", "flex")
                    property("justify-content", edge)
                    property("padding", "8px")
                }
            }) { ActionsMenu(ITEMS) }
        }

        listOf(SMALL_PHONE, TABLET).forEach { width ->
            test("at ${width}px a menu opened at the right edge stays on screen") {
                val frame = menuAt(width, "flex-end")

                frame.find(".menu-anchor button").click()
                awaitFrame()
                awaitFrame()

                val menu = frame.rect(frame.find(".menu"))
                menu.left shouldBeGreaterThanOrEqual 0.0
                menu.right shouldBeLessThanOrEqual width.toDouble()
                frame.horizontalOverflow() shouldBe 0
            }
        }

        test("a menu with room to its right still hangs from its trigger's left edge") {
            val frame = menuAt(TABLET, "flex-start")

            frame.find(".menu-anchor button").click()
            awaitFrame()
            awaitFrame()

            frame.rect(frame.find(".menu")).left shouldBe frame.rect(frame.find(".menu-anchor")).left
        }
    })
