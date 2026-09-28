package com.calypsan.listenup.web.shell

import androidx.compose.runtime.Composable
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.design.WebAppSurface
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.events.MouseEventInit
import kotlinx.browser.document
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class AccountMenuTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        fun mount(content: @Composable () -> Unit): HTMLElement = mounts.mount { WebAppSurface { content() } }

        test("the menu is closed until asked for") {
            val host = mount { AccountMenu(onSignOut = {}) }

            host.querySelectorAll(".menu").length shouldBe 0
        }

        test("opening reveals a sign-out item") {
            val host = mount { AccountMenu(onSignOut = {}) }

            (host.querySelector(".iconbtn") as HTMLElement).click()
            awaitFrame()

            (host.querySelector(".menu") as HTMLElement).textContent.orEmpty() shouldContain "Sign out"
        }

        test("signing out reports it once and closes the menu") {
            var signOuts = 0
            val host = mount { AccountMenu(onSignOut = { signOuts++ }) }
            (host.querySelector(".iconbtn") as HTMLElement).click()
            awaitFrame()

            (host.querySelector(".menu-i") as HTMLElement).click()

            // The callback fired synchronously; the menu vanishing is a recomposition.
            signOuts shouldBe 1
            awaitFrame()
            host.querySelectorAll(".menu").length shouldBe 0
        }
        test("the trigger says it opens a menu, and whether it is open") {
            val host = mount { AccountMenu(onSignOut = {}) }
            val trigger = host.querySelector(".iconbtn") as HTMLElement

            trigger.getAttribute("aria-haspopup") shouldBe "menu"
            trigger.getAttribute("aria-expanded") shouldBe "false"
            trigger.getAttribute("aria-label") shouldBe "Account"

            trigger.click()
            awaitFrame()

            trigger.getAttribute("aria-expanded") shouldBe "true"
        }

        test("every item is a real menu-item button") {
            // ⛔ They were `<div onClick>`: Sign out and Your profile could not be reached by Tab.
            val host = mount { AccountMenu(onSignOut = {}, onOpenProfile = {}) }
            (host.querySelector(".iconbtn") as HTMLElement).click()
            awaitFrame()

            (host.querySelector(".menu") as HTMLElement).getAttribute("role") shouldBe "menu"
            val items = host.querySelectorAll(".menu-i").asList().filterIsInstance<HTMLElement>()
            items.map { it.tagName } shouldBe listOf("BUTTON", "BUTTON")
            items.map { it.getAttribute("role") } shouldBe listOf("menuitem", "menuitem")
            items.map { it.getAttribute("type") } shouldBe listOf("button", "button")
        }

        test("opening moves focus onto the first item") {
            val host = mount { AccountMenu(onSignOut = {}, onOpenProfile = {}) }
            (host.querySelector(".iconbtn") as HTMLElement).click()
            awaitFrame()

            (document.activeElement as HTMLElement).textContent.orEmpty() shouldContain "Your profile"
        }

        test("Escape closes the menu and hands focus back to the trigger") {
            val host = mount { AccountMenu(onSignOut = {}) }
            val trigger = host.querySelector(".iconbtn") as HTMLElement
            trigger.click()
            awaitFrame()

            (host.querySelector(".menu-i") as HTMLElement).dispatchEvent(
                KeyboardEvent("keydown", KeyboardEventInit(key = "Escape", bubbles = true, cancelable = true)),
            )
            awaitFrame()

            host.querySelectorAll(".menu").length shouldBe 0
            document.activeElement shouldBe trigger
        }

        test("a click anywhere else closes the menu") {
            val host = mount { AccountMenu(onSignOut = {}) }
            (host.querySelector(".iconbtn") as HTMLElement).click()
            awaitFrame()

            document.body!!.dispatchEvent(MouseEvent("pointerdown", MouseEventInit(bubbles = true)))
            awaitFrame()

            host.querySelectorAll(".menu").length shouldBe 0
        }

        test("a click inside the menu does not count as outside") {
            var signOuts = 0
            val host = mount { AccountMenu(onSignOut = { signOuts++ }) }
            (host.querySelector(".iconbtn") as HTMLElement).click()
            awaitFrame()

            val item = host.querySelector(".menu-i") as HTMLElement
            item.dispatchEvent(MouseEvent("pointerdown", MouseEventInit(bubbles = true)))
            awaitFrame()
            host.querySelectorAll(".menu").length shouldBe 1
            (host.querySelector(".menu-i") as HTMLElement).click()

            signOuts shouldBe 1
        }
    })

/** Resolves after the next animation frame — when a scheduled recomposition has applied. */
private suspend fun awaitFrame() {
    suspendCoroutine { continuation ->
        window.requestAnimationFrame { window.requestAnimationFrame { continuation.resume(Unit) } }
    }
}
