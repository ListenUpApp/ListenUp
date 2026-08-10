package com.calypsan.listenup.web.features.auth

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.presentation.auth.PendingApprovalUiState
import com.calypsan.listenup.web.design.WebAppSurface
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

class PendingApprovalPanelTest :
    FunSpec({

        test("waiting names the email the request was made with") {
            val host =
                mount {
                    PendingApprovalPanel(
                        state = PendingApprovalUiState.Waiting,
                        email = "ada@example.com",
                        onCheckStatus = {},
                        onCancel = {},
                        onAcknowledge = {},
                    )
                }

            host.textContent.orEmpty() shouldContain "ada@example.com"
        }

        test("approval offers the way in, not another wait") {
            var acknowledged = 0
            val host =
                mount {
                    PendingApprovalPanel(
                        state = PendingApprovalUiState.Approved,
                        email = "ada@example.com",
                        onCheckStatus = {},
                        onCancel = {},
                        onAcknowledge = { acknowledged++ },
                    )
                }

            (host.querySelector(".btn") as HTMLElement).click()

            acknowledged shouldBe 1
        }

        test("denial shows the reason the server gave") {
            val host =
                mount {
                    PendingApprovalPanel(
                        state = PendingApprovalUiState.Denied("Not accepting new listeners."),
                        email = "ada@example.com",
                        onCheckStatus = {},
                        onCancel = {},
                        onAcknowledge = {},
                    )
                }

            (host.querySelector(".auth-err") as HTMLElement)
                .textContent
                .orEmpty() shouldContain "Not accepting new listeners"
        }

        test("waiting offers a manual re-check, because the stream can drop") {
            // Never Stranded: the approval watch is a socket, and a socket can die silently. A
            // manual check is the fallback that keeps the user from staring at a dead page.
            var checks = 0
            val host =
                mount {
                    PendingApprovalPanel(
                        state = PendingApprovalUiState.Waiting,
                        email = "ada@example.com",
                        onCheckStatus = { checks++ },
                        onCancel = {},
                        onAcknowledge = {},
                    )
                }

            (host.querySelector(".btn-ghost") as HTMLElement).click()

            checks shouldBe 1
        }
    })
