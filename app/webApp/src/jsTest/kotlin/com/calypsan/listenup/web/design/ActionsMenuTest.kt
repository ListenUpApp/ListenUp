package com.calypsan.listenup.web.design

import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.w3c.dom.HTMLElement
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

        test("a disabled menu cannot be opened") {
            val root = menu(listOf(action("Edit")), enabled = false)

            val trigger = root.querySelector(".menu-anchor button") as HTMLElement
            trigger.hasAttribute("disabled") shouldBe true
        }
    })
