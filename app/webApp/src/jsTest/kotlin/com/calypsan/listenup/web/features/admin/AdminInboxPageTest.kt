package com.calypsan.listenup.web.features.admin

import com.calypsan.listenup.api.dto.scan.ScanIssue
import com.calypsan.listenup.api.dto.scan.ScanIssueReason
import com.calypsan.listenup.client.domain.model.InboxBookItem
import com.calypsan.listenup.client.presentation.admin.AdminInboxUiState
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.browser.document
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList

private val hosts = mutableListOf<HTMLElement>()

private const val TWO_HOURS_MS = 2L * 3_600_000

internal fun inboxBook(
    id: String = "b1",
    title: String = "The Way of Kings",
    author: String? = "Brandon Sanderson",
    durationMs: Long = TWO_HOURS_MS,
): InboxBookItem = InboxBookItem(id = id, title = title, author = author, coverPath = null, durationMs = durationMs)

internal fun scanIssue(
    id: String = "i1",
    rootRelPath: String = "Sanderson/Elantris",
    reason: ScanIssueReason = ScanIssueReason.NO_RECOGNIZED_AUDIO,
    detail: String? = null,
): ScanIssue =
    ScanIssue(
        id = id,
        rootRelPath = rootRelPath,
        reason = reason,
        detail = detail,
        firstSeenAt = 0L,
        lastSeenAt = 0L,
    )

@Suppress("LongParameterList")
internal fun readyInbox(
    books: List<InboxBookItem> = listOf(inboxBook()),
    selectedBookIds: Set<String> = emptySet(),
    isReleasing: Boolean = false,
    lastReleasedCount: Int? = null,
    error: String? = null,
    scanIssues: List<ScanIssue> = emptyList(),
    bookIds: List<String>? = null,
): AdminInboxUiState.Ready =
    AdminInboxUiState.Ready(
        bookIds = bookIds ?: books.map { it.id },
        books = books,
        selectedBookIds = selectedBookIds,
        isReleasing = isReleasing,
        lastReleasedCount = lastReleasedCount,
        error = error,
        scanIssues = scanIssues,
    )

@Suppress("LongParameterList")
private fun page(
    state: AdminInboxUiState,
    onToggleBook: (String) -> Unit = {},
    onSelectAll: () -> Unit = {},
    onClearSelection: () -> Unit = {},
    onRelease: () -> Unit = {},
    onDismissIssue: (String) -> Unit = {},
    onClearError: () -> Unit = {},
    onClearReleaseResult: () -> Unit = {},
    onRetry: () -> Unit = {},
    onOpenAdmin: () -> Unit = {},
): HTMLElement {
    val host = document.createElement("div") as HTMLElement
    document.body!!.appendChild(host)
    hosts += host
    renderComposable(root = host) {
        AdminInboxPage(
            state = state,
            onToggleBook = onToggleBook,
            onSelectAll = onSelectAll,
            onClearSelection = onClearSelection,
            onRelease = onRelease,
            onDismissIssue = onDismissIssue,
            onClearError = onClearError,
            onClearReleaseResult = onClearReleaseResult,
            onRetry = onRetry,
            onOpenAdmin = onOpenAdmin,
        )
    }
    return host
}

private fun bookRows(host: HTMLElement) = host.querySelectorAll(".inbox-book").asList().filterIsInstance<HTMLElement>()

/** Cancel is first in the DOM so a hurried Return lands on the safe choice; confirm follows it. */
private fun dialogButton(
    host: HTMLElement,
    index: Int,
) = host.querySelectorAll("dialog.dlg .dlg-actions button").item(index) as HTMLElement

private fun button(
    host: HTMLElement,
    label: String,
): HTMLElement? =
    host
        .querySelectorAll("button")
        .asList()
        .filterIsInstance<HTMLElement>()
        .firstOrNull { it.textContent?.trim() == label }

/**
 * The admin inbox.
 *
 * What these pin: the two halves are genuinely independent — either can be empty while the other
 * has content, and only both being empty is "nothing to do"; a release asks first, because it makes
 * books visible to everyone and pressing something else does not undo that; and a scan issue says
 * what went wrong, where, and what to do about it, because a list of problems with no fixes is a
 * list the reader cannot act on.
 */
