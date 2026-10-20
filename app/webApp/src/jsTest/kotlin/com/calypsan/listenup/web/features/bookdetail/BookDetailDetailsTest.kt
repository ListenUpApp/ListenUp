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

        fun links(host: HTMLElement): List<HTMLElement> =
            host.querySelectorAll(".bd-detail-links .bd-detail-link").asList().map { it as HTMLElement }

        fun link(
            host: HTMLElement,
            startsWith: String,
        ): HTMLElement = links(host).first { it.textContent.orEmpty().startsWith(startsWith) }

        test("the facts read in the spec's order, and the old Duration, Chapters and Narrator rows are gone") {
            val host = rendered()
            details(host).querySelectorAll("dt").asList().map { it.textContent } shouldBe
                listOf("Publisher", "Released", "Genre", "Language", "Length", "Added", "ISBN", "ASIN")
            valueOf(host, "Genre").textContent shouldBe "Psychological horror · Unabridged"
            valueOf(host, "Length").textContent!!.endsWith(" chapters") shouldBe true
            valueOf(host, "Length").textContent!!.contains("h ") shouldBe true
        }

        // Spec D31: a link reads label first — "All credits (N) ›" — so its accessible name says what it opens, not
        // just "AAC · 64 kbps".
        test("the three links read label first, and that is their accessible name") {
            val host = rendered()
            links(host).map { it.textContent.orEmpty().substringBefore(" (") } shouldBe
                listOf("All credits", "Files & audio", "Maps & illustrations")
            links(host).forEach { it.getAttribute("aria-label") shouldBe null }
            link(host, "All credits").textContent shouldBe "All credits (2)›"
            link(host, "Files & audio").textContent.orEmpty().startsWith("Files & audio (") shouldBe true
        }

        test("All credits is a button") {
            link(rendered(), "All credits").tagName.lowercase() shouldBe "button"
        }

        test("Files & audio is a link to the Files view, followed in-app on a plain click") {
            var opened = 0
            val host = rendered(onOpenFiles = { opened++ })
            val files = link(host, "Files & audio")
            files.getAttribute("href") shouldBe "/book/42/files"
            files.click()
            awaitFrame()
            opened shouldBe 1
        }

        test("two documents go to the Files view") {
            val maps = link(rendered(), "Maps & illustrations")
            maps.getAttribute("href") shouldBe "/book/42/files"
            maps.getAttribute("target") shouldBe null
        }

        test("one PDF opens in a new tab, and says so to a screen reader") {
            val one = sampleDocuments().take(1)
            val maps = link(rendered(documents = one), "Maps & illustrations")
            maps.getAttribute("href") shouldBe documentUrl("42", one.single().id)
            maps.getAttribute("target") shouldBe "_blank"
            maps.getAttribute("rel") shouldBe "noopener"
            maps.querySelector("[aria-hidden]")?.textContent shouldBe "↗"
            maps.querySelector(".sr-only")?.textContent shouldBe " (opens in a new tab)"
            maps.textContent!!.startsWith("Maps & illustrations (PDF · 2 MB)") shouldBe true
        }

        // A browser tab shows an epub as raw bytes, so one that is not a PDF downloads, as the Files view's does.
        test("one document a browser cannot show downloads instead of opening a tab of bytes") {
            val epub = sampleDocuments().drop(1)
            val maps = link(rendered(documents = epub), "Maps & illustrations")
            maps.getAttribute("href") shouldBe documentUrl("42", epub.single().id)
            maps.getAttribute("target") shouldBe null
            maps.getAttribute("download") shouldBe "bonus-chapter.epub"
            maps.querySelector(".sr-only")?.textContent shouldBe " (downloads)"
        }
    })
