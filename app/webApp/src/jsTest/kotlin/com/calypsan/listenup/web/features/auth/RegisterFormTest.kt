package com.calypsan.listenup.web.features.auth

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.presentation.auth.RegisterUiState
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.design.WebAppSurface
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import org.w3c.dom.EventInit
import com.calypsan.listenup.web.awaitFrame
import io.kotest.matchers.string.shouldNotContain

private fun HTMLElement.typeInto(
    selector: String,
    text: String,
) {
    val input = querySelector(selector) as HTMLInputElement
    input.value = text
    input.dispatchEvent(Event("input", EventInit(bubbles = true)))
}

class RegisterFormTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        fun mount(content: @Composable () -> Unit): HTMLElement = mounts.mount { WebAppSurface { content() } }

        test("a mismatched confirmation cannot be submitted, and says why") {
            // ⛔ Registration was the one account-creating form on web without this. A typo made an
            // account awaiting admin approval with a password the registrant could not reproduce —
            // recoverable only through the admin-mediated reset flow. Setup and Claim-invite both
            // had a confirm field; Android guards the same way, in the form rather than the VM,
            // because `RegisterViewModel` takes no confirm value.
            var submitted = 0
            val host =
                mount {
                    RegisterForm(
                        state = RegisterUiState.Idle,
                        onSubmit = { _, _, _, _ -> submitted++ },
                        onBack = {},
                    )
                }

            host.typeInto("#auth-first", "Ada")
            host.typeInto("#auth-last", "Lovelace")
            host.typeInto("#auth-email", "ada@example.com")
            host.typeInto("#auth-password", "hunter2")
            host.typeInto("#auth-register-confirm", "hunter3")
            awaitFrame()

            val button = host.querySelector(".btn") as HTMLButtonElement
            button.hasAttribute("disabled") shouldBe true
            host.textContent.orEmpty() shouldContain "Passwords don't match"

            button.click()
            awaitFrame()
            submitted shouldBe 0
        }

        test("a password with no confirmation yet cannot be submitted either") {
            // Silent, not accusatory: nothing is wrong until the two disagree, so an untouched
            // confirm field disables the button without an error message.
            var submitted = 0
            val host =
                mount {
                    RegisterForm(
                        state = RegisterUiState.Idle,
                        onSubmit = { _, _, _, _ -> submitted++ },
                        onBack = {},
                    )
                }

            host.typeInto("#auth-email", "ada@example.com")
            host.typeInto("#auth-password", "hunter2")
            awaitFrame()

            (host.querySelector(".btn") as HTMLButtonElement).hasAttribute("disabled") shouldBe true
            host.textContent.orEmpty() shouldNotContain "Passwords don't match"
            submitted shouldBe 0
        }

        test("submitting forwards the values in the ViewModel's order") {
            // onRegisterSubmit is (email, password, first, last) — the reverse grouping of
            // onSetupSubmit. Pin it.
            var submitted: List<String>? = null
            val host =
                mount {
                    RegisterForm(
                        state = RegisterUiState.Idle,
                        onSubmit = { email, password, first, last ->
                            submitted = listOf(email, password, first, last)
                        },
                        onBack = {},
                    )
                }

            host.typeInto("#auth-first", "Ada")
            host.typeInto("#auth-last", "Lovelace")
            host.typeInto("#auth-email", "ada@example.com")
            host.typeInto("#auth-password", "hunter2")
            // The confirm field is part of the form's contract now: registration refuses to submit
            // without a match, the way Setup and Claim-invite already did.
            host.typeInto("#auth-register-confirm", "hunter2")
            // The submit button is gated on the two matching now, and that gate is recomposed —
            // clicking in the same frame as the last keystroke reaches a still-disabled button.
            awaitFrame()
            (host.querySelector(".btn") as HTMLButtonElement).click()

            submitted shouldBe listOf("ada@example.com", "hunter2", "Ada", "Lovelace")
        }

        test("a failure is shown verbatim") {
            // RegisterUiState.Error carries a raw String rather than a semantic type, unlike its
            // siblings. Rendering it as-is is deliberate — inventing copy here would hide what the
            // server actually said. See the spec's "known wart".
            val host =
                mount {
                    RegisterForm(
                        state = RegisterUiState.Error("That email is already registered."),
                        onSubmit = { _, _, _, _ -> },
                        onBack = {},
                    )
                }

            (host.querySelector(".auth-err") as HTMLElement)
                .textContent
                .orEmpty() shouldContain "already registered"
        }

        test("going back reports the intent") {
            var backs = 0
            val host =
                mount {
                    RegisterForm(state = RegisterUiState.Idle, onSubmit = { _, _, _, _ -> }, onBack = { backs++ })
                }

            (host.querySelector(".lnk") as HTMLElement).click()

            backs shouldBe 1
        }
    })
