package com.calypsan.listenup.web.shell

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit

private fun keydown(key: String) = KeyboardEvent("keydown", KeyboardEventInit(key = key, bubbles = true, cancelable = true))

/** The one window key dispatcher (spec §5.8): page, then palette, then player; the first to take a press keeps it. */
class WindowKeysTest :
    FunSpec({
        test("a page's handler answers before the palette's, and the palette's before the player's") {
            val keys = WindowKeys()
            val heard = mutableListOf<String>()
            keys.register(KeyPriority.Player) { heard.add("player ${it.key}") }
            keys.register(KeyPriority.Palette) { event -> (event.key == "/").also { if (it) heard += "palette /" } }
            keys.register(KeyPriority.Page) { event -> (event.key == "ArrowLeft").also { if (it) heard += "page ←" } }

            keys.dispatch(keydown("ArrowLeft"))
            keys.dispatch(keydown("/"))
            keys.dispatch(keydown(" "))

            heard shouldBe listOf("page ←", "palette /", "player  ")
        }

        test("a handler that is removed hears nothing more") {
            val keys = WindowKeys()
            var heard = 0
            val remove = keys.register(KeyPriority.Player) { true.also { heard++ } }
            keys.dispatch(keydown(" "))
            remove()
            keys.dispatch(keydown(" "))
            heard shouldBe 1
        }

        test("without the popover API the open-modal check falls back to dialog[open], and still finds an open dialog") {
            // Safari before 17 and Firefox before 125 have no popover API and throw a SyntaxError on `:popover-open`.
            // Chromium cannot be made to throw, but it can be made to look like those browsers to the feature check.
            openModalSelector() shouldBe "dialog[open], :popover-open"
            js("window.__luShowPopover = HTMLElement.prototype.showPopover; delete HTMLElement.prototype.showPopover;")
            val host = document.createElement("div") as HTMLElement
            document.body!!.appendChild(host)
            try {
                openModalSelector() shouldBe "dialog[open]"
                host.innerHTML = "<dialog open><button>Close</button></dialog><button id='lu-outside'>Play</button>"
                val outside = document.getElementById("lu-outside") as HTMLElement
                val event = keydown(" ")
                outside.dispatchEvent(event)
                keyTargetOf(event).isInDialog shouldBe true
            } finally {
                host.remove()
                js("HTMLElement.prototype.showPopover = window.__luShowPopover; delete window.__luShowPopover;")
            }
            openModalSelector() shouldBe "dialog[open], :popover-open"
        }

        test("one editability rule: fields, editable content and text roles are editable; buttons are not") {
            val host = document.createElement("div") as HTMLElement
            document.body!!.appendChild(host)

            fun element(html: String): HTMLElement {
                host.innerHTML = html
                return host.firstElementChild as HTMLElement
            }
            listOf(
                "<input type='text'>",
                "<input type='range'>",
                "<textarea></textarea>",
                "<select></select>",
                "<div contenteditable='true'></div>",
                "<div role='textbox'></div>",
                "<div role='combobox'></div>",
            ).forEach { html -> isEditableTarget(element(html)) shouldBe true }
            isEditableTarget(element("<button>Play</button>")) shouldBe false
            isEditableTarget(element("<div contenteditable='false'></div>")) shouldBe false
            host.remove()
        }
    })
