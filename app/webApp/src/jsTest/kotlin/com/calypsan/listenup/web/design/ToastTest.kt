package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.browser.document
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.HTMLElement

private fun mount(content: @Composable () -> Unit): HTMLElement {
    val host = document.createElement("div") as HTMLElement
    document.body!!.appendChild(host)
    renderComposable(root = host) { WebAppSurface { content() } }
    return host
}

class ToastTest :
    FunSpec({

        test("a queue starts with nothing on screen") {
            val host = mount { ToastHost(ToastQueue()) }

            host.querySelectorAll(".toastwrap").length shouldBe 0
        }

        test("a shown toast reaches the DOM with its text") {
            val queue = ToastQueue()
            val host = mount { ToastHost(queue) }

            queue.show("Could not reach the server.", ToastTone.Failure)
            awaitFrame()

            (host.querySelector(".toast") as HTMLElement)
                .textContent
                .orEmpty() shouldContain "Could not reach the server."
        }

        test("a failure interrupts, a notice waits") {
            // The role is not decoration: a toast is the ONLY report a failure gets on this
            // platform, so a screen reader has to be told immediately rather than at the next
            // pause. `alert` and `status` carry exactly that difference.
            val queue = ToastQueue()
            val host = mount { ToastHost(queue) }

            queue.show("Saved.", ToastTone.Notice)
            awaitFrame()
            (host.querySelector(".toast") as HTMLElement).getAttribute("role") shouldBe "status"

            queue.show("It broke.", ToastTone.Failure)
            awaitFrame()
            val toasts = host.querySelectorAll(".toast")
            (toasts.item(1) as HTMLElement).getAttribute("role") shouldBe "alert"
        }

        test("the same failure twice does not stack twice") {
            // `message` is a per-subtype constant, so a retry loop emits identical text over and
            // over. Three copies of one line tell the reader nothing the first did not.
            val queue = ToastQueue()

            val first = queue.show("Could not reach the server.", ToastTone.Failure)
            val second = queue.show("Could not reach the server.", ToastTone.Failure)

            second shouldBe first
            queue.messages.size shouldBe 1
        }

        test("a repeat only collapses against the newest, not anything still on screen") {
            // Otherwise A, B, A would silently drop the second A — which is a real sequence (two
            // failing operations interleaving) and the reader needs to see that A happened again.
            val queue = ToastQueue()

            queue.show("A", ToastTone.Failure)
            queue.show("B", ToastTone.Failure)
            queue.show("A", ToastTone.Failure)

            queue.messages.map { it.text } shouldBe listOf("A", "B", "A")
        }

        test("the stack is capped, and it is the oldest that goes") {
            // A toast tower is an error page with worse manners. The newest failure is the one
            // still happening, so it is the one that must survive.
            val queue = ToastQueue()

            listOf("one", "two", "three", "four").forEach { queue.show(it, ToastTone.Failure) }

            queue.messages.map { it.text } shouldBe listOf("two", "three", "four")
        }

        test("dismissing takes exactly the toast that was clicked") {
            val queue = ToastQueue()
            val host = mount { ToastHost(queue) }
            queue.show("first", ToastTone.Failure)
            queue.show("second", ToastTone.Failure)
            awaitFrame()

            (host.querySelectorAll(".t-x").item(0) as HTMLElement).click()
            awaitFrame()

            queue.messages.map { it.text } shouldBe listOf("second")
            host.querySelectorAll(".toast").length shouldBe 1
        }

        test("dismissing one of two identical toasts leaves the other") {
            // The reason a toast is keyed by id rather than by its text. `message` is a
            // per-subtype constant, so two live toasts saying the SAME thing is the ordinary
            // case, not a corner — and dismissing by text would silently take both.
            //
            // Written after a sabotage pass found the earlier version of this spec could not
            // tell the two implementations apart: it used two different strings, so text and id
            // picked the same toast and swapping them changed nothing.
            val queue = ToastQueue()
            val host = mount { ToastHost(queue) }
            queue.show("Could not reach the server.", ToastTone.Failure)
            queue.show("Something else failed.", ToastTone.Failure)
            val second = queue.show("Could not reach the server.", ToastTone.Failure)
            awaitFrame()

            queue.dismiss(second)
            awaitFrame()

            queue.messages.map { it.text } shouldBe
                listOf("Could not reach the server.", "Something else failed.")
            host.querySelectorAll(".toast").length shouldBe 2
        }

        test("dismissing an id that has already gone is not an error") {
            // Expiry and a click race by construction — the timer can fire between the mouse
            // going down and the handler running.
            val queue = ToastQueue()
            val id = queue.show("gone", ToastTone.Failure)

            queue.dismiss(id)
            queue.dismiss(id)

            queue.messages shouldBe emptyList()
        }

        test("an error's own words are what the toast says") {
            // `message` is written to be shown. Nothing here reinterprets it, and nothing
            // substring-matches it — it is a constant, so a match would be redundant or wrong.
            TransportError.NetworkUnavailable().toastText() shouldBe
                TransportError.NetworkUnavailable().message
            InternalError().toastText() shouldBe InternalError().message
        }

        test("a rate limit says how long, because its constant cannot") {
            // The one subtype whose body-level message has to omit something the reader needs:
            // the wait is per-instance and the constant is not.
            val text = AuthError.RateLimited(retryAfterSeconds = 42).toastText()

            text shouldContain "42s"
            text shouldBe "Too many attempts. Try again in 42s."
        }
    })
