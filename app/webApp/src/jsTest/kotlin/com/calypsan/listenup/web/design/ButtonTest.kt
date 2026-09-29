package com.calypsan.listenup.web.design

import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.ViewportFrames
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.compose.web.attributes.onSubmit
import org.jetbrains.compose.web.dom.Form
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement

/**
 * One button, five kinds, three sizes.
 *
 * Web had no Button composable: 144 raw class strings across six overlapping classes, each choosing
 * its own height, radius and weight, and some forgetting `type="button"` inside a form.
 */
class ButtonTest :
    FunSpec({
        val mounts = MountRegistry()
        val frames = ViewportFrames()
        afterTest {
            mounts.disposeAll()
            frames.disposeAll()
        }

        ButtonKind.entries.forEach { kind ->
            test("$kind renders a real <button type=button> with its kind and size classes") {
                val host = mounts.mount { Button(kind = kind, label = "Go") { Text("Go") } }

                val button = host.querySelector("button") as HTMLButtonElement
                button.getAttribute("type") shouldBe "button"
                button.className shouldBe "btn btn-${kind.name.lowercase()} btn-md"
            }
        }

        test("a Button inside a form does not submit it unless it is the submit button") {
            var submits = 0
            var presses = 0
            val host =
                mounts.mount {
                    Form(attrs = {
                        onSubmit { event ->
                            event.preventDefault()
                            submits++
                        }
                    }) {
                        Button(kind = ButtonKind.Secondary, onClick = { presses++ }) { Text("Cancel") }
                        Button(kind = ButtonKind.Primary, submit = true) { Text("Save") }
                    }
                }

            val (cancel, save) = host.querySelectorAll("button").let { it.item(0) as HTMLButtonElement to it.item(1) as HTMLButtonElement }
            cancel.click()
            presses shouldBe 1
            submits shouldBe 0
            save.getAttribute("type") shouldBe "submit"
            save.click()
            submits shouldBe 1
        }

        test("sizes and full width are classes on the same element") {
            val host =
                mounts.mount {
                    Button(kind = ButtonKind.Primary, size = ButtonSize.Lg, fill = true) { Text("Sign in") }
                    Button(kind = ButtonKind.Secondary, size = ButtonSize.Sm) { Text("Undo") }
                }

            host.querySelector(".btn-primary.btn-lg.btn-fill") shouldBe host.querySelectorAll("button").item(0)
            host.querySelector(".btn-secondary.btn-sm") shouldBe host.querySelectorAll("button").item(1)
        }

        test("a disabled Button is disabled, and a press on it does nothing") {
            var presses = 0
            val host = mounts.mount { Button(kind = ButtonKind.Primary, enabled = false, onClick = { presses++ }) { Text("Save") } }

            val button = host.querySelector("button") as HTMLButtonElement
            button.disabled shouldBe true
            button.click()
            presses shouldBe 0
        }

        test("danger is filled with --danger-fill and primary with --coral-fill, in the real sheet") {
            val frame =
                frames.mount(480) {
                    Button(kind = ButtonKind.Danger) { Text("Delete") }
                    Button(kind = ButtonKind.Primary) { Text("Save") }
                }

            fun token(name: String): String {
                val probe =
                    frame.host.ownerDocument!!
                        .createElement("div")
                        .unsafeCast<HTMLElement>()
                probe.style.setProperty("background-color", "var($name)")
                frame.host.appendChild(probe)
                return frame.css(probe, "background-color").also { probe.remove() }
            }

            frame.css(frame.find(".btn-danger"), "background-color") shouldBe token("--danger-fill")
            frame.css(frame.find(".btn-primary"), "background-color") shouldBe token("--coral-fill")
        }

        test("an icon button names itself, and refuses to exist without a name") {
            val host = mounts.mount { Button(kind = ButtonKind.Icon, label = "Edit book") { Text("✎") } }

            val button = host.querySelector("button") as HTMLButtonElement
            button.getAttribute("aria-label") shouldBe "Edit book"
            button.getAttribute("title") shouldBe "Edit book"
            shouldThrow<IllegalArgumentException> { requireAccessibleName(ButtonKind.Icon, null) }
            shouldThrow<IllegalArgumentException> { requireAccessibleName(ButtonKind.Icon, " ") }
            requireAccessibleName(ButtonKind.Secondary, null)
        }

        test("a link that looks like a button is still a link") {
            val host =
                mounts.mount {
                    ButtonLink(href = "/api/v1/books/b1/documents/d1", kind = ButtonKind.Secondary, size = ButtonSize.Sm) {
                        Text("Open")
                    }
                }

            val link = host.querySelector("a") as HTMLAnchorElement
            link.getAttribute("href") shouldBe "/api/v1/books/b1/documents/d1"
            link.className shouldBe "btn btn-secondary btn-sm"
            host.querySelector("button") shouldBe null
        }

        test("no button rule takes away the page's focus ring") {
            // The ring is `.luw :focus-visible`, drawn for every control; a button rule that set
            // `outline: none` would silently remove it from all 144 buttons at once.
            val offenders = mutableListOf<String>()

            fun scan(rules: dynamic) {
                val length = rules.length as? Int ?: return
                for (i in 0 until length) {
                    val rule = rules.item(i)
                    val selector = rule?.selectorText as? String
                    if (selector == null) {
                        val nested = rule?.cssRules
                        if (nested != null) scan(nested)
                        continue
                    }
                    val outline = (rule.style.getPropertyValue("outline-style") as? String).orEmpty()
                    if (Regex("""\.btn(-[a-z]+)?\b""").containsMatchIn(selector) && outline == "none") offenders += selector
                }
            }
            val sheets = kotlinx.browser.document.styleSheets
            for (i in 0 until sheets.length) {
                val sheet = sheets.item(i) as? org.w3c.dom.css.CSSStyleSheet ?: continue
                scan(runCatching { sheet.cssRules }.getOrNull()?.asDynamic())
            }

            offenders shouldBe emptyList()
        }
    })
