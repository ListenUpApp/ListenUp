package com.calypsan.listenup.web.features.auth

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.presentation.auth.LoginUiState
import com.calypsan.listenup.client.presentation.auth.RegisterUiState
import com.calypsan.listenup.client.presentation.auth.SetupUiState
import com.calypsan.listenup.web.design.WebAppSurface
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.browser.document
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLFormElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import org.w3c.dom.EventInit

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

/**
 * Pressing Enter in a field submits the form it is in.
 *
 * ⛔ These do NOT synthesise a keypress. A browser's *implicit submission* — Enter in a text field
 * activating the form's submit button — is a user-agent behaviour driven by real user input; a
 * dispatched `KeyboardEvent` does not trigger it, so a spec built on one would pass against markup
 * where Enter does nothing. That is exactly the bug being fixed here, so a spec that could not
 * detect it would be worse than none.
 *
 * Instead each spec pins the two structural facts the browser needs in order to do it natively:
 * the fields live inside a real `<form>`, and the button is a `submit` button. `requestSubmit()` is
 * the platform's own programmatic equivalent of pressing Enter — it fires a cancellable `submit`
 * event through the same path — so it verifies the handler is actually wired, not merely present.
 */
class EnterSubmitsTest :
    FunSpec({

        fun formOf(host: HTMLElement): HTMLFormElement {
            val form = host.querySelector("form")
            form shouldNotBe null
            return form as HTMLFormElement
        }

        /**
         * The platform's own "the user pressed Enter" entry point. Not in Kotlin's DOM externs,
         * so it is reached dynamically; it fires a cancellable `submit` event down the same path
         * implicit submission uses, which a hand-dispatched Event would only imitate.
         */
        fun HTMLFormElement.submitLikeEnter() {
            asDynamic().requestSubmit()
        }

        fun submitButtonOf(host: HTMLElement): HTMLButtonElement =
            host.querySelector("button[type=submit]") as? HTMLButtonElement
                ?: error("no submit button — Enter cannot submit a form without one")

        test("Enter in the login form signs in with what was typed") {
            var submitted: Pair<String, String>? = null
            val host =
                mount {
                    LoginForm(
                        state = LoginUiState.Idle,
                        openRegistration = false,
                        onSubmit = { email, password -> submitted = email to password },
                        onRegister = {},
                        onForgotPassword = {},
                        onClaimInvite = {},
                    )
                }

            host.typeInto("#auth-email", "ada@example.com")
            host.typeInto("#auth-password", "hunter2")
            formOf(host).submitLikeEnter()

            submitted shouldBe ("ada@example.com" to "hunter2")
        }

        test("the login form's submit never reloads the page") {
            // A <form> whose handler does not preventDefault navigates on submit — which in this
            // app means a full reload that drops the typed credentials and the whole Kotlin/JS
            // runtime with them. Worse than the button-only version it replaces.
            val host =
                mount {
                    LoginForm(
                        state = LoginUiState.Idle,
                        openRegistration = false,
                        onSubmit = { _, _ -> },
                        onRegister = {},
                        onForgotPassword = {},
                        onClaimInvite = {},
                    )
                }

            val event = Event("submit", EventInit(bubbles = true, cancelable = true))
            formOf(host).dispatchEvent(event)

            event.defaultPrevented shouldBe true
        }

        test("the login button submits the form rather than handling its own click") {
            val host =
                mount {
                    LoginForm(
                        state = LoginUiState.Idle,
                        openRegistration = false,
                        onSubmit = { _, _ -> },
                        onRegister = {},
                        onForgotPassword = {},
                        onClaimInvite = {},
                    )
                }

            submitButtonOf(host).type shouldBe "submit"
        }

        test("Enter in the register form creates the account") {
            var submitted: String? = null
            val host =
                mount {
                    RegisterForm(
                        state = RegisterUiState.Idle,
                        onSubmit = { email, _, _, _ -> submitted = email },
                        onBack = {},
                    )
                }

            host.typeInto("#auth-email", "grace@example.com")
            formOf(host).submitLikeEnter()

            submitted shouldBe "grace@example.com"
        }

        test("Enter in the first-run setup form creates the admin") {
            var submitted: String? = null
            val host =
                mount {
                    SetupForm(
                        state = SetupUiState.Idle,
                        onSubmit = { _, _, email, _, _ -> submitted = email },
                    )
                }

            host.typeInto("#auth-email", "root@example.com")
            formOf(host).submitLikeEnter()

            submitted shouldBe "root@example.com"
        }
        test("the login fields tell a password manager what they hold") {
            // A manager keys off autocomplete, not off placeholder text or field order. Without
            // these it often offers neither to fill nor to save — which is how someone ends up
            // locked out of their own server with no saved credential.
            val host =
                mount {
                    LoginForm(
                        state = LoginUiState.Idle,
                        openRegistration = false,
                        onSubmit = { _, _ -> },
                        onRegister = {},
                        onForgotPassword = {},
                        onClaimInvite = {},
                    )
                }

            (host.querySelector("#auth-email") as HTMLInputElement).getAttribute("autocomplete") shouldBe "username"
            (host.querySelector("#auth-password") as HTMLInputElement)
                .getAttribute("autocomplete") shouldBe "current-password"
        }

        test("account creation asks for a NEW password, not the saved one") {
            val host =
                mount {
                    RegisterForm(
                        state = RegisterUiState.Idle,
                        onSubmit = { _, _, _, _ -> },
                        onBack = {},
                    )
                }

            (host.querySelector("#auth-password") as HTMLInputElement)
                .getAttribute("autocomplete") shouldBe "new-password"
        }
    })
