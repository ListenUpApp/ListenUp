package com.calypsan.listenup.web.features.browse

import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.FacetKind
import com.calypsan.listenup.client.presentation.browsefacet.BrowseFacetUiState
import com.calypsan.listenup.client.presentation.genredestination.FacetIcon
import com.calypsan.listenup.api.dto.FacetStats
import com.calypsan.listenup.client.presentation.genredestination.GenreCrumb
import com.calypsan.listenup.client.presentation.genredestination.GenreDestinationUiState
import com.calypsan.listenup.client.presentation.genredestination.GenreIdentity
import com.calypsan.listenup.client.presentation.genredestination.SubGenre
import com.calypsan.listenup.core.GenreId
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.awaitFrame
import com.calypsan.listenup.web.features.library.contractBook
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList

private const val MINUTE = 60_000L

private const val HOUR = 60 * MINUTE

internal fun facetReady(
    kind: FacetKind = FacetKind.Tag,
    facetName: String = "Grimdark",
    books: List<BookListItem> = listOf(contractBook("b1", "The Blade Itself")),
    bookCount: Int = books.size,
    totalDurationMs: Long = 12 * HOUR,
) = BrowseFacetUiState.Ready(
    kind = kind,
    facetName = facetName,
    books = books,
    bookCount = bookCount,
    totalDurationMs = totalDurationMs,
)

