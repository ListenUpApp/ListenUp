package com.calypsan.listenup.web.features.hardcover

import com.calypsan.listenup.api.dto.hardcover.HardcoverBrokenReason
import com.calypsan.listenup.api.dto.hardcover.HardcoverLinkFailure
import com.calypsan.listenup.client.presentation.settings.HardcoverSettingsUiState
import com.calypsan.listenup.client.util.formatDateLong
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList

/** 26 September 2026, midday UTC — far enough from midnight that no timezone moves the day. */
private const val SINCE_MS = 1_790_424_000_000L

private val LINKING =
    HardcoverSettingsUiState.Linking(
        userCode = "ABCD-1234",
        verificationUri = "https://hardcover.app/link",
        verificationUriComplete = "https://hardcover.app/link?code=ABCD-1234",
        expiresAt = 0L,
    )

private val CONNECTED = HardcoverSettingsUiState.Connected(username = "simon", since = SINCE_MS, isDisconnecting = false)

private fun broken(
    reason: HardcoverBrokenReason = HardcoverBrokenReason.REVOKED,
    username: String? = "simon",
) = HardcoverSettingsUiState.Broken(reason = reason, username = username, isStarting = false)

private fun HTMLElement.button(label: String): HTMLButtonElement =
    querySelectorAll("button")
        .asList()
        .map { it as HTMLButtonElement }
        .first { it.textContent.orEmpty().trim() == label }

private fun HTMLElement.dialogButton(index: Int): HTMLElement =
    querySelectorAll("dialog.dlg .dlg-actions button").item(index) as HTMLElement

/**
 * The Hardcover page, one phase at a time — and the three things web does differently from the apps:
 * the approval page is a real link rather than a hoped-for popup, copying can fail without
 * stranding anyone, and nothing disconnects without being asked twice.
 */
class HardcoverPageTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        fun mount(
            state: HardcoverSettingsUiState,
            onConnect: () -> Unit = {},
            onDisconnect: () -> Unit = {},
            onOpenSettings: () -> Unit = {},
            copyText: (String, (Boolean) -> Unit) -> Unit = { _, onResult -> onResult(true) },
        ): HTMLElement =
            mounts.mount {
                HardcoverPage(
                    state = state,
                    onConnect = onConnect,
                    onDisconnect = onDisconnect,
                    onOpenSettings = onOpenSettings,
                    copyText = copyText,
                )
            }

        test("every phase has a real top-level heading") {
            listOf(
                HardcoverSettingsUiState.Loading,
                HardcoverSettingsUiState.NotOffered,
                HardcoverSettingsUiState.NotConnected(lastFailure = null, isStarting = false),
                LINKING,
                CONNECTED,
                broken(),
            ).forEach { state ->
                val host = mount(state)
                host.querySelector("h1").shouldNotBeNull()
            }
        }

        test("the breadcrumb leads back to Account, in Settings") {
            var opened = 0
            val host = mount(CONNECTED, onOpenSettings = { opened++ })

            val crumbs = host.querySelector(".crumb").shouldNotBeNull()
            crumbs.textContent.orEmpty() shouldContain "Account"
            crumbs.textContent.orEmpty() shouldContain "Hardcover"
            val account =
                crumbs
                    .querySelectorAll("a")
                    .asList()
                    .map { it as HTMLElement }
                    .first { it.textContent == "Account" }
            account.click()

            opened shouldBe 1
        }

        test("loading says what it is waiting for") {
            val host = mount(HardcoverSettingsUiState.Loading)

            host.textContent.orEmpty() shouldContain "Checking your Hardcover connection…"
        }

        test("a server without Hardcover says so") {
            val host = mount(HardcoverSettingsUiState.NotOffered)

            host.textContent.orEmpty() shouldContain "Hardcover isn't set up on this server."
            host.querySelectorAll("button").length shouldBe 0
        }

        test("not connected explains what is shared, and connects") {
            var connected = 0
            val host =
                mount(HardcoverSettingsUiState.NotConnected(lastFailure = null, isStarting = false), onConnect = { connected++ })

            host.querySelector("h1")?.textContent shouldBe "Share what you finish"
            val text = host.textContent.orEmpty()
            text shouldContain "Connect your Hardcover account and ListenUp will mark what you listen to as read there."
            text shouldContain "A book you finish is marked as read on Hardcover."
            text shouldContain "You sign in on Hardcover itself. ListenUp never sees your password."

            host.button("Connect Hardcover").click()

            connected shouldBe 1
        }

        test("a connect already in flight cannot be pressed again") {
            val host = mount(HardcoverSettingsUiState.NotConnected(lastFailure = null, isStarting = true))

            host.button("Connect Hardcover").disabled shouldBe true
        }

        test("why the last attempt ended is said, in its own words") {
            mapOf(
                HardcoverLinkFailure.DENIED to "You declined on Hardcover. Connect again whenever you like.",
                HardcoverLinkFailure.EXPIRED to "The code expired before it was approved. Try again.",
                HardcoverLinkFailure.UNREACHABLE to "Hardcover couldn't be reached. Try again.",
            ).forEach { (failure, words) ->
                val host = mount(HardcoverSettingsUiState.NotConnected(lastFailure = failure, isStarting = false))
                host.textContent.orEmpty() shouldContain words
            }
        }

        test("linking shows the code and where to enter it, without the scheme") {
            val host = mount(LINKING)

            host.querySelector("h1")?.textContent shouldBe "Approve ListenUp on Hardcover"
            val text = host.textContent.orEmpty()
            text shouldContain "We opened the page for you. On another device, go to hardcover.app/link and enter this code."
            text shouldContain "Your code"
            host.querySelector(".hc-code")?.textContent shouldBe "ABCD-1234"
            text shouldContain "Open hardcover.app/link"
            text shouldContain "Enter the code and approve ListenUp"
            text shouldContain "This page updates on its own"
            text shouldNotContain "https://"
        }

        test("linking always offers a real new-tab link to the pre-filled page") {
            // ⛔ The automatic open follows an awaited RPC, so a popup blocker may eat it. This link
            // is what a person can always click — and a click on a real anchor is never blocked.
            val host = mount(LINKING)

            val anchor =
                host
                    .querySelectorAll("a")
                    .asList()
                    .map { it as HTMLAnchorElement }
                    .first { it.textContent.orEmpty().contains("Open Hardcover in a new tab") }
            anchor.getAttribute("href") shouldBe "https://hardcover.app/link?code=ABCD-1234"
            anchor.getAttribute("target") shouldBe "_blank"
            anchor.getAttribute("rel") shouldBe "noopener noreferrer"
        }

        test("the waiting line is announced politely") {
            val host = mount(LINKING)

            val status = host.querySelector("[aria-live=polite]").shouldNotBeNull()
            status.textContent.orEmpty() shouldContain "Waiting for you to approve"
            status.textContent.orEmpty() shouldContain "This screen updates by itself. The code works for about 15 minutes."
        }

        test("copying the code says Copied") {
            var copied: String? = null
            val host = mount(LINKING, copyText = { text, onResult -> copied = text.also { onResult(true) } })

            host.button("Copy code").click()
            awaitFrame()

            copied shouldBe "ABCD-1234"
            host.querySelector(".hc-copy")?.textContent.orEmpty() shouldContain "Copied"
        }

        test("a clipboard that refuses leaves the code on screen and claims nothing") {
            val host = mount(LINKING, copyText = { _, onResult -> onResult(false) })

            host.button("Copy code").click()
            awaitFrame()

            host.querySelector(".hc-code")?.textContent shouldBe "ABCD-1234"
            host.querySelector(".hc-copy")?.textContent.orEmpty() shouldNotContain "Copied"
        }

        test("cancelling a pending sign-in needs no confirmation — nothing is lost") {
            var disconnected = 0
            val host = mount(LINKING, onDisconnect = { disconnected++ })

            host.button("Cancel").click()
            awaitFrame()

            disconnected shouldBe 1
            host.querySelector("dialog.dlg") shouldBe null
        }

        test("connected shows who, since when, and what is shared") {
            val host = mount(CONNECTED)

            val text = host.textContent.orEmpty()
            text shouldContain "Connected"
            text shouldContain "simon"
            text shouldContain "Since ${formatDateLong(SINCE_MS)}"
            host
                .querySelectorAll("h2")
                .asList()
                .map { it.textContent }
                .contains("What ListenUp shares") shouldBe true
            text shouldContain "Books you finish, marked as read"
            // The sync row is a placeholder for a later PR, and must not ship as a promise.
            text shouldNotContain "Sync now"
        }

        test("disconnecting asks first, and only the confirm disconnects") {
            var disconnected = 0
            val host = mount(CONNECTED, onDisconnect = { disconnected++ })

            host.button("Disconnect").click()
            awaitFrame()

            disconnected shouldBe 0
            val dialog = host.querySelector("dialog.dlg").shouldNotBeNull()
            dialog.textContent.orEmpty() shouldContain "Disconnect Hardcover?"
            dialog.textContent.orEmpty() shouldContain
                "ListenUp will stop updating your Hardcover account. Nothing already on Hardcover is removed."

            host.dialogButton(1).click()

            disconnected shouldBe 1
        }

        test("cancelling the confirmation keeps the connection") {
            var disconnected = 0
            val host = mount(CONNECTED, onDisconnect = { disconnected++ })

            host.button("Disconnect").click()
            awaitFrame()
            host.dialogButton(0).click()
            awaitFrame()

            disconnected shouldBe 0
            host.querySelector("dialog.dlg") shouldBe null
        }

        test("a disconnect in flight cannot be started twice") {
            val host = mount(CONNECTED.copy(isDisconnecting = true))

            host.button("Disconnect").disabled shouldBe true
        }

        test("broken names the reason and who it was, and reconnects") {
            var connected = 0
            val host = mount(broken(), onConnect = { connected++ })

            host.querySelector("h2")?.textContent shouldBe "Reconnect to keep sharing"
            val text = host.textContent.orEmpty()
            text shouldContain "Was connected as simon."
            text shouldContain
                "Hardcover no longer accepts ListenUp's access — it may have been removed on Hardcover. Reconnect to continue."

            host.button("Reconnect").click()

            connected shouldBe 1
        }

        test("each broken reason has its own words") {
            mapOf(
                HardcoverBrokenReason.CANNOT_DECRYPT to
                    "This server can't read its saved Hardcover sign-in, which usually means it was restored from a backup.",
                HardcoverBrokenReason.MISSING_SCOPE to "ListenUp is missing a permission it needs on Hardcover.",
            ).forEach { (reason, words) ->
                val host = mount(broken(reason = reason))
                host.textContent.orEmpty() shouldContain words
            }
        }

        test("a broken connection whose owner is unknown does not invent one") {
            val host = mount(broken(username = null))

            host.textContent.orEmpty() shouldNotContain "Was connected as"
        }

        test("disconnecting a broken connection also asks first") {
            var disconnected = 0
            val host = mount(broken(), onDisconnect = { disconnected++ })

            host.button("Disconnect").click()
            awaitFrame()

            disconnected shouldBe 0
            host.querySelector("dialog.dlg").shouldNotBeNull()
            host.dialogButton(1).click()

            disconnected shouldBe 1
        }
    })
