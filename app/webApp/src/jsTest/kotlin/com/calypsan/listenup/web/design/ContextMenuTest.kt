package com.calypsan.listenup.web.design

import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.browser.document
import kotlinx.coroutines.delay
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.events.MouseEventInit
import org.w3c.dom.pointerevents.PointerEvent
import org.w3c.dom.pointerevents.PointerEventInit

/**
 * The design system's context menu: right-click, the Menu key or Shift+F10, and a touch long press each open it on the
 * element it belongs to; Escape closes it and gives focus back.
 */
class ContextMenuTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        var clicks = 0
        val chosen = mutableListOf<String>()

        beforeTest {
            clicks = 0
            chosen.clear()
        }

        fun mountTarget(
            items: List<MenuAction> =
                listOf(
                    MenuAction("Play", WebIcon.Pencil, { chosen += "play" }),
                    MenuAction("Select", null, {
                        chosen +=
                            "select"
                    }),
                ),
        ): HTMLElement {
            val root =
                mounts.mount {
                    ContextMenuHost(items = items, label = "Actions for Dune") {
                        Div(attrs = {
                            classes("target")
                            tabIndex(0)
                            contextMenuTarget()
                            onClick { clicks++ }
                        }) { Text("Dune") }
                    }
                }
            return root.querySelector(".target") as HTMLElement
        }

        fun openItems(): List<String?> =
            document.querySelectorAll("[role=menu][aria-label='Actions for Dune'] .menu-i").asList().map { it.textContent?.trim() }

        test("a contextmenu event on the target opens the menu and keeps the browser's away") {
            val target = mountTarget()

            val event = MouseEvent("contextmenu", MouseEventInit(bubbles = true, cancelable = true, clientX = 40, clientY = 40))
            target.dispatchEvent(event)
            awaitFrame()

            event.defaultPrevented shouldBe true
            openItems() shouldContainExactly listOf("Play", "Select")
        }

        test("the ContextMenu key and Shift+F10 open it from the keyboard, focus on the first item") {
            listOf(
                KeyboardEventInit(key = "ContextMenu", bubbles = true, cancelable = true),
                KeyboardEventInit(key = "F10", shiftKey = true, bubbles = true, cancelable = true),
            ).forEach { init ->
                val target = mountTarget()
                target.focus()

                target.dispatchEvent(KeyboardEvent("keydown", init))
                awaitFrame()

                openItems() shouldContainExactly listOf("Play", "Select")
                (document.activeElement as HTMLElement).textContent?.trim() shouldBe "Play"
                mounts.disposeAll()
            }
        }

        test("a 500ms touch press opens it, and the click after the long press is swallowed") {
            val target = mountTarget()

            target.dispatchEvent(
                PointerEvent("pointerdown", PointerEventInit(pointerType = "touch", clientX = 10, clientY = 10, bubbles = true)),
            )
            delay(LONG_PRESS_WAIT_MS)
            awaitFrame()
            openItems() shouldContainExactly listOf("Play", "Select")

            target.dispatchEvent(PointerEvent("pointerup", PointerEventInit(pointerType = "touch", bubbles = true)))
            target.click()
            clicks shouldBe 0
        }

        test("a touch that moves 10px is a scroll, not a long press") {
            val target = mountTarget()

            target.dispatchEvent(
                PointerEvent("pointerdown", PointerEventInit(pointerType = "touch", clientX = 10, clientY = 10, bubbles = true)),
            )
            target.dispatchEvent(
                PointerEvent("pointermove", PointerEventInit(pointerType = "touch", clientX = 10, clientY = 30, bubbles = true)),
            )
            delay(LONG_PRESS_WAIT_MS)
            awaitFrame()

            openItems().size shouldBe 0
            target.click()
            clicks shouldBe 1
        }

        test("Escape closes it and returns focus to the target") {
            val target = mountTarget()
            target.focus()
            target.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = "ContextMenu", bubbles = true, cancelable = true)))
            awaitFrame()

            (document.activeElement as HTMLElement).dispatchEvent(
                KeyboardEvent("keydown", KeyboardEventInit(key = "Escape", bubbles = true, cancelable = true)),
            )
            awaitFrame()

            openItems().size shouldBe 0
            document.activeElement shouldBe target
        }

        test("choosing an item runs it and closes the menu") {
            val target = mountTarget()
            target.dispatchEvent(MouseEvent("contextmenu", MouseEventInit(bubbles = true, cancelable = true)))
            awaitFrame()

            (document.querySelectorAll(".menu-i").asList().last() as HTMLElement).click()
            awaitFrame()

            chosen shouldBe listOf("select")
            openItems().size shouldBe 0
            clicks shouldBe 0
        }

        test("no items, no menu") {
            val target = mountTarget(items = emptyList())

            target.dispatchEvent(MouseEvent("contextmenu", MouseEventInit(bubbles = true, cancelable = true)))
            awaitFrame()

            document.querySelectorAll("[role=menu]").length shouldBe 0
        }
    })

private const val LONG_PRESS_WAIT_MS = 550L
