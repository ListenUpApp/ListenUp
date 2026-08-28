package com.calypsan.listenup.web.features.devices

import androidx.compose.runtime.Composable
import com.calypsan.listenup.api.error.AuthError.SessionExpired
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.client.presentation.settings.DeviceRow
import com.calypsan.listenup.client.presentation.settings.DevicesUiState
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.browser.document
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.HTMLElement

/** Two days back, so the relative time is stable to read. */
private const val NOW_MS = 1_000_000_000L

private const val TWO_DAYS_MS = 172_800_000L

private val mountedHosts = mutableListOf<HTMLElement>()

private fun mount(content: @Composable () -> Unit): HTMLElement {
    val host = document.createElement("div") as HTMLElement
    document.body!!.appendChild(host)
    mountedHosts += host
    renderComposable(root = host) { content() }
    return host
}

private fun device(
    id: String,
    name: String,
    isCurrent: Boolean = false,
    lastUsedAt: Long = NOW_MS - TWO_DAYS_MS,
) = DeviceRow(
    sessionId = id,
    displayName = name,
    secondary = "iOS 17.2 · ListenUp 1.0.0",
    lastUsedAt = lastUsedAt,
    isCurrent = isCurrent,
)

private fun ready(
    vararg devices: DeviceRow,
    signingOut: Set<String> = emptySet(),
) = DevicesUiState.Ready(devices = devices.toList(), signingOut = signingOut)

/**
 * Devices, and mostly the one rule that matters: you cannot revoke yourself by accident.
 */
class DevicesPageTest :
    FunSpec({

        afterSpec {
            mountedHosts.forEach { it.remove() }
            mountedHosts.clear()
        }

        test("this device is shown apart, and carries no sign-out button of its own") {
            // Revoking your own session is signing yourself out — a different intention from ending
            // one on a laptop you no longer have. A button doing the first while looking like the
            // second is the trap this separation exists to avoid.
            val host =
                mount { DevicesPage(ready(device("s1", "This Mac", isCurrent = true)), NOW_MS, {}, {}, {}) }

            host.textContent.orEmpty() shouldContain "This device"
            // The only buttons on the page are the deliberate one at the bottom.
            host.querySelectorAll(".dev-card button").length shouldBe 0
        }

        test("other devices each get their own sign-out, naming the device") {
            val host =
                mount {
                    DevicesPage(
                        ready(device("s1", "This Mac", isCurrent = true), device("s2", "Simon's iPhone")),
                        NOW_MS,
                        {},
                        {},
                        {},
                    )
                }

            val buttons = host.querySelectorAll(".dev-card button")
            buttons.length shouldBe 1
            (buttons.item(0) as HTMLElement).getAttribute("aria-label") shouldBe "Sign out Simon's iPhone"
        }

        test("signing out a device reports that device, not the first in the list") {
            var revoked: String? = null
            val host =
                mount {
                    DevicesPage(
                        ready(device("s1", "This Mac", isCurrent = true), device("s2", "iPhone"), device("s3", "iPad")),
                        NOW_MS,
                        { revoked = it },
                        {},
                        {},
                    )
                }

            (host.querySelectorAll(".dev-card button").item(1) as HTMLElement).click()

            revoked shouldBe "s3"
        }

        test("a device already signing out cannot be asked twice") {
            val host =
                mount {
                    DevicesPage(
                        ready(device("s1", "iPhone"), signingOut = setOf("s1")),
                        NOW_MS,
                        {},
                        {},
                        {},
                    )
                }

            val button = host.querySelectorAll(".dev-card button").item(0) as HTMLElement
            button.getAttribute("disabled") shouldBe ""
            button.textContent shouldContain "Signing out"
        }

        test("the current device says it is active now rather than guessing at a timestamp") {
            val host =
                mount {
                    DevicesPage(
                        ready(device("s1", "This Mac", isCurrent = true, lastUsedAt = 0L)),
                        NOW_MS,
                        {},
                        {},
                        {},
                    )
                }

            host.textContent.orEmpty() shouldContain "Active now"
        }

        test("another device says how long ago it was used") {
            val host = mount { DevicesPage(ready(device("s2", "iPhone")), NOW_MS, {}, {}, {}) }

            host.textContent.orEmpty() shouldContain "2 days ago"
        }

        test("signing out everywhere asks first, and says this device is included") {
            // The consequence someone actually needs to know before pressing it.
            var signedOut = 0
            val host =
                mount {
                    DevicesPage(ready(device("s1", "This Mac", isCurrent = true)), NOW_MS, {}, { signedOut++ }, {})
                }

            (host.querySelector(".dev-danger button") as HTMLElement).click()
            awaitFrame()

            signedOut shouldBe 0
            val dialog = host.querySelector("dialog.dlg").shouldNotBeNull()
            dialog.textContent.orEmpty() shouldContain "including this one"
        }

        test("confirming is what signs everything out") {
            var signedOut = 0
            val host =
                mount {
                    DevicesPage(ready(device("s1", "This Mac", isCurrent = true)), NOW_MS, {}, { signedOut++ }, {})
                }

            (host.querySelector(".dev-danger button") as HTMLElement).click()
            awaitFrame()
            (host.querySelectorAll("dialog.dlg .dlg-actions button").item(1) as HTMLElement).click()

            signedOut shouldBe 1
        }

        test("cancelling leaves every session alone") {
            var signedOut = 0
            val host =
                mount {
                    DevicesPage(ready(device("s1", "This Mac", isCurrent = true)), NOW_MS, {}, { signedOut++ }, {})
                }

            (host.querySelector(".dev-danger button") as HTMLElement).click()
            awaitFrame()
            (host.querySelectorAll("dialog.dlg .dlg-actions button").item(0) as HTMLElement).click()
            awaitFrame()

            signedOut shouldBe 0
            host.querySelector("dialog.dlg") shouldBe null
        }

        test("signing out of nowhere else says so rather than showing an empty heading") {
            val host =
                mount { DevicesPage(ready(device("s1", "This Mac", isCurrent = true)), NOW_MS, {}, {}, {}) }

            host.textContent.orEmpty() shouldContain "You are not signed in anywhere else."
        }

        test("a failed load offers a way to try again when trying again could work") {
            var retried = 0
            val host =
                mount {
                    DevicesPage(
                        DevicesUiState.Error(TransportError.NetworkUnavailable(debugInfo = "offline")),
                        NOW_MS,
                        {},
                        {},
                        { retried++ },
                    )
                }

            (host.querySelector("button") as HTMLElement).click()

            retried shouldBe 1
        }

        test("a failure the reader has to act on is not offered a retry that cannot work") {
            // This test used to assert the opposite, with an InternalError — which is isRetryable
            // = false — so it was pinning the dead button in place. The case that exposed it: an
            // expired session reads "Please sign in again." directly above a Try again that can
            // only fail again, which is a way out only in appearance.
            val host =
                mount {
                    DevicesPage(DevicesUiState.Error(SessionExpired()), NOW_MS, {}, {}, {})
                }

            host.textContent.orEmpty() shouldContain "Please sign in again."
            host.querySelectorAll("button").length shouldBe 0
        }
    })
