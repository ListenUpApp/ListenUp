package com.calypsan.listenup.web.features.auth

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.presentation.auth.RegisterUiState
import com.calypsan.listenup.web.design.WebAppSurface
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.browser.document
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
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

class RegisterFormTest :
    FunSpec({

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
