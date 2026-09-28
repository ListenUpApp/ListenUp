package com.calypsan.listenup.web.features.auth

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.presentation.invite.ClaimInviteUiState
import com.calypsan.listenup.client.presentation.auth.ForgotPasswordUiState
import com.calypsan.listenup.client.presentation.auth.LoginUiState
import com.calypsan.listenup.client.presentation.auth.PendingApprovalUiState
import com.calypsan.listenup.client.presentation.auth.RegisterUiState
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.design.WebAppSurface
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList

/**
 * Every way between the auth screens is a real button.
 *
 * ⛔ They were `<span onClick>` — no role, no tab stop, no key handler — so a keyboard user could not
 * reach "Forgot your password?", "Create account", "Redeem it" or "Back to sign in". Sign-in is the
 * one screen with no other route past it, so an unreachable link there strands someone completely.
 */
class AuthLinksAreButtonsTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        fun mount(content: @Composable () -> Unit): HTMLElement = mounts.mount { WebAppSurface { content() } }

        /** Every `.lnk` in [host] that is not a `<button type="button">`, by its text. */
        fun notButtons(host: HTMLElement): List<String> {
            val links = host.querySelectorAll(".lnk").asList().filterIsInstance<HTMLElement>()
            links.size shouldBeGreaterThan 0
            return links
                .filterNot { it.tagName == "BUTTON" && it.getAttribute("type") == "button" }
                .map { "${it.tagName}: ${it.textContent}" }
        }

        test("sign-in's three links are buttons") {
            val host =
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

            notButtons(host).shouldBeEmpty()
        }

        test("registration's way back is a button") {
            val host = mount { RegisterForm(state = RegisterUiState.Idle, onSubmit = { _, _, _, _ -> }, onBack = {}) }

            notButtons(host).shouldBeEmpty()
        }

        test("password recovery's way back is a button") {
            val host =
                mount {
                    ForgotPasswordPanel(
                        state = ForgotPasswordUiState.EnterEmail,
                        onRequestReset = {},
                        onCompleteReset = { _, _ -> },
                        onCheckStatus = {},
                        onRetryRequest = {},
                        onBackToSignIn = {},
                    )
                }

            notButtons(host).shouldBeEmpty()
        }

        test("the invite panel's way back is a button") {
            val host =
                mount {
                    ClaimInvitePanel(
                        state = ClaimInviteUiState.Idle,
                        onCodeEntered = {},
                        onClaim = { _, _, _ -> },
                        onBackToSignIn = {},
                    )
                }

            notButtons(host).shouldBeEmpty()
        }

        test("a pending request's cancel, and a denied one's way back, are buttons") {
            val waiting =
                mount {
                    PendingApprovalPanel(
                        state = PendingApprovalUiState.Waiting,
                        email = "ada@example.com",
                        onCheckStatus = {},
                        onCancel = {},
                        onAcknowledge = {},
                    )
                }
            val denied =
                mount {
                    PendingApprovalPanel(
                        state = PendingApprovalUiState.Denied("No."),
                        email = "ada@example.com",
                        onCheckStatus = {},
                        onCancel = {},
                        onAcknowledge = {},
                    )
                }

            notButtons(waiting).shouldBeEmpty()
            notButtons(denied).shouldBeEmpty()
        }
    })
