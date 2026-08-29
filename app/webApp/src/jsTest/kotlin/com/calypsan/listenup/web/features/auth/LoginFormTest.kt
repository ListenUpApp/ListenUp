package com.calypsan.listenup.web.features.auth

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.presentation.auth.LoginErrorType
import com.calypsan.listenup.client.presentation.auth.LoginField
import com.calypsan.listenup.client.presentation.auth.LoginUiState
import com.calypsan.listenup.web.design.WebAppSurface
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
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

class LoginFormTest :
    FunSpec({

        test("sign-in offers a way back in when the password is gone") {
            // The one client you reach by URL, on a machine that may have no app installed, had no
            // recovery path at all: web admins could approve reset requests nobody on web could
            // raise. Never Stranded is not optional on the sign-in screen.
            var forgotClicks = 0
            val host =
                mount {
                    LoginForm(
                        state = LoginUiState.Idle,
                        openRegistration = false,
                        onSubmit = { _, _ -> },
                        onRegister = {},
                        onForgotPassword = { forgotClicks++ },
                        onClaimInvite = {},
                    )
                }

            (host.querySelector(".auth-aside .lnk") as HTMLElement).click()

            forgotClicks shouldBe 1
        }

        test("recovery is offered even on a server that takes no new accounts") {
            // The two are unrelated: a closed server still has existing users who forget.
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

            host.querySelector(".auth-aside .lnk") shouldNotBe null
        }

        test("an invite is offered even on a server that takes no new accounts") {
            // That is exactly when it matters most: with open registration off, an invite is the
            // ONLY way in, so hiding the link there strands an invited reader holding a code.
            var claims = 0
            val host =
                mount {
                    LoginForm(
                        state = LoginUiState.Idle,
                        openRegistration = false,
                        onSubmit = { _, _ -> },
                        onRegister = {},
                        onForgotPassword = {},
                        onClaimInvite = { claims++ },
                    )
                }

            host.linkNamed("Redeem it").click()

            claims shouldBe 1
        }

        test("submitting forwards exactly what was typed") {
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
            (host.querySelector(".btn") as HTMLButtonElement).click()

            submitted shouldBe ("ada@example.com" to "hunter2")
        }

        test("the submit button is disabled while a login is in flight") {
            // Two submits from one impatient double-click is a real bug on a slow LAN, and the
            // ViewModel has no guard of its own.
            val host =
                mount {
                    LoginForm(
                        state = LoginUiState.Loading,
                        openRegistration = false,
                        onSubmit = { _, _ -> },
                        onRegister = {},
                        onForgotPassword = {},
                        onClaimInvite = {},
                    )
                }

            (host.querySelector(".btn") as HTMLButtonElement).disabled shouldBe true
        }

        test("bad credentials say so without naming which half was wrong") {
            val host =
                mount {
                    LoginForm(
                        state = LoginUiState.Error(LoginErrorType.InvalidCredentials),
                        openRegistration = false,
                        onSubmit = { _, _ -> },
                        onRegister = {},
                        onForgotPassword = {},
                        onClaimInvite = {},
                    )
                }

            (host.querySelector(".auth-err") as HTMLElement).textContent.orEmpty() shouldContain "Email or password"
        }

        test("a field validation error marks that field and no other") {
            val host =
                mount {
                    LoginForm(
                        state = LoginUiState.Error(LoginErrorType.ValidationError(LoginField.PASSWORD)),
                        openRegistration = false,
                        onSubmit = { _, _ -> },
                        onRegister = {},
                        onForgotPassword = {},
                        onClaimInvite = {},
                    )
                }

            host.querySelectorAll(".f-box.err").length shouldBe 1
            val errored = host.querySelector(".f-box.err") as HTMLElement
            (errored.querySelector(".f-input") as HTMLInputElement).id shouldBe "auth-password"
        }

        test("the create-account link appears only when the server allows registration") {
            val open =
                mount {
                    LoginForm(
                        state = LoginUiState.Idle,
                        openRegistration = true,
                        onSubmit = { _, _ -> },
                        onRegister = {},
                        onForgotPassword = {},
                        onClaimInvite = {},
                    )
                }
            val closed =
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

            // Named rather than counted. The footer also carries the invite redeem link, and
            // `.auth-aside` carries recovery — both offered whatever the server says about new
            // accounts, so a link COUNT answers a different question than this test is asking.
            open.textContent.orEmpty() shouldContain "Create account"
            closed.textContent.orEmpty() shouldNotContain "Create account"
        }

        test("the create-account link reports the intent") {
            var registerClicks = 0
            val host =
                mount {
                    LoginForm(
                        state = LoginUiState.Idle,
                        openRegistration = true,
                        onSubmit = { _, _ -> },
                        onRegister = { registerClicks++ },
                        onForgotPassword = {},
                        onClaimInvite = {},
                    )
                }

            host.linkNamed("Create account").click()

            registerClicks shouldBe 1
        }
    })

/**
 * The link with exactly this text. Sign-in carries three now, so a positional selector picks the
 * wrong one silently.
 */
private fun HTMLElement.linkNamed(text: String): HTMLElement {
    val links = querySelectorAll(".lnk")
    for (i in 0 until links.length) {
        val link = links.item(i) as? HTMLElement ?: continue
        if (link.textContent?.trim() == text) return link
    }
    error("no link named \"$text\"")
}
