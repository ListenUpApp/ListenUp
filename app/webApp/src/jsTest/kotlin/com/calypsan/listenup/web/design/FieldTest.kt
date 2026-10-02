package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.calypsan.listenup.web.MountRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlinx.browser.window
import org.w3c.dom.EventInit
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class FieldTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        fun mount(content: @Composable () -> Unit): HTMLElement = mounts.mount { WebAppSurface { content() } }

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

        test("an error message is tied to its field, so a screen reader reads it with the field") {
            // A red border alone is invisible to a screen reader, and a message floating below the
            // form belongs to nothing. `aria-describedby` is what makes the message the field's own.
            val host =
                mount {
                    Field(label = "Email", value = "", id = "probe", errorText = "Enter a valid email address.", onInput = {})
                }
            val input = host.querySelector(".f-input") as HTMLInputElement
            val message = host.querySelector(".f-err") as HTMLElement

            input.getAttribute("aria-invalid") shouldBe "true"
            input.getAttribute("aria-describedby") shouldBe message.id
            message.id shouldBe "probe-err"
            message.textContent.orEmpty() shouldContain "valid email"
            host.querySelectorAll(".f-box.err").length shouldBe 1
        }

        test("a field with no error claims no error") {
            val host = mount { Field(label = "Email", value = "", onInput = {}) }
            val input = host.querySelector(".f-input") as HTMLInputElement

            input.hasAttribute("aria-invalid") shouldBe false
            input.hasAttribute("aria-describedby") shouldBe false
            host.querySelector(".f-err") shouldBe null
        }

        test("a password field ties its error message to the input too") {
            val host =
                mount {
                    PasswordField(label = "Confirm", value = "", id = "pw", errorText = "The two passwords do not match.", onInput = {})
                }
            val input = host.querySelector(".f-input") as HTMLInputElement

            input.getAttribute("aria-invalid") shouldBe "true"
            input.getAttribute("aria-describedby") shouldBe "pw-err"
            (host.querySelector("#pw-err") as HTMLElement).textContent.orEmpty() shouldContain "do not match"
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

        test("a switch's wrapper is not drawn as a second, empty switch") {
            // A dead design-kit `.sw` rule gave the SwitchField label a 46px grey pill of its own,
            // so every switch rendered inside a stray track. The track is `.sw-track`, and only it.
            val host = mount { SwitchField(label = "Wi-Fi only", checked = false, onChange = {}) }
            val wrapper = host.querySelector(".sw") as HTMLElement

            window.getComputedStyle(wrapper).backgroundColor shouldBe "rgba(0, 0, 0, 0)"
            window.getComputedStyle(wrapper).height shouldNotBe "27px"
        }

        test("a switch is fully controlled: a click asks, and only its state moves the box") {
            val asked = mutableListOf<Boolean>()
            var checked by mutableStateOf(true)
            val host = mount { SwitchField(label = "Sync", checked = checked, onChange = { asked += it }) }
            val box = host.querySelector(".sw-in") as HTMLInputElement

            box.click()
            awaitFrame()
            asked shouldBe listOf(false)
            box.checked shouldBe true

            checked = false
            awaitFrame()
            box.checked shouldBe false
            checked = true
            awaitFrame()
            box.checked shouldBe true
        }

        test("the eye is a named toggle that says whether the password is showing") {
            val host = mount { PasswordField(label = "Password", value = "hunter2", onInput = {}) }
            val eye = host.querySelector(".f-eye") as HTMLElement

            eye.getAttribute("aria-label") shouldBe "Show password"
            eye.getAttribute("aria-pressed") shouldBe "false"

            eye.click()
            awaitFrame()

            (host.querySelector(".f-eye") as HTMLElement).getAttribute("aria-pressed") shouldBe "true"
        }
    })

/** Resolves after the next animation frame — when a scheduled recomposition has applied. */
private suspend fun awaitFrame() {
    suspendCoroutine { continuation ->
        window.requestAnimationFrame { window.requestAnimationFrame { continuation.resume(Unit) } }
    }
}
