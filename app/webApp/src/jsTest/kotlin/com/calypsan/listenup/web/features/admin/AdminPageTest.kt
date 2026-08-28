package com.calypsan.listenup.web.features.admin

import androidx.compose.runtime.Composable
import com.calypsan.listenup.api.dto.auth.PasswordResetRequest
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.client.domain.model.AdminUserInfo
import com.calypsan.listenup.client.domain.model.InviteInfo
import com.calypsan.listenup.client.presentation.admin.AdminUiState
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.browser.document
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.events.Event

private val mountedHosts = mutableListOf<HTMLElement>()

private fun mount(content: @Composable () -> Unit): HTMLElement {
    val host = document.createElement("div") as HTMLElement
    document.body!!.appendChild(host)
    mountedHosts += host
    renderComposable(root = host) { content() }
    return host
}

private fun user(
    id: String,
    name: String,
    isRoot: Boolean = false,
) = AdminUserInfo(
    id = id,
    email = "$id@example.com",
    displayName = name,
    firstName = null,
    lastName = null,
    isRoot = isRoot,
    role = "MEMBER",
    status = "ACTIVE",
    createdAt = "2026-01-01",
)

private fun invite(id: String) =
    InviteInfo(
        id = id,
        code = "ABC",
        name = "Alan",
        email = "alan@example.com",
        role = "MEMBER",
        expiresAt = "2026-02-01",
        claimedAt = null,
        url = "https://listenup.example/i/ABC",
        createdAt = "2026-01-01",
    )

private fun resetRequest(id: String) =
    PasswordResetRequest(
        id = id,
        userId = UserId("u2"),
        displayName = "Ada",
        email = "ada@example.com",
        requestedAt = 0L,
        expiresAt = 0L,
    )

@Composable
@Suppress("LongParameterList")
private fun page(
    state: AdminUiState = AdminUiState.Ready(),
    onApproveUser: (String) -> Unit = {},
    onDenyUser: (String) -> Unit = {},
    onDeleteUser: (String) -> Unit = {},
    onRevokeInvite: (String) -> Unit = {},
    onDecidePasswordReset: (String, Boolean) -> Unit = { _, _ -> },
    onDismissResetCode: () -> Unit = {},
    onSetRegistrationPolicy: (RegistrationPolicy) -> Unit = {},
) {
    AdminPage(
        state = state,
        nowMs = 0L,
        onApproveUser = onApproveUser,
        onDenyUser = onDenyUser,
        onDeleteUser = onDeleteUser,
        onRevokeInvite = onRevokeInvite,
        onDecidePasswordReset = onDecidePasswordReset,
        onDismissResetCode = onDismissResetCode,
        onSetRegistrationPolicy = onSetRegistrationPolicy,
        onClearError = {},
        onRetry = {},
    )
}

/**
 * Admin's contract. Every action here lands on a real account on a real server, so the specs are
 * mostly about what has to be true before one fires.
 */
