package com.calypsan.listenup.web.features.auth

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.presentation.auth.SetupErrorType
import com.calypsan.listenup.client.presentation.auth.SetupField
import com.calypsan.listenup.client.presentation.auth.SetupUiState
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

class SetupFormTest :
    FunSpec({

        test("submitting forwards all five values in the ViewModel's order") {
            // onSetupSubmit is (first, last, email, password, confirm) while onRegisterSubmit is
            // (email, password, first, last). Getting these the wrong way round compiles fine and
            // creates an admin called "hunter2", so pin the order.
            var submitted: List<String>? = null
            val host =
                mount {
                    SetupForm(
                        state = SetupUiState.Idle,
                        onSubmit = { first, last, email, password, confirm ->
                            submitted = listOf(first, last, email, password, confirm)
                        },
                    )
                }

            host.typeInto("#auth-first", "Ada")
            host.typeInto("#auth-last", "Lovelace")
            host.typeInto("#auth-email", "ada@example.com")
            host.typeInto("#auth-password", "hunter2")
            host.typeInto("#auth-confirm", "hunter2")
            (host.querySelector(".btn") as HTMLButtonElement).click()

            submitted shouldBe listOf("Ada", "Lovelace", "ada@example.com", "hunter2", "hunter2")
        }

        test("the submit button is disabled while setup is in flight") {
            val host = mount { SetupForm(state = SetupUiState.Loading, onSubmit = { _, _, _, _, _ -> }) }

            (host.querySelector(".btn") as HTMLButtonElement).disabled shouldBe true
        }

        test("a mismatched confirmation marks the confirm field") {
            val host =
                mount {
                    SetupForm(
                        state = SetupUiState.Error(SetupErrorType.ValidationError(SetupField.PASSWORD_CONFIRM)),
                        onSubmit = { _, _, _, _, _ -> },
                    )
                }

            host.querySelectorAll(".f-box.err").length shouldBe 1
            val errored = host.querySelector(".f-box.err") as HTMLElement
            (errored.querySelector(".f-input") as HTMLInputElement).id shouldBe "auth-confirm"
        }

        test("an already-configured server says so plainly") {
            val host =
                mount {
                    SetupForm(state = SetupUiState.Error(SetupErrorType.AlreadyConfigured), onSubmit = { _, _, _, _, _ -> })
                }

            (host.querySelector(".auth-err") as HTMLElement).textContent.orEmpty() shouldContain "already set up"
        }
    })