internal fun genreReady(
    name: String = "Grimdark",
    blurb: String? = null,
    hue: String = "#5B3A8A",
    breadcrumb: List<GenreCrumb> = emptyList(),
    subGenres: List<SubGenre> = emptyList(),
    includeSubGenres: Boolean = false,
    bookCount: Int = 1,
    totalDurationMs: Long = 9 * HOUR,
    books: List<BookListItem> = listOf(contractBook("b1", "The Blade Itself")),
) = GenreDestinationUiState.Ready(
    identity = GenreIdentity(name = name, slug = name.lowercase(), blurb = blurb, icon = FacetIcon.FANTASY, hue = hue),
    breadcrumb = breadcrumb,
    subGenres = subGenres,
    hasSubs = subGenres.isNotEmpty(),
    includeSubGenres = includeSubGenres,
    stats = FacetStats(bookCount = bookCount, totalDurationMs = totalDurationMs),
    books = books,
)

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
 * The three browse destinations — `/tag/{id}`, `/mood/{id}` and `/genre/{id}`.
 *
 * What these pin: the hero describes the shelf the reader is actually looking at (the count is the
 * server's, not `books.size`, and it moves when the sub-genre scope does); an empty facet is an
 * honest sentence rather than a blank grid; a dead link is a way out rather than a wedge; and the
 * genre page's own controls — the ancestor trail, the sub-genre pills, the include toggle — all
 * lead somewhere.
 */
class BrowsePagesTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        fun facetPage(
            state: BrowseFacetUiState,
            onOpenBook: (String) -> Unit = {},
            onOpenLibrary: () -> Unit = {},
        ): HTMLElement =
            mounts.mount {
                BrowseFacetPage(state = state, onOpenBook = onOpenBook, onOpenLibrary = onOpenLibrary)
            }

        fun genrePage(
            state: GenreDestinationUiState,
            onOpenBook: (String) -> Unit = {},
            onOpenGenre: (String) -> Unit = {},
            onOpenLibrary: () -> Unit = {},
            onToggleSubGenres: () -> Unit = {},
        ): HTMLElement =
            mounts.mount {
                GenreDestinationPage(
                    state = state,
                    onOpenBook = onOpenBook,
                    onOpenGenre = onOpenGenre,
                    onOpenLibrary = onOpenLibrary,
                    onToggleSubGenres = onToggleSubGenres,
                )
            }

        test("a tag's shelf names it and says what is on it") {
            val host = facetPage(facetReady(facetName = "Grimdark", bookCount = 3, totalDurationMs = 41 * HOUR))

            text(host, ".brw-eyebrow") shouldBe "Tag"
            text(host, ".brw-t") shouldBe "Grimdark"
            host.querySelectorAll(".brw-stat").asList().map { (it as HTMLElement).textContent?.trim() } shouldContainExactly
                listOf("3 books", "41h of audio")
        }

        test("a mood is labelled as a mood, not as a tag") {
            // The two share a page and a store; only the kind separates them, and getting it wrong
            // is invisible until someone reads the eyebrow.
            val host = facetPage(facetReady(kind = FacetKind.Mood, facetName = "Cosy"))

            text(host, ".brw-eyebrow") shouldBe "Mood"
        }

        test("the count is the server's, not the number of cards on screen") {
            // ⛔ `bookCount` reflects the whole live set; `books` is whatever Room has mirrored. A
            // page that counted the cards would under-report a partially-synced library and quietly
            // disagree with every other client.
            val host =
                facetPage(
                    facetReady(books = listOf(contractBook("b1", "One"), contractBook("b2", "Two")), bookCount = 412),
                )

            text(host, ".brw-stat") shouldBe "412 books"
            host.querySelectorAll(".lib-card").length shouldBe 2
        }

        test("a shelf under an hour is reported in minutes") {
            // "0h of audio" on a two-book tag reads as a bug, not as a small collection.
            val host = facetPage(facetReady(totalDurationMs = 25 * MINUTE))

            host.querySelectorAll(".brw-stat").asList().map { (it as HTMLElement).textContent?.trim() } shouldContainExactly
                listOf("1 book", "25m of audio")
        }

        test("one book is a book, not 1 books") {
            val host = facetPage(facetReady(bookCount = 1))

            text(host, ".brw-stat") shouldBe "1 book"
        }

        test("a facet with no books says so instead of leaving a hero over nothing") {
            val host = facetPage(facetReady(books = emptyList(), bookCount = 0))

            text(host, ".brw-none") shouldBe "No books here yet."
            host.querySelector(".lib-grid").shouldBeNull()
        }

        test("opening a book from a facet reports which one") {
            val opened = mutableListOf<String>()
            val host = facetPage(facetReady(books = listOf(contractBook("b7", "Best Served Cold"))), onOpenBook = { opened += it })

            (host.querySelector(".lib-card") as HTMLElement).click()
            awaitFrame()

            opened shouldContainExactly listOf("b7")
        }

        test("a link to a tag that is gone is a way out, not a wedge") {
            var left = 0
            val host = facetPage(BrowseFacetUiState.NotFound(FacetKind.Tag), onOpenLibrary = { left++ })

            text(host, ".brw-empty h1") shouldBe "This tag is gone"
            button(host, "Back to Library").shouldNotBeNull().click()
            awaitFrame()

            left shouldBe 1
        }

        test("a facet still loading shows a placeholder, not an empty shelf") {
            val host = facetPage(BrowseFacetUiState.Loading)

            host.querySelector(".brw-skel").shouldNotBeNull()
            host.querySelector(".brw-none").shouldBeNull()
        }

        test("a genre wears the accent its own name derives") {
            // The hue is FacetIdentity's, shared with Android and iOS — a genre looks like itself
            // on every client, and the page is the only place web can honour that.
            val host = genrePage(genreReady(hue = "#1F7E74"))

            (host.querySelector(".brw-icon") as HTMLElement).getAttribute("style") shouldContain "#1F7E74"
        }

        test("the ancestors are the trail, and each one opens that genre") {
            val opened = mutableListOf<String>()
            val host =
                genrePage(
                    genreReady(
                        name = "Grimdark",
                        breadcrumb = listOf(GenreCrumb(GenreId("g-fantasy"), "Fantasy")),
                    ),
                    onOpenGenre = { opened += it },
                )

            val crumb = host.querySelector(".crumb") as HTMLElement
            crumb.textContent?.trim() shouldBe "Library/Fantasy/Grimdark"

            crumb
                .querySelectorAll("a")
                .asList()
                .filterIsInstance<HTMLElement>()
                .first { it.textContent?.trim() == "Fantasy" }
                .click()
            awaitFrame()

            opened shouldContainExactly listOf("g-fantasy")
        }

        test("the sub-genre toggle says what it is to both the eye and a screen reader") {
            // ⛔ Both, not either. `aria-pressed` alone is an invisible state; the `on` class alone
            // is a state assistive tech cannot read. The series-edit picker shipped with exactly
            // half of this.
            val host = genrePage(genreReady(subGenres = listOf(SubGenre(GenreId("g1"), "Heroic", 4)), includeSubGenres = true))

            val toggle = button(host, "Include sub-genres").shouldNotBeNull()
            toggle.getAttribute("aria-pressed") shouldBe "true"
            toggle.classList.contains("on") shouldBe true
        }

        test("the sub-genre toggle reports the change") {
            var toggled = 0
            val host =
                genrePage(
                    genreReady(subGenres = listOf(SubGenre(GenreId("g1"), "Heroic", 4))),
                    onToggleSubGenres = { toggled++ },
                )

            val toggle = button(host, "Include sub-genres").shouldNotBeNull()
            toggle.getAttribute("aria-pressed") shouldBe "false"
            toggle.classList.contains("on") shouldBe false

            toggle.click()
            awaitFrame()

            toggled shouldBe 1
        }

        test("a sub-genre pill carries its own count and opens it") {
            val opened = mutableListOf<String>()
            val host =
                genrePage(
                    genreReady(subGenres = listOf(SubGenre(GenreId("g-heroic"), "Heroic", 12))),
                    onOpenGenre = { opened += it },
                )

            text(host, ".brw-sub-n") shouldBe "12"
            buttons(host).first { it.classList.contains("brw-sub") }.click()
            awaitFrame()

            opened shouldContainExactly listOf("g-heroic")
        }

        test("a genre with no children shows no sub-genre row at all") {
            val host = genrePage(genreReady(subGenres = emptyList()))

            host.querySelector(".brw-subs").shouldBeNull()
            button(host, "Include sub-genres").shouldBeNull()
        }

        test("the curator's blurb is shown when there is one, and nothing stands in for it when there is not") {
            text(genrePage(genreReady(blurb = "Fantasy with the shine taken off.")), ".brw-blurb") shouldBe
                "Fantasy with the shine taken off."
            genrePage(genreReady(blurb = null)).querySelector(".brw-blurb").shouldBeNull()
        }

        test("a blurb that is present but blank is not a blank line") {
            genrePage(genreReady(blurb = "   ")).querySelector(".brw-blurb").shouldBeNull()
        }

        test("a link to a genre that is gone is a way out too") {
            var left = 0
            val host = genrePage(GenreDestinationUiState.NotFound, onOpenLibrary = { left++ })

            text(host, ".brw-empty h1") shouldBe "This genre is gone"
            button(host, "Back to Library").shouldNotBeNull().click()
            awaitFrame()

            left shouldBe 1
        }

        test("a genre with no books in scope says so rather than showing a bare hero") {
            val host = genrePage(genreReady(books = emptyList(), bookCount = 0))

            text(host, ".brw-none") shouldBe "No books here yet."
        }
    })
