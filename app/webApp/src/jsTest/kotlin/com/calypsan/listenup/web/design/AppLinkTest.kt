package com.calypsan.listenup.web.design

import com.calypsan.listenup.web.MountRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.events.MouseEventInit

/** An in-app link is a real link: a plain click routes in place, anything else is the browser's. */
class AppLinkTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        fun mountLink(onOpen: () -> Unit): HTMLElement {
            val host = mounts.mount { AppLink(href = "/book/b1", onOpen = onOpen) { Text("Dune") } }
            return host.querySelector("a") as HTMLElement
        }

        /** Dispatches [init] and reports whether the link prevented the default; never follows the href. */
        fun clickOn(
            link: HTMLElement,
            init: MouseEventInit,
        ): Boolean {
            var prevented: Boolean? = null
            val host = link.parentElement!!
            val listener: (org.w3c.dom.events.Event) -> Unit = { event ->
                prevented = event.defaultPrevented
                event.preventDefault()
            }
            host.addEventListener("click", listener)
            link.dispatchEvent(MouseEvent("click", init))
            host.removeEventListener("click", listener)
            return prevented == true
        }

        test("it is an anchor with the given href") {
            val link = mountLink {}
            link.tagName shouldBe "A"
            link.getAttribute("href") shouldBe "/book/b1"
        }

        test("a plain click opens in place and stops the browser following the link") {
            var opened = 0
            val link = mountLink { opened++ }
            clickOn(link, MouseEventInit(bubbles = true, cancelable = true, button = 0)) shouldBe true
            opened shouldBe 1
        }

        test("a ctrl, meta or middle click is left to the browser") {
            var opened = 0
            val link = mountLink { opened++ }
            clickOn(link, MouseEventInit(bubbles = true, cancelable = true, ctrlKey = true)) shouldBe false
            clickOn(link, MouseEventInit(bubbles = true, cancelable = true, metaKey = true)) shouldBe false
            clickOn(link, MouseEventInit(bubbles = true, cancelable = true, button = 1)) shouldBe false
            opened shouldBe 0
        }
    })