class AdminPageTest :
    FunSpec({

        afterSpec {
            mountedHosts.forEach { it.remove() }
            mountedHosts.clear()
        }

        test("the owner cannot be removed — the control is absent, not disabled") {
            // Removing the root account would leave nobody able to administer the server. Offering
            // it and refusing later would be a worse way to say so.
            val host =
                mount { page(AdminUiState.Ready(users = listOf(user("u1", "Simon", isRoot = true)))) }

            host.textContent.orEmpty() shouldContain "Owner"
            host.querySelectorAll(".adm-row-actions button").length shouldBe 0
        }

        test("removing a member asks first, and says what happens to their history") {
            var deleted: String? = null
            val host =
                mount {
                    page(
                        state = AdminUiState.Ready(users = listOf(user("u2", "Ada"))),
                        onDeleteUser = { deleted = it },
                    )
                }

            (host.querySelector(".adm-row-actions button") as HTMLElement).click()
            awaitFrame()

            deleted shouldBe null
            val dialog = host.querySelector("dialog.dlg").shouldNotBeNull()
            dialog.textContent.orEmpty() shouldContain "Ada"
            dialog.textContent.orEmpty() shouldContain "listening history"
        }

        test("confirming is what removes them") {
            var deleted: String? = null
            val host =
                mount {
                    page(
                        state = AdminUiState.Ready(users = listOf(user("u2", "Ada"))),
                        onDeleteUser = { deleted = it },
                    )
                }

            (host.querySelector(".adm-row-actions button") as HTMLElement).click()
            awaitFrame()
            (host.querySelectorAll("dialog.dlg .dlg-actions button").item(1) as HTMLElement).click()

            deleted shouldBe "u2"
        }

        test("revoking an invite is worded more lightly than removing a person") {
            // Different weights should not share a prompt: nobody has used the invite yet.
            val host = mount { page(AdminUiState.Ready(pendingInvites = listOf(invite("i1")))) }

            (host.querySelector(".adm-row-actions button") as HTMLElement).click()
            awaitFrame()

            val dialog = host.querySelector("dialog.dlg").shouldNotBeNull()
            dialog.textContent.orEmpty() shouldContain "another"
            dialog.textContent.orEmpty() shouldNotContain "listening history"
        }

        test("approving someone waiting does not ask — it is the friendly direction") {
            // A confirm on every action trains people to dismiss them. Approving is reversible by
            // removing, and it is the action an admin came here to take.
            var approved: String? = null
            val host =
                mount {
                    page(
                        state = AdminUiState.Ready(pendingUsers = listOf(user("u3", "Grace"))),
                        onApproveUser = { approved = it },
                    )
                }

            (host.querySelector(".adm-row-actions button") as HTMLElement).click()

            approved shouldBe "u3"
        }

        test("an action already in flight cannot be fired twice") {
            val host =
                mount {
                    page(
                        AdminUiState.Ready(
                            pendingUsers = listOf(user("u3", "Grace")),
                            approvingUserId = "u3",
                        ),
                    )
                }

            val approve = host.querySelector(".adm-row-actions button") as HTMLElement
            approve.getAttribute("disabled") shouldBe ""
            approve.textContent shouldContain "Approving"
        }

        test("a password reset can be approved or declined, and says which") {
            var decision: Pair<String, Boolean>? = null
            val host =
                mount {
                    page(
                        state = AdminUiState.Ready(pendingPasswordResets = listOf(resetRequest("r1"))),
                        onDecidePasswordReset = { id, approved -> decision = id to approved },
                    )
                }

            (host.querySelectorAll(".adm-row-actions button").item(1) as HTMLElement).click()

            decision shouldBe ("r1" to false)
        }

        test("a minted reset code is shown once, and says so") {
            // It is a credential. The copy has to make clear it will not come back, or someone
            // dismisses it expecting to find it again later.
            val host =
                mount {
                    page(
                        AdminUiState.Ready(
                            resetCodeToConvey = "MOON-42",
                            resetCodeRecipientName = "Ada",
                        ),
                    )
                }
            awaitFrame()

            val dialog = host.querySelector("dialog.dlg").shouldNotBeNull()
            dialog.textContent.orEmpty() shouldContain "MOON-42"
            dialog.textContent.orEmpty() shouldContain "Ada"
            dialog.textContent.orEmpty() shouldContain "shown once"
        }

        test("no reset code means no dialog holding the screen") {
            val host = mount { page(AdminUiState.Ready()) }

            host.querySelector("dialog.dlg") shouldBe null
        }

        test("the registration policy reports the choice, and reads as what it means") {
            var policy: RegistrationPolicy? = null
            val host =
                mount {
                    page(
                        state = AdminUiState.Ready(registrationPolicy = RegistrationPolicy.CLOSED),
                        onSetRegistrationPolicy = { policy = it },
                    )
                }

            host.textContent.orEmpty() shouldContain "Anyone can ask, you approve"
            val select = host.querySelector("select") as HTMLSelectElement
            select.value shouldBe "CLOSED"
            select.value = "OPEN"
            select.dispatchEvent(Event("change", eventInit()))

            policy shouldBe RegistrationPolicy.OPEN
        }

        test("a policy value this build does not know closes the door rather than opening it") {
            // The safe default for an unrecognised value is the restrictive one.
            policyOf("BANANA") shouldBe RegistrationPolicy.CLOSED
            policyOf("OPEN") shouldBe RegistrationPolicy.OPEN
        }

        test("a load that half-failed still shows what it got, above the error") {
            // There is no Error state — a failed load arrives as Ready carrying `error`. Replacing
            // the page with a blank error would throw away the half that did load.
            val host =
                mount {
                    page(AdminUiState.Ready(users = listOf(user("u2", "Ada")), error = "Invites unavailable"))
                }

            host.textContent.orEmpty() shouldContain "Invites unavailable"
            host.textContent.orEmpty() shouldContain "Ada"
        }

        test("sections with nothing in them are not announced") {
            // An admin with no pending anything should not read four empty headings.
            val host = mount { page(AdminUiState.Ready(users = listOf(user("u2", "Ada")))) }

            val text = host.textContent.orEmpty()
            text shouldNotContain "Waiting for you"
            text shouldNotContain "Open invites"
            text shouldNotContain "Password reset requests"
        }
    })

private fun eventInit(): dynamic {
    val init: dynamic = js("({})")
    init.bubbles = true
    return init
}
