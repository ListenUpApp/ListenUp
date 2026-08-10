package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.browser.document
import kotlinx.browser.window
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.EventInit
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

private fun mount(content: @Composable () -> Unit): HTMLElement {
    val host = document.createElement("div") as HTMLElement
    document.body!!.appendChild(host)
    renderComposable(root = host) { WebAppSurface { content() } }
    return host
}

class FieldTest :
    FunSpec({

        test("a field renders its label and current value") {
            val host = mount { Field(label = "Email", value = "ada@example.com", onInput = {}) }

            (host.querySelector(".f-label") as HTMLElement).textContent.orEmpty() shouldContain "Email"
            (host.querySelector(".f-input") as HTMLInputElement).value shouldBe "ada@example.com"
        }

        test("typing reports the new value") {
            var captured: String? = null
            val host = mount { Field(label = "Email", value = "", onInput = { captured = it }) }

            val input = host.querySelector(".f-input") as HTMLInputElement
            input.value = "ada@example.com"
            input.dispatchEvent(Event("input", EventInit(bubbles = true)))

            captured shouldBe "ada@example.com"
        }

        test("a leading icon is optional") {
            val withIcon = mount { Field(label = "Email", value = "", leading = WebIcon.Mail, onInput = {}) }
            val without = mount { Field(label = "Name", value = "", onInput = {}) }

            withIcon.querySelectorAll(".f-box svg").length shouldBe 1
            without.querySelectorAll(".f-box svg").length shouldBe 0
        }

        test("an errored field is marked so CSS can colour it") {
            // The sheet styles by class, so the error has to be a class rather than an inline
            // style — otherwise dark mode and the focus ring both fight it.
            val host = mount { Field(label = "Email", value = "", error = true, onInput = {}) }

            host.querySelectorAll(".f-box.err").length shouldBe 1
        }

        test("a password field hides its value until the eye is clicked") {
            val host = mount { PasswordField(label = "Password", value = "hunter2", onInput = {}) }
            val input = host.querySelector(".f-input") as HTMLInputElement

            input.getAttribute("type") shouldBe "password"

            (host.querySelector(".f-eye") as HTMLElement).click()
            // Recomposition is frame-scheduled (see WebAppRootTest.awaitFrame), so the toggled
            // `type` attribute only exists on the input after the next frame.
            awaitFrame()

            (host.querySelector(".f-input") as HTMLInputElement).getAttribute("type") shouldBe "text"
        }
    })

/** Resolves after the next animation frame — when a scheduled recomposition has applied. */
private suspend fun awaitFrame() {
    suspendCoroutine { continuation ->
        window.requestAnimationFrame { window.requestAnimationFrame { continuation.resume(Unit) } }
    }
}
