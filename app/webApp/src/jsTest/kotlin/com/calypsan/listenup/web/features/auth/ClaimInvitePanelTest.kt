package com.calypsan.listenup.web.features.auth

import androidx.compose.runtime.Composable
import com.calypsan.listenup.api.dto.invite.InvitePreview
import com.calypsan.listenup.client.presentation.invite.ClaimInviteUiState
import com.calypsan.listenup.web.awaitFrame
import com.calypsan.listenup.web.design.WebAppSurface
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.core.spec.style.FunSpec
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

internal fun invitePreview(
    valid: Boolean = true,
    invalidReason: String? = null,
): InvitePreview =
    InvitePreview(
        displayName = "Ada Lovelace",
        email = "ada@example.com",
        invitedByName = "Simon",
        serverName = "The Attic",
        valid = valid,
        invalidReason = invalidReason,
    )

private fun panel(
    state: ClaimInviteUiState,
    onCodeEntered: (String) -> Unit = {},
    onClaim: (String, String, String) -> Unit = { _, _, _ -> },
    onBackToSignIn: () -> Unit = {},
): HTMLElement =
    mount {
        ClaimInvitePanel(
            state = state,
            onCodeEntered = onCodeEntered,
            onClaim = onClaim,
            onBackToSignIn = onBackToSignIn,
        )
    }

class ClaimInvitePanelTest :
    FunSpec({

        test("a typed code is looked up, trimmed") {
            // Codes get copied out of chat messages and emails, and a trailing space is the most
            // ordinary way for one to arrive. Sending it to the server unchanged turns a valid
            // invite into "no such code".
            var lookedUp: String? = null
            val host = panel(ClaimInviteUiState.Idle, onCodeEntered = { lookedUp = it })

            host.typeInto("#auth-invite-code", "  TREEHOUSE-42  ")
            awaitFrame()
            (host.querySelector("button[type=submit]") as HTMLButtonElement).click()

            lookedUp shouldBe "TREEHOUSE-42"
        }

        test("an empty code cannot be submitted") {
            // The ViewModel would ask the server about "" quite happily, and the reader would get
            // an error they caused by pressing a button that should not have been pressable.
            val host = panel(ClaimInviteUiState.Idle)

            (host.querySelector("button[type=submit]") as HTMLButtonElement).disabled shouldBe true
        }

        test("a lookup in flight cannot be fired twice") {
            val host = panel(ClaimInviteUiState.LookingUp)

            (host.querySelector("button[type=submit]") as HTMLButtonElement).disabled shouldBe true
        }

        test("the preview names who invited you and to what") {
            // An invite code is an opaque token. A page that asks for a password without saying
            // whose library it is for is indistinguishable from a phishing form.
            val text = panel(ClaimInviteUiState.Preview(invitePreview())).textContent.orEmpty()

            text shouldContain "Simon"
            text shouldContain "The Attic"
            text shouldContain "ada@example.com"
        }

        test("claiming forwards the password and both names") {
            var claimed: List<String>? = null
            val host =
                panel(
                    ClaimInviteUiState.Preview(invitePreview()),
                    onClaim = { password, first, last -> claimed = listOf(password, first, last) },
                )

            host.typeInto("#auth-invite-first", "Ada")
            host.typeInto("#auth-invite-last", "Lovelace")
            host.typeInto("#auth-invite-password", "a-good-one")
            host.typeInto("#auth-invite-confirm", "a-good-one")
            (host.querySelector("button[type=submit]") as HTMLButtonElement).click()

            claimed shouldBe listOf("a-good-one", "Ada", "Lovelace")
        }

        test("a mismatched confirmation never burns the code") {
            // An invite is one-time. Sending a password the reader mistyped, when the mistake is
            // visible on this side, risks spending it on an account they cannot get into.
            var claims = 0
            val host =
                panel(
                    ClaimInviteUiState.Preview(invitePreview()),
                    onClaim = { _, _, _ -> claims++ },
                )

            host.typeInto("#auth-invite-password", "a-good-one")
            host.typeInto("#auth-invite-confirm", "a-good-onf")
            (host.querySelector("button[type=submit]") as HTMLButtonElement).click()
            awaitFrame()

            claims shouldBe 0
            (host.querySelector(".auth-err") as HTMLElement).textContent.orEmpty() shouldContain "do not match"
        }

        test("an invite the server rejected shows the reason instead of a form") {
            // `valid = false` is an error wearing a preview's shape. Rendering the join form over
            // it invites four fields' worth of typing for a request that cannot succeed.
            val host =
                panel(
                    ClaimInviteUiState.Preview(
                        invitePreview(valid = false, invalidReason = "This invite was already used."),
                    ),
                )

            (host.querySelector(".auth-err") as HTMLElement)
                .textContent
                .orEmpty() shouldContain "This invite was already used."
            host.querySelector("#auth-invite-password") shouldBe null
        }

        test("a rejected invite with no reason still says something true") {
            val text = panel(ClaimInviteUiState.Preview(invitePreview(valid = false))).textContent.orEmpty()

            text shouldContain "no longer valid"
        }

        test("a claim in flight keeps the form, and what was typed into it") {
            // The ViewModel drops the preview on the way through Submitting. Swapping the form for
            // a spinner would lose the reader's typing the moment the claim came back a failure.
            val host = panel(ClaimInviteUiState.Submitting)

            host.querySelector("#auth-invite-password") shouldNotBe null
            (host.querySelector("button[type=submit]") as HTMLButtonElement).disabled shouldBe true
        }

        test("a failure shows the server's own words") {
            val host = panel(ClaimInviteUiState.Error("That code does not exist."))

            (host.querySelector(".auth-err") as HTMLElement)
                .textContent
                .orEmpty() shouldContain "That code does not exist."
        }

        test("every step keeps a way back to sign in") {
            listOf(
                ClaimInviteUiState.Idle,
                ClaimInviteUiState.LookingUp,
                ClaimInviteUiState.Preview(invitePreview()),
                ClaimInviteUiState.Preview(invitePreview(valid = false)),
                ClaimInviteUiState.Submitting,
                ClaimInviteUiState.Error("nope"),
            ).forEach { state ->
                var backs = 0
                val host = panel(state, onBackToSignIn = { backs++ })

                (host.querySelector(".auth-alt .lnk") as HTMLElement).click()

                backs shouldBe 1
            }
        }
    })
