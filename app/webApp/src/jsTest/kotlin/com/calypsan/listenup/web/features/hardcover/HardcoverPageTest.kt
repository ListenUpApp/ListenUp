package com.calypsan.listenup.web.features.hardcover

import com.calypsan.listenup.api.dto.hardcover.HardcoverBrokenReason
import com.calypsan.listenup.api.dto.hardcover.HardcoverHistory
import com.calypsan.listenup.api.dto.hardcover.HardcoverLinkFailure
import com.calypsan.listenup.api.dto.hardcover.HardcoverShareMode
import com.calypsan.listenup.api.dto.hardcover.HardcoverSyncProblem
import com.calypsan.listenup.client.presentation.hardcover.HardcoverBookToMatch
import com.calypsan.listenup.client.presentation.hardcover.HardcoverSyncStatus
import com.calypsan.listenup.client.presentation.settings.HardcoverSettingsUiState
import com.calypsan.listenup.client.util.formatDateLong
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.browser.document
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.Node
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

/** The page's clock, three days after [SINCE_MS]: the sync line measures against this, not the real one. */
private const val NOW_MS = SINCE_MS + 3 * 24 * 60 * 60 * 1000L

private val HAIL_MARY = HardcoverBookToMatch("b1", "Project Hail Mary", "Andy Weir", null, null)
private val PIRANESI = HardcoverBookToMatch("b2", "Piranesi", "Susanna Clarke", null, "h2")

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
            onSyncNow: () -> Unit = {},
            onSetShareMode: (HardcoverShareMode) -> Unit = {},
            onSendHistory: () -> Unit = {},
            onDismissHistory: () -> Unit = {},
            onFindMatch: (String) -> Unit = {},
            onOpenKeptOff: () -> Unit = {},
            copyText: (String, (Boolean) -> Unit) -> Unit = { _, onResult -> onResult(true) },
        ): HTMLElement =
            mounts.mount {
                HardcoverPage(
                    state = state,
                    onConnect = onConnect,
                    onDisconnect = onDisconnect,
                    onSyncNow = onSyncNow,
                    onSetShareMode = onSetShareMode,
                    onSendHistory = onSendHistory,
                    onDismissHistory = onDismissHistory,
                    onFindMatch = onFindMatch,
                    onOpenKeptOff = onOpenKeptOff,
                    onOpenSettings = onOpenSettings,
                    nowMs = NOW_MS,
                    copyText = copyText,
                )
            }

        test("with books kept off, a quiet row in Sync says how many and opens the list") {
            var keptOffOpens = 0
            val host = mount(CONNECTED.copy(keptOffBookCount = 3), onOpenKeptOff = { keptOffOpens++ })
            awaitFrame()

            val row = host.querySelector(".hc-kept-row") as HTMLElement
            row.textContent.orEmpty() shouldContain "Kept off Hardcover"
            row.textContent.orEmpty() shouldContain "3 books"
            row.click()
            keptOffOpens shouldBe 1
        }

        test("one book kept off reads in the singular, and none shows no row") {
            val one = mount(CONNECTED.copy(keptOffBookCount = 1))
            awaitFrame()
            (one.querySelector(".hc-kept-row") as HTMLElement).textContent.orEmpty() shouldContain "1 book"
            (one.querySelector(".hc-kept-row") as HTMLElement).textContent.orEmpty() shouldNotContain "1 books"

            val none = mount(CONNECTED.copy(keptOffBookCount = 0))
            awaitFrame()
            none.querySelector(".hc-kept-row") shouldBe null
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

        test("a disconnect in flight cannot be started twice, and keeps the focus the dialog hands back") {
            val host = mount(CONNECTED.copy(isDisconnecting = true))

            val press = host.button("Disconnect")
            press.disabled shouldBe false
            press.getAttribute("aria-disabled") shouldBe "true"
            press.click()
            awaitFrame()
            host.querySelector("dialog.dlg") shouldBe null
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

        test("a sync this minute reads just now, and Sync now asks for one") {
            var syncs = 0
            val host = mount(CONNECTED.copy(lastSyncedAt = NOW_MS - 20_000L), onSyncNow = { syncs++ })

            host.querySelector(".hc-sync-t")!!.textContent shouldBe "Last synced just now"
            host.button("Sync now").click()

            syncs shouldBe 1
        }

        test("an older sync says how long ago") {
            val host = mount(CONNECTED.copy(lastSyncedAt = NOW_MS - 3 * 60 * 1000L))

            host.querySelector(".hc-sync-t")!!.textContent.orEmpty() shouldStartWith "Last synced "
            host.querySelector(".hc-sync-t")!!.textContent shouldNotBe "Last synced just now"
        }

        test("never synced says so") {
            mount(CONNECTED).querySelector(".hc-sync-t")!!.textContent shouldBe "Not synced yet"
        }

        test("syncing is announced, and Sync now cannot be pressed a second time") {
            var syncs = 0
            val host = mount(CONNECTED.copy(sync = HardcoverSyncStatus.Syncing), onSyncNow = { syncs++ })

            val line = host.querySelector(".hc-sync-t")!!
            line.textContent shouldBe "Syncing…"
            line.getAttribute("role") shouldBe "status"
            val press = host.button("Sync now")
            // Unavailable, but never `disabled`: that would drop the focus of the press that started it.
            press.disabled shouldBe false
            press.getAttribute("aria-disabled") shouldBe "true"
            press.getAttribute("aria-busy") shouldBe "true"
            press.click()
            syncs shouldBe 0
        }

        test("a stalled push is said in plain words, on its own card, with Try again") {
            var syncs = 0
            val host =
                mount(
                    CONNECTED.copy(sync = HardcoverSyncStatus.Problem(HardcoverSyncProblem.PUSH_STALLED)),
                    onSyncNow = { syncs++ },
                )

            val card = host.querySelector(".hc-problem")!!
            card.getAttribute("role") shouldBe "status"
            card.textContent.orEmpty() shouldContain
                "Some of your listening hasn't reached Hardcover yet. ListenUp keeps trying."
            host.button("Try again").click()
            syncs shouldBe 1
        }

        test("a stalled pull has its own words") {
            mount(CONNECTED.copy(sync = HardcoverSyncStatus.Problem(HardcoverSyncProblem.PULL_STALLED)))
                .querySelector(".hc-problem")!!
                .textContent
                .orEmpty() shouldContain "ListenUp can't read your Hardcover shelf right now. It keeps trying."
        }

        test("a Sync now that failed is the toast's to say: the sync line stays as it was") {
            val host =
                mount(
                    CONNECTED.copy(
                        lastSyncedAt = NOW_MS - 20_000L,
                        sync = HardcoverSyncStatus.Problem(HardcoverSyncProblem.SYNC_NOW_FAILED),
                    ),
                )

            host.querySelector(".hc-problem") shouldBe null
            host.querySelector(".hc-sync-t")!!.textContent shouldBe "Last synced just now"
            host.button("Sync now").hasAttribute("aria-disabled") shouldBe false
        }

        test("the books that need a match are listed and counted, and each opens Find on Hardcover") {
            val opened = mutableListOf<String>()
            val host =
                mount(
                    CONNECTED.copy(booksToMatch = listOf(HAIL_MARY, PIRANESI), isMatchListKnown = true),
                    onFindMatch = { opened += it },
                )

            val section = host.panel("Needs a match")
            section.textContent.orEmpty() shouldContain
                "ListenUp couldn't tell which Hardcover book these are. Pick each one and it starts syncing."
            section.querySelector(".hc-count")!!.textContent shouldBe "2"
            val rows = section.querySelectorAll(".hc-match-row").asList().map { it as HTMLElement }
            rows.map { it.querySelector(".hc-match-title")!!.textContent } shouldBe listOf("Project Hail Mary", "Piranesi")
            rows[1].querySelector(".hc-match-by")!!.textContent shouldBe "Susanna Clarke"
            rows[1].button("Find on Hardcover: Piranesi").click()
            opened shouldBe listOf("b2")
        }

        test("each Find on Hardcover is named for the book it finds, after its visible words, so they can be told apart") {
            val host = mount(CONNECTED.copy(booksToMatch = listOf(HAIL_MARY, PIRANESI), isMatchListKnown = true))

            val press = host.panel("Needs a match").querySelectorAll("button").item(1) as HTMLElement
            press.textContent shouldBe "Find on Hardcover: Piranesi"
            press.querySelector(".sr-only")!!.textContent shouldBe ": Piranesi"
        }

        test("a known empty list says every started book is matched") {
            val host = mount(CONNECTED.copy(isMatchListKnown = true))

            host.panel("Needs a match").textContent.orEmpty() shouldContain "Every book you've started is matched"
        }

        test("a list that is not known yet draws no section at all") {
            mount(CONNECTED).textContent.orEmpty() shouldNotContain "Needs a match"
        }

        test("what is shared names all four things, and what comes back never counts as listening") {
            val shares = mount(CONNECTED).panel("What ListenUp shares")
            val text = shares.textContent.orEmpty()

            listOf(
                "Books you start, as Currently reading",
                "How far you've listened",
                "Books you finish, marked as read",
            ).forEach { text shouldContain it }
            shares.querySelector("h3")!!.textContent shouldBe "What comes back"
            text shouldContain "Books you've read elsewhere appear in Readers with a Hardcover label."
            text shouldContain "They never count as listening."
        }

        test("Update Hardcover offers As I listen and Only when I finish, as a labelled group, the current one pressed") {
            val group = mount(CONNECTED).panel("What ListenUp shares").querySelector(".hc-share-mode .seg") as HTMLElement

            group.getAttribute("role") shouldBe "group"
            group.getAttribute("aria-label") shouldBe "Update Hardcover"
            val options = group.querySelectorAll("button").asList().map { it as HTMLButtonElement }
            options.map { it.textContent.orEmpty().trim() } shouldBe listOf("As I listen", "Only when I finish")
            options.map { it.getAttribute("aria-pressed") } shouldBe listOf("true", "false")
        }

        test("Only when I finish names only finishing, with its dates, and says nothing is shared while listening") {
            val shares = mount(CONNECTED.copy(shareMode = HardcoverShareMode.FINISHED_ONLY)).panel("What ListenUp shares")
            val text = shares.textContent.orEmpty()

            text shouldContain "Only books you finish, marked as read, with when you started and finished"
            text shouldContain "Nothing is shared while you're still listening"
            text shouldNotContain "How far you've listened"
            text shouldNotContain "Books you start, as Currently reading"
            shares.button("Only when I finish").getAttribute("aria-pressed") shouldBe "true"
            text shouldContain "They never count as listening."
        }

        test("choosing a mode asks for it") {
            val chosen = mutableListOf<HardcoverShareMode>()
            val host = mount(CONNECTED, onSetShareMode = { chosen += it })

            host.button("Only when I finish").click()

            chosen shouldBe listOf(HardcoverShareMode.FINISHED_ONLY)
        }

        test("while a choice saves, neither option can be pressed, and focus stays where it was") {
            val chosen = mutableListOf<HardcoverShareMode>()
            val host =
                mount(
                    CONNECTED.copy(shareMode = HardcoverShareMode.FINISHED_ONLY, isSavingShareMode = true),
                    onSetShareMode = { chosen += it },
                )
            val options = host.querySelectorAll(".hc-share-mode .seg button").asList().map { it as HTMLButtonElement }

            options.map { it.getAttribute("aria-disabled") } shouldBe listOf("true", "true")
            // aria-disabled rather than `disabled`: a disabled button drops the focus a keyboard press put on it.
            options.forEach { it.disabled shouldBe false }
            host.button("As I listen").click()

            chosen shouldBe emptyList()
        }

        test("what comes back also brings the Want to Read list onto the To Read shelf") {
            val shares = mount(CONNECTED).panel("What ListenUp shares")
            val comesBack = shares.querySelectorAll("ul").asList().last() as HTMLElement

            comesBack.querySelectorAll("li").length shouldBe 2
            comesBack.textContent.orEmpty() shouldContain "Your Want to Read list, on your To Read shelf"
        }

        test("the earlier-books offer sits between who you are and Sync, and Send and Not now each ask once") {
            var sends = 0
            var dismissals = 0
            val host =
                mount(
                    CONNECTED.copy(history = HardcoverHistory.Offer(74)),
                    onSendHistory = { sends++ },
                    onDismissHistory = { dismissals++ },
                )
            val card = host.querySelector(".hc-history") as HTMLElement
            val who = host.querySelector(".hc-who") as HTMLElement
            val sync = host.querySelector(".hc-sync") as HTMLElement

            who.isBefore(card) shouldBe true
            card.isBefore(sync) shouldBe true
            card.querySelector("h2")?.textContent shouldBe "Send your earlier listening?"
            card.textContent.orEmpty() shouldContain
                "You finished 74 books in ListenUp before connecting. Send them to Hardcover as read, with when you started and finished."
            card.button("Send 74 books").click()
            card.button("Not now").click()

            sends shouldBe 1
            dismissals shouldBe 1
        }

        test("one book is said in the singular") {
            val card = mount(CONNECTED.copy(history = HardcoverHistory.Offer(1))).querySelector(".hc-history") as HTMLElement

            card.button("Send 1 book").shouldNotBeNull()
            card.textContent.orEmpty() shouldContain "You finished 1 book in ListenUp before connecting. Send it to Hardcover"
        }

        test("sending is a labelled progress bar read as 23 of 74, with no buttons, and says it keeps going") {
            val card = mount(CONNECTED.copy(history = HardcoverHistory.Sending(23, 74))).querySelector(".hc-history") as HTMLElement
            val bar = card.querySelector("[role=progressbar]") as HTMLElement

            bar.getAttribute("aria-label") shouldBe "Sending earlier books"
            bar.getAttribute("aria-valuetext") shouldBe "23 of 74"
            card.textContent.orEmpty() shouldContain "Sending 23 of 74 books…"
            card.textContent.orEmpty() shouldContain "You can leave this screen — it keeps going. Your new listening is sent first."
            card.querySelectorAll("button").length shouldBe 0
        }

        test("done says what was sent; 4 need a match is a button to the Needs a match panel; Dismiss asks once") {
            var dismissals = 0
            val host =
                mount(
                    CONNECTED.copy(history = HardcoverHistory.Done(70, 4), booksToMatch = listOf(HAIL_MARY), isMatchListKnown = true),
                    onDismissHistory = { dismissals++ },
                )
            val card = host.querySelector(".hc-history") as HTMLElement

            card.querySelector("[role=status]")?.textContent.orEmpty() shouldContain "Sent 70 books to Hardcover"
            host
                .querySelector("#hc-needs-match")
                .shouldNotBeNull()
                .textContent
                .orEmpty() shouldContain "Needs a match"
            card.button("4 need a match").click()
            (card.querySelector("button[aria-label='Dismiss']") as HTMLButtonElement).click()

            dismissals shouldBe 1
        }

        test("done with every book matched says all were sent, and nothing waits for a match") {
            val card = mount(CONNECTED.copy(history = HardcoverHistory.Done(74, 0))).querySelector(".hc-history") as HTMLElement

            card.textContent.orEmpty() shouldContain "Sent all 74 books to Hardcover"
            card.textContent.orEmpty() shouldNotContain "need a match"
        }

        test("done with nothing sent yet says the books need a match first, never Sent 0") {
            val card = mount(CONNECTED.copy(history = HardcoverHistory.Done(0, 4))).querySelector(".hc-history") as HTMLElement

            card.textContent.orEmpty() shouldContain "4 books need a match before they can be sent"
            card.textContent.orEmpty() shouldNotContain "Sent 0"
            (mount(CONNECTED.copy(history = HardcoverHistory.Done(0, 1))).querySelector(".hc-history") as HTMLElement)
                .textContent
                .orEmpty() shouldContain "1 book needs a match before it can be sent"
        }

        test("after Not now the quiet row in Sync sends in place, and no card shows") {
            var sends = 0
            val host = mount(CONNECTED.copy(history = HardcoverHistory.Available(74)), onSendHistory = { sends++ })
            val sync = host.panel("Sync")

            sync.textContent.orEmpty() shouldContain "Send earlier books"
            sync.textContent.orEmpty() shouldContain "74 finished before you connected"
            (sync.querySelector("button[aria-label='Send earlier books to Hardcover']") as HTMLButtonElement).click()

            sends shouldBe 1
            host.querySelector(".hc-history") shouldBe null
        }

        test("with no history there is no card and no row") {
            val host = mount(CONNECTED)

            host.querySelector(".hc-history") shouldBe null
            host.textContent.orEmpty() shouldNotContain "Send earlier books"
        }
    })

/** The panel whose heading reads [title]. */
private fun HTMLElement.panel(title: String): HTMLElement =
    querySelectorAll("section")
        .asList()
        .map { it as HTMLElement }
        // The heading's own words — a counted panel says its count after them, for a screen reader only.
        .first { it.querySelector("h2")?.firstChild?.textContent == title }

/** Whether [this] comes before [other] in document order. */
private fun HTMLElement.isBefore(other: HTMLElement): Boolean =
    compareDocumentPosition(other).toInt() and Node.DOCUMENT_POSITION_FOLLOWING.toInt() != 0
