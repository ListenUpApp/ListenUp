package com.calypsan.listenup.web.features.auth

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.presentation.auth.ForgotPasswordUiState
import com.calypsan.listenup.web.awaitFrame
import com.calypsan.listenup.web.design.WebAppSurface
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.browser.document
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.EventInit
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event

private fun mount(content: @Composable () -> Unit): HTMLElement {
    val host = document.createElement("div") as HTMLElement
    document.body!!.appendChild(host)
    renderComposable(root = host) { WebAppSurface { content() } }
    return host
}

private fun HTMLElement.typeInto(
    selector: String,
    text: String,
) {
    val input = querySelector(selector) as HTMLInputElement
    input.value = text
    input.dispatchEvent(Event("input", EventInit(bubbles = true)))
}

private fun panel(
    state: ForgotPasswordUiState,
    onRequestReset: (String) -> Unit = {},
    onCompleteReset: (String, String) -> Unit = { _, _ -> },
    onCheckStatus: () -> Unit = {},
    onRetryRequest: () -> Unit = {},
    onBackToSignIn: () -> Unit = {},
): HTMLElement =
    mount {
        ForgotPasswordPanel(
            state = state,
            onRequestReset = onRequestReset,
            onCompleteReset = onCompleteReset,
            onCheckStatus = onCheckStatus,
            onRetryRequest = onRetryRequest,
            onBackToSignIn = onBackToSignIn,
        )
    }

class ForgotPasswordPanelTest :
    FunSpec({

        test("the first step asks for the address and opens a request with it") {
            var requestedFor: String? = null
            val host = panel(ForgotPasswordUiState.EnterEmail, onRequestReset = { requestedFor = it })

            host.typeInto("#auth-reset-email", "ada@example.com")
            (host.querySelector("button[type=submit]") as HTMLButtonElement).click()

            requestedFor shouldBe "ada@example.com"
        }

        test("the first step says an admin approves this, not an inbox") {
            // A self-hosted server has no mail relay. Copy that says "check your email" would send
            // someone to watch an inbox nothing will ever arrive in — the flow would look broken
            // while working perfectly.
            val text = panel(ForgotPasswordUiState.EnterEmail).textContent.orEmpty()

            text shouldContain "admin"
            text.lowercase() shouldNotContain "check your email"
        }

        test("a request in flight cannot be fired twice") {
            val host = panel(ForgotPasswordUiState.Submitting)

            (host.querySelector("button[type=submit]") as HTMLButtonElement).disabled shouldBe true
        }

        test("waiting offers a manual re-check, because the stream can drop") {
            // Never Stranded: the status watch is a socket and a socket can die silently. The
            // ViewModel polls too, but someone staring at an unchanging page must be able to ask.
            var checks = 0
            val host = panel(ForgotPasswordUiState.AwaitingApproval("t1"), onCheckStatus = { checks++ })

            (host.querySelector(".btn-ghost") as HTMLElement).click()

            checks shouldBe 1
        }

        test("the code step submits the code and the new password together") {
            var completed: Pair<String, String>? = null
            val host =
                panel(
                    ForgotPasswordUiState.EnterCode("t1"),
                    onCompleteReset = { code, password -> completed = code to password },
                )

            host.typeInto("#auth-reset-code", "884213")
            host.typeInto("#auth-reset-password", "a-new-one")
            host.typeInto("#auth-reset-confirm", "a-new-one")
            (host.querySelector("button[type=submit]") as HTMLButtonElement).click()

            completed shouldBe ("884213" to "a-new-one")
        }

        test("a mismatched confirmation never reaches the server") {
            // The ticket has a finite attempt budget and `completeReset` takes one password, so
            // the confirm field has no other reader. Spending an attempt on a typo the user can
            // see for themselves is the failure this prevents.
            var completions = 0
            val host =
                panel(
                    ForgotPasswordUiState.EnterCode("t1"),
                    onCompleteReset = { _, _ -> completions++ },
                )

            host.typeInto("#auth-reset-code", "884213")
            host.typeInto("#auth-reset-password", "a-new-one")
            host.typeInto("#auth-reset-confirm", "a-new-onf")
            (host.querySelector("button[type=submit]") as HTMLButtonElement).click()
            awaitFrame()

            completions shouldBe 0
            (host.querySelector(".auth-err") as HTMLElement).textContent.orEmpty() shouldContain "do not match"
        }

        test("a wrong code keeps the form and says how many tries are left") {
            val host =
                panel(ForgotPasswordUiState.EnterCode("t1", attemptsRemaining = 2, error = "That code is wrong."))

            host.querySelector("#auth-reset-code") shouldNotBe null
            val error = (host.querySelector(".auth-err") as HTMLElement).textContent.orEmpty()
            error shouldContain "That code is wrong."
            error shouldContain "2 tries left."
        }

        test("the last try is singular, because '1 tries left' reads as a bug") {
            val host =
                panel(ForgotPasswordUiState.EnterCode("t1", attemptsRemaining = 1, error = "That code is wrong."))

            (host.querySelector(".auth-err") as HTMLElement).textContent.orEmpty() shouldContain "1 try left."
        }

        test("correcting the code drops the message about the old one") {
            // A field asserting something false about its own contents is worse than no validation
            // at all — the same rule LoginForm follows.
            val host =
                panel(ForgotPasswordUiState.EnterCode("t1", attemptsRemaining = 2, error = "That code is wrong."))

            host.typeInto("#auth-reset-code", "884214")
            awaitFrame()

            host.querySelector(".auth-err") shouldBe null
        }

        test("a decline offers to ask again rather than starting over") {
            // The requester already gave their address; a decline is usually a misunderstanding,
            // and the ViewModel can re-open the same request with one call.
            var retries = 0
            val host = panel(ForgotPasswordUiState.Denied, onRetryRequest = { retries++ })

            (host.querySelector(".btn") as HTMLElement).click()

            retries shouldBe 1
        }

        test("completion sends you to sign in and offers no way back into a dead flow") {
            var backs = 0
            val host = panel(ForgotPasswordUiState.Complete, onBackToSignIn = { backs++ })

            (host.querySelector(".btn") as HTMLElement).click()

            backs shouldBe 1
            host.querySelectorAll(".auth-alt").length shouldBe 0
        }

        test("a terminal failure shows the server's own words") {
            val host = panel(ForgotPasswordUiState.Error("Your reset request expired. Please start again."))

            (host.querySelector(".auth-err") as HTMLElement)
                .textContent
                .orEmpty() shouldContain "Your reset request expired."
        }

        test("every step keeps a way back to sign in, except the one that is already done") {
            listOf(
                ForgotPasswordUiState.EnterEmail,
                ForgotPasswordUiState.AwaitingApproval("t1"),
                ForgotPasswordUiState.EnterCode("t1"),
                ForgotPasswordUiState.Denied,
                ForgotPasswordUiState.Error("nope"),
            ).forEach { state ->
                var backs = 0
                val host = panel(state, onBackToSignIn = { backs++ })

                (host.querySelector(".auth-alt .lnk") as HTMLElement).click()

                backs shouldBe 1
            }
        }
    })
