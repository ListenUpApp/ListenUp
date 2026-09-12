package com.calypsan.listenup.web.features.sync

import com.calypsan.listenup.client.presentation.sync.PendingOperationUi
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLDialogElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList

internal fun failedOp(
    id: String = "op-1",
    description: String = "Edit “Dune”",
    error: String? = "Conflict (HTTP 409)",
) = PendingOperationUi(id = id, description = description, isFailed = true, error = error)

private fun buttons(host: HTMLElement): List<HTMLButtonElement> =
    host.querySelectorAll("button").asList().filterIsInstance<HTMLButtonElement>()

private fun button(
    host: HTMLElement,
    label: String,
): HTMLButtonElement? = buttons(host).firstOrNull { it.textContent?.trim() == label }

private fun text(
    host: HTMLElement,
    selector: String,
): String? = (host.querySelector(selector) as? HTMLElement)?.textContent?.trim()

/**
 * Edits the server refused for good.
 *
 * ⛔ The gap this closes: `PendingOperationQueue` has had `retryOp`/`dismissOp` and a live
 * dead-letter feed all along, and web consumed none of it — so a book edit that exhausted its retry
 * budget was silently and permanently lost. Nothing repairs one on its own: the op's unchanged
 * `(id, revision)` means no reconcile, catch-up or firehose echo touches it.
 *
 * Deliberately NOT a sync indicator. This client shows no in-flight sync chrome, and these specs
 * pin that a reader with nothing failed sees nothing at all.
 */
class DeadLetterNoticeTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        fun notice(
            failed: List<PendingOperationUi>,
            onRetry: (String) -> Unit = {},
            onDismiss: (String) -> Unit = {},
            onRetryAll: () -> Unit = {},
            onDismissAll: () -> Unit = {},
        ): HTMLElement =
            mounts.mount {
                DeadLetterNotice(
                    failed = failed,
                    onRetry = onRetry,
                    onDismiss = onDismiss,
                    onRetryAll = onRetryAll,
                    onDismissAll = onDismissAll,
                )
            }

        test("nothing failed means nothing at all on screen") {
            // ⛔ The standing decision: web carries no pending-operations panel. A permanent
            // "sync is fine" indicator is exactly the chrome this client does not want.
            notice(emptyList()).textContent?.trim() shouldBe ""
        }

        test("a failed edit says so, and one is a change rather than 1 changes") {
            text(notice(listOf(failedOp())), ".dlq-t") shouldBe "1 change didn't save"
            text(notice(listOf(failedOp(), failedOp(id = "op-2"))), ".dlq-t") shouldBe "2 changes didn't save"
        }

        test("the notice offers no way to hide it — only to resolve it") {
            // A close button would let a reader dismiss the fact that their edit is gone, which is
            // not the same as deciding what to do about it.
            val host = notice(listOf(failedOp()))

            buttons(host).map { it.textContent?.trim() } shouldContainExactly listOf("Review")
        }

        test("reviewing names each edit and what the server said about it") {
            // ⛔ The server's own message, verbatim: it is the only thing that explains why retrying
            // might behave differently, and house copy in its place leaves the reader choosing
            // between Retry and Dismiss with nothing to choose on.
            val host =
                notice(
                    listOf(
                        failedOp(description = "Edit “Dune”", error = "Conflict (HTTP 409)"),
                        failedOp(id = "op-2", description = "Add to shelf", error = null),
                    ),
                )

            (host.querySelector(".dlq-go") as HTMLElement).click()
            awaitFrame()

            host.querySelectorAll(".dlq-row").length shouldBe 2
            host.textContent.orEmpty() shouldContain "Edit “Dune”"
            host.textContent.orEmpty() shouldContain "Conflict (HTTP 409)"
            // The one with no server message renders no reason line rather than an empty one.
            host.querySelectorAll(".dlq-why").length shouldBe 1

            (host.querySelector("dialog") as HTMLDialogElement).close()
        }

        test("each edit can be retried or dismissed on its own") {
            val retried = mutableListOf<String>()
            val dismissed = mutableListOf<String>()
            val host =
                notice(
                    listOf(failedOp(id = "op-7")),
                    onRetry = { retried += it },
                    onDismiss = { dismissed += it },
                )

            (host.querySelector(".dlq-go") as HTMLElement).click()
            awaitFrame()

            val row = host.querySelector(".dlq-row") as HTMLElement
            button(row, "Retry").shouldNotBeNull().click()
            button(row, "Dismiss").shouldNotBeNull().click()
            awaitFrame()

            retried shouldContainExactly listOf("op-7")
            dismissed shouldContainExactly listOf("op-7")

            (host.querySelector("dialog") as HTMLDialogElement).close()
        }

        test("both actions are named per row, so they are distinguishable when read aloud") {
            // Four bare "Retry"/"Dismiss" buttons in a list say nothing about which edit they act on.
            val host = notice(listOf(failedOp(description = "Edit “Dune”")))

            (host.querySelector(".dlq-go") as HTMLElement).click()
            awaitFrame()

            val row = host.querySelector(".dlq-row") as HTMLElement
            button(row, "Retry").shouldNotBeNull().getAttribute("aria-label") shouldBe "Retry Edit “Dune”"
            button(row, "Dismiss").shouldNotBeNull().getAttribute("aria-label") shouldBe "Dismiss Edit “Dune”"

            (host.querySelector("dialog") as HTMLDialogElement).close()
        }

        test("the whole batch can be answered at once") {
            var retriedAll = 0
            var dismissedAll = 0
            val host =
                notice(
                    listOf(failedOp(), failedOp(id = "op-2")),
                    onRetryAll = { retriedAll++ },
                    onDismissAll = { dismissedAll++ },
                )

            (host.querySelector(".dlq-go") as HTMLElement).click()
            awaitFrame()

            button(host, "Retry all").shouldNotBeNull().click()
            button(host, "Dismiss all").shouldNotBeNull().click()
            awaitFrame()

            retriedAll shouldBe 1
            dismissedAll shouldBe 1

            (host.querySelector("dialog") as HTMLDialogElement).close()
        }

        test("the review explains what each answer actually does") {
            // Dismiss is not a discard: the queue emits a heal request that re-fetches server truth
            // over the abandoned local value. A reader choosing it deserves to know that.
            val host = notice(listOf(failedOp()))

            (host.querySelector(".dlq-go") as HTMLElement).click()
            awaitFrame()

            host.textContent.orEmpty() shouldContain "keeps the server's version"

            (host.querySelector("dialog") as HTMLDialogElement).close()
        }

        test("closing the review leaves the notice standing") {
            val host = notice(listOf(failedOp()))

            (host.querySelector(".dlq-go") as HTMLElement).click()
            awaitFrame()
            (host.querySelector("dialog") as HTMLDialogElement).close()
            awaitFrame()

            text(host, ".dlq-t").shouldNotBeNull()
        }

        test("a notice with no dialog open renders no dialog") {
            notice(listOf(failedOp())).querySelector("dialog").shouldBeNull()
        }
    })