class AdminInboxPageTest :
    FunSpec({

        afterSpec {
            hosts.forEach { it.remove() }
            hosts.clear()
        }

        test("a held book shows what it is, who wrote it, and how long it runs") {
            val host = page(readyInbox(books = listOf(inboxBook(title = "Elantris", author = "Brandon Sanderson"))))

            val row = bookRows(host).single()
            row.textContent.orEmpty() shouldContain "Elantris"
            row.textContent.orEmpty() shouldContain "Brandon Sanderson"
            row.textContent.orEmpty() shouldContain "2h"
        }

        // A book whose author the scan could not work out is one of the reasons it is in here, so
        // the byline is absent rather than an empty line.
        test("a book with no author shows no byline") {
            val host = page(readyInbox(books = listOf(inboxBook(author = null))))

            host.querySelector(".inbox-book-by").shouldBeNull()
        }

        test("a row announces itself as a checkbox, and reports the press") {
            val toggled = mutableListOf<String>()
            val host = page(readyInbox(books = listOf(inboxBook(id = "b7"))), onToggleBook = { toggled += it })

            val row = bookRows(host).single()
            row.getAttribute("role") shouldBe "checkbox"
            row.getAttribute("aria-checked") shouldBe "false"
            row.click()
            awaitFrame()

            toggled shouldContainExactly listOf("b7")
        }

        test("a selected row says so, to the screen reader as well as the eye") {
            val host = page(readyInbox(books = listOf(inboxBook(id = "b7")), selectedBookIds = setOf("b7")))

            val row = bookRows(host).single()
            row.getAttribute("aria-checked") shouldBe "true"
            row.className shouldContain "is-sel"
        }

        test("Select all offers itself until everything is selected, then offers the way back") {
            val all = page(readyInbox(books = listOf(inboxBook(id = "b1")), selectedBookIds = setOf("b1")))
            val none = page(readyInbox(books = listOf(inboxBook(id = "b1"))))

            button(all, "Deselect all").shouldNotBeNull()
            button(none, "Select all").shouldNotBeNull()
        }

        // The label and the action are two separate expressions over `allSelected`, so proving the
        // label alone leaves a button that reads "Deselect all" and selects everything again.
        test("the one button does the thing it currently says") {
            var selectedAll = 0
            var cleared = 0
            val none =
                page(
                    readyInbox(books = listOf(inboxBook(id = "b1"))),
                    onSelectAll = { selectedAll++ },
                    onClearSelection = { cleared++ },
                )
            val all =
                page(
                    readyInbox(books = listOf(inboxBook(id = "b1")), selectedBookIds = setOf("b1")),
                    onSelectAll = { selectedAll++ },
                    onClearSelection = { cleared++ },
                )

            button(none, "Select all").shouldNotBeNull().click()
            button(all, "Deselect all").shouldNotBeNull().click()
            awaitFrame()

            selectedAll shouldBe 1
            cleared shouldBe 1
        }

        test("the bulk bar appears only once something is selected") {
            val empty = page(readyInbox())
            val selected = page(readyInbox(selectedBookIds = setOf("b1")))

            empty.querySelector(".bulk").shouldBeNull()
            selected.querySelector(".bulk").shouldNotBeNull()
        }

        // ⛔ Releasing makes books visible to everyone on the server, and pressing something else
        // does not undo it. Both native clients confirm; so does this.
        test("Release asks before it releases") {
            var released = 0
            val host = page(readyInbox(selectedBookIds = setOf("b1")), onRelease = { released++ })

            button(host, "Release 1").shouldNotBeNull().click()
            awaitFrame()

            host.querySelector("dialog.dlg").shouldNotBeNull()
            released shouldBe 0
        }

        test("confirming the release releases") {
            var released = 0
            val host = page(readyInbox(selectedBookIds = setOf("b1")), onRelease = { released++ })

            button(host, "Release 1").shouldNotBeNull().click()
            awaitFrame()
            dialogButton(host, 1).click()
            awaitFrame()

            released shouldBe 1
        }

        test("cancelling the confirmation releases nothing") {
            var released = 0
            val host = page(readyInbox(selectedBookIds = setOf("b1")), onRelease = { released++ })

            button(host, "Release 1").shouldNotBeNull().click()
            awaitFrame()
            dialogButton(host, 0).click()
            awaitFrame()

            released shouldBe 0
            host.querySelector("dialog.dlg[open]").shouldBeNull()
        }

        test("the confirmation says what releasing does") {
            val host = page(readyInbox(selectedBookIds = setOf("b1")))

            button(host, "Release 1").shouldNotBeNull().click()
            awaitFrame()

            host.querySelector("dialog.dlg")?.textContent.orEmpty() shouldContain "visible to everyone"
        }

        test("a release in flight says so and cannot be started again") {
            var released = 0
            val host =
                page(readyInbox(selectedBookIds = setOf("b1"), isReleasing = true), onRelease = { released++ })

            val action = button(host, "Releasing…").shouldNotBeNull()
            action.click()
            awaitFrame()

            host.querySelector("dialog.dlg").shouldBeNull()
            released shouldBe 0
        }

        test("a scan issue names the folder, what went wrong, and what to do about it") {
            val host =
                page(
                    readyInbox(
                        books = emptyList(),
                        scanIssues = listOf(scanIssue(rootRelPath = "Sanderson/Elantris")),
                    ),
                )

            val issue = host.querySelector(".inbox-issue").shouldNotBeNull()
            issue.textContent.orEmpty() shouldContain "No audio in this folder"
            issue.textContent.orEmpty() shouldContain "Sanderson/Elantris"
            issue.textContent.orEmpty() shouldContain "Add the audio files"
        }

        test("every reason the scanner can report gets its own headline and its own fix") {
            val host =
                page(
                    readyInbox(
                        books = emptyList(),
                        scanIssues =
                            ScanIssueReason.entries.mapIndexed { index, reason ->
                                scanIssue(id = "i$index", reason = reason)
                            },
                    ),
                )

            // Five reasons, five distinct headlines AND five distinct fixes — a `when` that fell
            // through to one shared string would still render five rows, and only this notices.
            // Both halves are checked: they are separate `when`s, and either can collapse alone.
            fun distinct(selector: String) =
                host
                    .querySelectorAll(selector)
                    .asList()
                    .mapNotNull { (it as HTMLElement).textContent }
                    .toSet()
                    .size

            distinct(".inbox-issue-what") shouldBe ScanIssueReason.entries.size
            distinct(".inbox-issue-fix") shouldBe ScanIssueReason.entries.size
        }

        test("the scan's own words appear only when it had any") {
            val without = page(readyInbox(books = emptyList(), scanIssues = listOf(scanIssue())))
            val with = page(readyInbox(books = emptyList(), scanIssues = listOf(scanIssue(detail = "ffprobe: EBML"))))

            without.querySelector(".inbox-issue-detail").shouldBeNull()
            with.querySelector(".inbox-issue-detail")?.textContent shouldBe "ffprobe: EBML"
        }

        test("dismissing an issue reports which one") {
            val dismissed = mutableListOf<String>()
            val host =
                page(
                    readyInbox(books = emptyList(), scanIssues = listOf(scanIssue(id = "i9"))),
                    onDismissIssue = { dismissed += it },
                )

            button(host, "Dismiss").shouldNotBeNull().click()
            awaitFrame()

            dismissed shouldContainExactly listOf("i9")
        }

        // The two halves are independent: an issue is not a book awaiting a decision, it is a thing
        // that went wrong and produced no book at all.
        test("issues alone are still content — the inbox does not claim to be empty") {
            val host = page(readyInbox(books = emptyList(), scanIssues = listOf(scanIssue())))

            host.textContent.orEmpty() shouldContain "Needs attention"
            host.querySelector(".empty").shouldBeNull()
        }

        test("books alone are still content") {
            val host = page(readyInbox(scanIssues = emptyList()))

            host.textContent.orEmpty() shouldContain "Waiting for review"
            host.querySelector(".empty").shouldBeNull()
        }

        test("only both halves empty says so") {
            val host = page(readyInbox(books = emptyList(), scanIssues = emptyList()))

            host.querySelector(".empty")?.textContent.orEmpty() shouldContain "Inbox empty"
        }

        test("a transient failure is shown and can be dismissed") {
            var cleared = 0
            val host = page(readyInbox(error = "No library available"), onClearError = { cleared++ })

            host.querySelector(".inbox-err")?.textContent.orEmpty() shouldContain "No library available"
            (host.querySelector(".inbox-err .inbox-notice-x") as HTMLElement).click()
            awaitFrame()

            cleared shouldBe 1
        }

        // ⛔ Assert the whole string, never `shouldContain`. "Released 1 books" CONTAINS
        // "Released 1 book", so a receipt that always used the plural passed the containment
        // version of this test — sabotage proved it.
        test("the release receipt counts, and agrees with itself about the plural") {
            val one = page(readyInbox(lastReleasedCount = 1))
            val many = page(readyInbox(lastReleasedCount = 4))

            one.querySelector(".inbox-notice-t")?.textContent shouldBe "Released 1 book"
            many.querySelector(".inbox-notice-t")?.textContent shouldBe "Released 4 books"
        }

        // The two notices are the same component with different wiring, so proving one dismisses
        // proves nothing about the other: crossing the two callbacks left the suite green.
        test("each notice dismisses its own thing") {
            var clearedError = 0
            var clearedReceipt = 0
            val host =
                page(
                    readyInbox(error = "No library available", lastReleasedCount = 2),
                    onClearError = { clearedError++ },
                    onClearReleaseResult = { clearedReceipt++ },
                )

            (host.querySelector(".inbox-note .inbox-notice-x") as HTMLElement).click()
            awaitFrame()

            clearedReceipt shouldBe 1
            clearedError shouldBe 0
        }

        test("a loading inbox draws a skeleton rather than an empty page") {
            val host = page(AdminInboxUiState.Loading)

            host.querySelector(".inbox-skel").shouldNotBeNull()
            host.querySelector(".inbox-book").shouldBeNull()
        }

        test("an inbox that cannot be loaded explains itself and offers a retry that fires") {
            var retries = 0
            val host = page(AdminInboxUiState.Error("Server said no."), onRetry = { retries++ })

            host.textContent.orEmpty() shouldContain "Server said no."
            (host.querySelector(".empty button") as HTMLElement).click()
            awaitFrame()

            retries shouldBe 1
        }
    })
