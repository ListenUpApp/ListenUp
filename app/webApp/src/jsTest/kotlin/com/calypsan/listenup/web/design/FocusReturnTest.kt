package com.calypsan.listenup.web.design

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.browser.document
import org.jetbrains.compose.web.dom.Form
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Section
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLDialogElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event

private fun active(): HTMLElement? = document.activeElement as? HTMLElement

/**
 * Focus never falls to the top of the page because a dialog closed or a pressed button changed under
 * it (#1562, WCAG 2.4.3): every confirm hands focus back to what opened it, a busy button keeps the
 * focus that pressed it, and a [FocusHold] lands it on what an in-place press came to.
 */
class FocusReturnTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest {
            mounts.disposeAll()
            (document.activeElement as? HTMLElement)?.blur()
        }

        fun confirmPage(onConfirm: (removeOpener: () -> Unit) -> Unit = {}): HTMLElement {
            var open by mutableStateOf(false)
            var openerShown by mutableStateOf(true)
            return mounts.mount {
                Section {
                    H2 { Text("Danger zone") }
                    if (openerShown) Button(kind = ButtonKind.Secondary, onClick = { open = true }) { Text("Delete") }
                }
                ConfirmDialog(
                    open = open,
                    title = "Delete it?",
                    body = "It goes for good.",
                    confirmLabel = "Delete",
                    onConfirm = {
                        open = false
                        onConfirm { openerShown = false }
                    },
                    onDismiss = { open = false },
                )
            }
        }

        fun HTMLElement.opener(): HTMLElement = querySelector("section .btn") as HTMLElement

        test("Cancel hands focus back to the button that opened the dialog") {
            val host = confirmPage()
            val opener = host.opener()
            opener.focus()
            opener.click()
            awaitFrame()
            active().shouldNotBeNull().closest("dialog").shouldNotBeNull()

            (document.querySelector("dialog .dlg-actions button") as HTMLElement).click()
            awaitFrame()

            document.querySelector("dialog").shouldBeNull()
            active() shouldBe opener
        }

        test("Escape hands focus back too") {
            val host = confirmPage()
            val opener = host.opener()
            opener.focus()
            opener.click()
            awaitFrame()

            // What Escape does to a modal <dialog>: it closes, and says so.
            val dialog = document.querySelector("dialog") as HTMLDialogElement
            dialog.close()
            dialog.dispatchEvent(Event("close"))
            awaitFrame()

            active() shouldBe opener
        }

        test("a verb that removes the opener lands focus on its section's heading") {
            val host = confirmPage { removeOpener -> removeOpener() }
            val opener = host.opener()
            opener.focus()
            opener.click()
            awaitFrame()

            (document.querySelectorAll("dialog .dlg-actions button").item(1) as HTMLElement).click()
            awaitFrame()

            active().shouldNotBeNull().textContent shouldBe "Danger zone"
        }

        test("a button whose own press makes it unavailable keeps that focus, and takes no second press") {
            var presses = 0
            var busy by mutableStateOf(false)
            val host =
                mounts.mount {
                    Button(kind = ButtonKind.Secondary, pressable = !busy, onClick = {
                        presses++
                        busy = true
                    }) { Text("Sync now") }
                }
            val button = host.querySelector(".btn") as HTMLElement
            button.focus()
            button.click()
            awaitFrame()

            active() shouldBe button
            button.hasAttribute("disabled") shouldBe false
            button.getAttribute("aria-disabled") shouldBe "true"
            button.click()
            presses shouldBe 1
        }

        test("an unpressable submit button does not submit its form") {
            var submits = 0
            val host =
                mounts.mount {
                    Form(attrs = {
                        addEventListener("submit") { event ->
                            event.preventDefault()
                            submits++
                        }
                    }) {
                        Button(kind = ButtonKind.Primary, submit = true, pressable = false) { Text("Save") }
                    }
                }

            (host.querySelector(".btn") as HTMLElement).click()

            submits shouldBe 0
        }

        test("FocusHold lands focus on what replaced the pressed button, preferring priority 1 over 2") {
            var phase by mutableStateOf(0)
            val host =
                mounts.mount {
                    FocusHold(key = phase) {
                        Button(kind = ButtonKind.Secondary, attrs = { focusLanding(priority = 2) }) { Text("Fallback") }
                        if (phase == 0) {
                            Button(kind = ButtonKind.Primary) { Text("Send") }
                        } else {
                            Span(attrs = { focusLanding() }) { Text("Sending…") }
                        }
                    }
                }
            (host.querySelectorAll(".btn").item(1) as HTMLElement).focus()

            phase = 1
            awaitFrame()

            active().shouldNotBeNull().textContent shouldBe "Sending…"
            active()!!.getAttribute("tabindex") shouldBe "-1"
        }
    })
