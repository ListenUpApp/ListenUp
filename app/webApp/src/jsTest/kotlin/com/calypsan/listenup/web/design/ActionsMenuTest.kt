package com.calypsan.listenup.web.design

import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.browser.document
import kotlinx.browser.window
import org.jetbrains.compose.web.dom.Button
import org.w3c.dom.EventInit
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import org.w3c.dom.asList

/**
 * The shared "more actions" menu.
 *
 * ⛔ This spec exists because a sabotage pass found the component's own contract uncovered: making
 * it render an empty menu broke **nothing**, since its only coverage came through Book Detail's
 * specs, which never supply an empty list. A shared design component is asserted here, not left to
 * whichever consumer happens to exercise it.
 */
class ActionsMenuTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        fun menu(
            items: List<MenuAction>,
            enabled: Boolean = true,
        ): HTMLElement = mounts.mount { ActionsMenu(items = items, label = "Actions", enabled = enabled) }

        fun action(
            label: String,
            onSelect: () -> Unit = {},
        ) = MenuAction(label, WebIcon.Pencil, onSelect)

        // A button that opens an empty sheet promises something to do and then shows nothing.
        test("no items means no button at all, not a button that opens nothing") {
            val root = menu(emptyList())

            root.querySelectorAll("button").length shouldBe 0
            root.querySelectorAll(".menu-anchor").length shouldBe 0
        }

        test("the trigger is closed until pressed, and says so") {
            val root = menu(listOf(action("Edit")))

            val trigger = root.querySelector(".menu-anchor button") as HTMLElement
            trigger.getAttribute("aria-expanded") shouldBe "false"
            root.querySelectorAll(".menu-i").length shouldBe 0
        }

        test("pressing the trigger opens the items in the order given") {
            val root = menu(listOf(action("Edit"), action("Find metadata")))

            (root.querySelector(".menu-anchor button") as HTMLElement).click()
            awaitFrame()

            root.querySelectorAll(".menu-i").asList().map { it.textContent?.trim() } shouldContainExactly
                listOf("Edit", "Find metadata")
        }

        // ⛔ Real <button role="menuitem">s. A clickable <div> is unreachable by keyboard and
        // announces nothing — the account menu still has that shape and wants this treatment.
        test("every item is a real button that announces itself as a menu item") {
            val root = menu(listOf(action("Edit")))

            (root.querySelector(".menu-anchor button") as HTMLElement).click()
            awaitFrame()

            val item = root.querySelector(".menu-i") as HTMLElement
            item.tagName.lowercase() shouldBe "button"
            item.getAttribute("role") shouldBe "menuitem"
        }

        test("the trigger says it opens a menu") {
            val root = menu(listOf(action("Edit")))
            val trigger = root.querySelector(".menu-anchor button") as HTMLElement

            trigger.getAttribute("aria-haspopup") shouldBe "menu"
            trigger.click()
            awaitFrame()
            trigger.getAttribute("aria-expanded") shouldBe "true"
        }

        test("opening puts focus on the first item, and the arrows, Home and End walk the rest") {
            val root = menu(listOf(action("Edit"), action("Find metadata"), action("Delete")))

            (root.querySelector(".menu-anchor button") as HTMLElement).click()
            awaitFrame()
            val items = root.querySelectorAll(".menu-i").asList().map { it as HTMLElement }

            document.activeElement shouldBe items[0]
            items[0].press("ArrowDown")
            document.activeElement shouldBe items[1]
            items[1].press("End")
            document.activeElement shouldBe items[2]
            items[2].press("ArrowDown")
            document.activeElement shouldBe items[0]
            items[0].press("ArrowUp")
            document.activeElement shouldBe items[2]
            items[2].press("Home")
            document.activeElement shouldBe items[0]
        }

        test("Escape closes the menu and hands focus back to the trigger") {
            val root = menu(listOf(action("Edit"), action("Delete")))
            val trigger = root.querySelector(".menu-anchor button") as HTMLElement

            trigger.click()
            awaitFrame()
            (root.querySelector(".menu-i") as HTMLElement).press("Escape")
            awaitFrame()

            root.querySelectorAll(".menu").length shouldBe 0
            document.activeElement shouldBe trigger
        }

        test("a press anywhere else closes the menu") {
            val root = menu(listOf(action("Edit")))

            (root.querySelector(".menu-anchor button") as HTMLElement).click()
            awaitFrame()
            document.body!!.dispatchEvent(Event("pointerdown", EventInit(bubbles = true)))
            awaitFrame()

            root.querySelectorAll(".menu").length shouldBe 0
        }

        test("a highlighted item is visibly highlighted, inside the themed surface too") {
            // A `.luw .menu-i { background: transparent }` reset once outranked `.menu-i.hi` and
            // `.menu-i:hover`, so no menu item anywhere in the app ever lit up.
            val root =
                mounts.mount {
                    WebAppSurface {
                        Button(attrs = { classes("menu-i", "hi") }) {}
                    }
                }
            val item = root.querySelector(".menu-i") as HTMLElement

            window.getComputedStyle(item).backgroundColor shouldNotBe "rgba(0, 0, 0, 0)"
        }

        test("a disabled menu cannot be opened") {
            val root = menu(listOf(action("Edit")), enabled = false)

            val trigger = root.querySelector(".menu-anchor button") as HTMLElement
            trigger.hasAttribute("disabled") shouldBe true
        }
    })

private fun HTMLElement.press(key: String) {
    dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = key, bubbles = true, cancelable = true)))
}
