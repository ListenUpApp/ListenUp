package com.calypsan.listenup.web.features.bookdetail

import com.calypsan.listenup.api.error.BookError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.client.domain.model.ListenerRating
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailUiState
import com.calypsan.listenup.client.presentation.bookdetail.BookRatingsUiState
import com.calypsan.listenup.client.presentation.bookdetail.PrimaryAction
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.awaitFrame
import com.calypsan.listenup.web.features.ratings.RatingsPanel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.Node
import org.w3c.dom.asList

/**
 * Book Detail's states on the web (spec §3.2.3): the error with Retry and Back to Library, the admin's scan warning
 * with Review at the top of the hero, the offline reason in the hero directly above the actions, and the finished
 * reader's "How was it?".
 */
class BookDetailStatesTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        fun rendered(
            state: BookDetailUiState,
            onRetryConnection: () -> Unit = {},
            onOpenLibrary: () -> Unit = {},
            onOpenFiles: () -> Unit = {},
        ): HTMLElement =
            mounts.mount {
                BookDetailPage(
                    state = state,
                    onPlayFromChapter = {},
                    onOpenLibrary = onOpenLibrary,
                    onPlay = {},
                    onRetryConnection = onRetryConnection,
                    onOpenFiles = onOpenFiles,
                )
            }

        fun button(
            host: HTMLElement,
            label: String,
        ): HTMLButtonElement? =
            host.querySelectorAll("button").asList().filterIsInstance<HTMLButtonElement>().firstOrNull {
                it.textContent?.trim() == label
            }

        test("an error says what happened once, and offers Retry when asking again could help") {
            var retries = 0
            var backs = 0
            val host =
                rendered(
                    BookDetailUiState.Error(TransportError.NetworkUnavailable()),
                    onRetryConnection = { retries++ },
                    onOpenLibrary = { backs++ },
                )
            host.querySelector("h1")?.textContent?.trim() shouldBe "Couldn’t load this book"
            host.textContent.orEmpty() shouldNotContain "This browser hasn't synced"
            // The title is said once: the page heading, not again inside the empty state.
            host.textContent.orEmpty().split("Couldn’t load this book").size shouldBe 2
            host.querySelector(".bd .empty")?.textContent.orEmpty() shouldContain TransportError.NetworkUnavailable().message
            button(host, "Retry").shouldNotBeNull().click()
            button(host, "Back to Library").shouldNotBeNull().click()
            awaitFrame()
            retries shouldBe 1
            backs shouldBe 1
        }

        // isRetryable is false for a book that no longer exists: asking again cannot bring it back.
        test("a book that no longer exists offers only the way back") {
            val host = rendered(BookDetailUiState.Error(BookError.NotFound()))
            host.querySelector(".bd .empty")?.textContent.orEmpty() shouldContain BookError.NotFound().message
            button(host, "Retry").shouldBeNull()
            button(host, "Back to Library").shouldNotBeNull()
        }

        test("an admin sees the scan warning at the top of the hero, and Review opens the Files view") {
            var reviews = 0
            val host =
                rendered(
                    readyBook().copy(isAdmin = true, hasScanWarning = true, showScanWarning = true),
                    onOpenFiles = { reviews++ },
                )
            val scan = (host.querySelector(".bd-head .bd-scan[role=status]") as? HTMLElement).shouldNotBeNull()
            scan.textContent.orEmpty().contains("The scanner flagged this book’s files.") shouldBe true
            val cover = host.querySelector(".bd-cover").shouldNotBeNull()
            (scan.compareDocumentPosition(cover).toInt() and Node.DOCUMENT_POSITION_FOLLOWING.toInt()) shouldBe
                Node.DOCUMENT_POSITION_FOLLOWING.toInt()
            (scan.querySelector("button") as HTMLButtonElement).click()
            awaitFrame()
            reviews shouldBe 1
        }

        test("a member never sees the scan warning") {
            rendered(readyBook().copy(hasScanWarning = true, showScanWarning = false))
                .querySelector(".bd-scan")
                .shouldBeNull()
        }

        test("offline, the reason sits in the hero directly before the actions, with Retry, over a disabled primary") {
            var retries = 0
            val host =
                rendered(
                    readyBook(showServerWarning = true).copy(canPlay = false, primaryAction = PrimaryAction.UnavailableOffline),
                    onRetryConnection = { retries++ },
                )
            val reason = (host.querySelector(".bd-head .bd-offline[role=status]") as? HTMLElement).shouldNotBeNull()
            reason.textContent.orEmpty().contains("Can’t reach your server, so this book can’t stream right now.") shouldBe true
            reason.nextElementSibling?.classList?.contains("bd-actions") shouldBe true
            (host.querySelector(".bd-primary") as HTMLButtonElement).hasAttribute("disabled") shouldBe true
            host.querySelector(".banner.warn").shouldBeNull()
            (reason.querySelector("button") as HTMLButtonElement).click()
            awaitFrame()
            retries shouldBe 1
        }

        test("a ready page never says disabled=false") {
            rendered(readyBook().copy(isAdmin = true)).querySelectorAll("[disabled='false']").length shouldBe 0
        }

        test("a finished reader is asked how it was, and to leave a note for their listeners") {
            val host =
                mounts.mount {
                    RatingsPanel(
                        state =
                            BookRatingsUiState.Ready(
                                listeners = null,
                                mine =
                                    ListenerRating(
                                        bookId = "42",
                                        userId = "me",
                                        halfStars = 8,
                                        note = null,
                                        ratedAtMs = 0L,
                                    ),
                                external = null,
                                breakdown = emptyList(),
                                canRefresh = false,
                                isRefreshingExternal = false,
                                isCheckingExternal = false,
                            ),
                        onSetStars = {},
                        onRate = { _, _ -> },
                        onClear = {},
                        isFinished = true,
                    )
                }
            host.querySelector(".rt-you-l")?.textContent shouldBe "How was it?"
            button(host, "Add a note for your listeners").shouldNotBeNull()
        }
    })
