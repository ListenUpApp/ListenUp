package com.calypsan.listenup.web.features.bookdetail

import com.calypsan.listenup.client.domain.model.Genre
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailUiState
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList

/**
 * The Details panel on the web (spec §3.2.1 D31): the shared rule's rows in order, then All credits, Files & audio and
 * Maps & illustrations as links — one document opens in a new tab, more go to the Files view.
 */
class BookDetailDetailsTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        val full: BookDetailUiState.Ready =
            readyBook().let { ready ->
                ready.copy(
                    book =
                        ready.book.copy(
                            isbn = "9781982110567",
                            asin = "B07T8TY2CP",
                            language = "en",
                            genres =
                                listOf(
                                    Genre(id = "g1", name = "Horror", slug = "horror", path = "/fiction/horror"),
                                    Genre(id = "g2", name = "Psychological horror", slug = "psych", path = "/fiction/horror/psych"),
                                ),
                        ),
                    addedAt = 1_770_854_400_000L,
                )
            }

        fun rendered(
            documents: List<com.calypsan.listenup.client.domain.model.BookDocument> = sampleDocuments(),
            onOpenFiles: () -> Unit = {},
        ): HTMLElement =
            mounts.mount {
                BookDetailPage(
                    state = full,
                    onPlayFromChapter = {},
                    onOpenLibrary = {},
                    onPlay = {},
                    onRetryConnection = {},
                    documents = documents,
                    onOpenFiles = onOpenFiles,
                )
            }

        fun details(host: HTMLElement): HTMLElement = host.querySelector(".bd-details").shouldNotBeNull() as HTMLElement

        fun valueOf(
            host: HTMLElement,
            label: String,
        ): HTMLElement =
            details(host)
                .querySelectorAll(".meta-r")
                .asList()
                .map { it as HTMLElement }
                .first { it.querySelector("dt")?.textContent == label }
                .querySelector("dd") as HTMLElement

        test("the rows read in the spec's order, and the old Duration, Chapters and Narrator rows are gone") {
            val host = rendered()
            details(host).querySelectorAll("dt").asList().map { it.textContent } shouldBe
                listOf(
                    "Publisher",
                    "Released",
                    "Genre",
                    "Language",
                    "Length",
                    "Added",
                    "ISBN",
                    "ASIN",
                    "All credits",
                    "Files & audio",
                    "Maps & illustrations",
                )
            valueOf(host, "Genre").textContent shouldBe "Psychological horror · Unabridged"
            valueOf(host, "Length").textContent!!.endsWith(" chapters") shouldBe true
            valueOf(host, "Length").textContent!!.contains("h ") shouldBe true
        }

        test("All credits is a button") {
            valueOf(rendered(), "All credits").querySelector("button.bd-detail-link").shouldNotBeNull()
        }

        test("Files & audio is a link to the Files view, followed in-app on a plain click") {
            var opened = 0
            val host = rendered(onOpenFiles = { opened++ })
            val link = valueOf(host, "Files & audio").querySelector("a").shouldNotBeNull() as HTMLElement
            link.getAttribute("href") shouldBe "/book/42/files"
            link.click()
            awaitFrame()
            opened shouldBe 1
        }

        test("two documents go to the Files view") {
            val link = valueOf(rendered(), "Maps & illustrations").querySelector("a").shouldNotBeNull() as HTMLElement
            link.getAttribute("href") shouldBe "/book/42/files"
            link.getAttribute("target") shouldBe null
        }

        test("one document opens in a new tab, and says so to a screen reader") {
            val one = sampleDocuments().take(1)
            val link =
                valueOf(rendered(documents = one), "Maps & illustrations").querySelector("a").shouldNotBeNull() as HTMLElement
            link.getAttribute("href") shouldBe documentUrl("42", one.single().id)
            link.getAttribute("target") shouldBe "_blank"
            link.getAttribute("rel") shouldBe "noopener"
            link.querySelector("[aria-hidden]")?.textContent shouldBe "↗"
            link.querySelector(".sr-only")?.textContent shouldBe " (opens in a new tab)"
            link.textContent!!.startsWith("PDF · 2 MB") shouldBe true
        }
    })
