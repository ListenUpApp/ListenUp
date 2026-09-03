package com.calypsan.listenup.web.features.bookdetail

import com.calypsan.listenup.client.domain.model.BookSeries
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.browser.document
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.HTMLElement

private fun bookDetailPage(
    series: List<BookSeries>,
    onOpenSeries: (String) -> Unit = {},
): HTMLElement {
    val root = document.createElement("div") as HTMLElement
    document.body?.appendChild(root)
    renderComposable(root = root) {
        BookDetailPage(
            state = readyBook(series = series),
            tab = "overview",
            onSelectTab = {},
            onOpenLibrary = {},
            onPlay = {},
            onOpenSeries = onOpenSeries,
        )
    }
    return root
}

/**
 * The series a book belongs to, on Book Detail.
 *
 * Web showed a book's series NOWHERE outside the edit form until this arc — a reader could not
 * tell that The Way of Kings was book one of anything, let alone reach the rest of it. What these
 * pin: every membership appears (a Cosmere novel is in two series, not one), each carries its own
 * position formatted the way a person writes it, each is a real control that opens that series,
 * and a standalone book grows no empty row.
 */
class BookDetailSeriesTest :
    FunSpec({

        test("a book in no series renders no series row") {
            val root = bookDetailPage(series = emptyList())

            root.querySelector(".bd-series") shouldBe null
        }

        test("the series a book belongs to is named") {
            val root =
                bookDetailPage(
                    series = listOf(BookSeries(seriesId = "s1", seriesName = "The Stormlight Archive", sequence = 1.0)),
                )

            (root.querySelector(".bd-series-chip") as HTMLElement).textContent.orEmpty() shouldContain "The Stormlight Archive"
        }

        // A book can be in several — a Cosmere novel is in both its own series and the Cosmere —
        // which is why this is a row of chips and not the single string iOS reads.
        test("a book in two series shows both") {
            val root =
                bookDetailPage(
                    series =
                        listOf(
                            BookSeries(seriesId = "s1", seriesName = "The Stormlight Archive", sequence = 1.0),
                            BookSeries(seriesId = "s2", seriesName = "The Cosmere", sequence = 7.0),
                        ),
                )

            root.querySelectorAll(".bd-series-chip").length shouldBe 2
        }

        // ⛔ Pins the OUTPUT, not the mechanism. On Kotlin/JS a whole `Double` stringifies as
        // "1", so interpolating the raw `sequence` renders identically here — verified by
        // sabotage: swapping `sequenceLabel` for `sequence` fails nothing in this suite. The
        // "Book 1.0" bug this guards against everywhere else is unreachable in a browser. The
        // call site still reads `sequenceLabel`, because the JVM and Native ones must.
        test("a whole-numbered position reads '#1', never '#1.0'") {
            val root =
                bookDetailPage(
                    series = listOf(BookSeries(seriesId = "s1", seriesName = "The Stormlight Archive", sequence = 1.0)),
                )

            (root.querySelector(".bd-series-seq") as HTMLElement).textContent shouldBe "#1"
        }

        test("an interquel keeps its fractional position") {
            val root =
                bookDetailPage(
                    series = listOf(BookSeries(seriesId = "s1", seriesName = "The Stormlight Archive", sequence = 2.5)),
                )

            (root.querySelector(".bd-series-seq") as HTMLElement).textContent shouldBe "#2.5"
        }

        test("an unnumbered membership renders the name without a position") {
            val root =
                bookDetailPage(
                    series = listOf(BookSeries(seriesId = "s1", seriesName = "The Cosmere", sequence = null)),
                )

            root.querySelector(".bd-series-seq") shouldBe null
            (root.querySelector(".bd-series-chip") as HTMLElement).textContent shouldBe "The Cosmere"
        }

        test("clicking a series chip opens that series, by id") {
            val opened = mutableListOf<String>()
            val root =
                bookDetailPage(
                    series =
                        listOf(
                            BookSeries(seriesId = "s1", seriesName = "The Stormlight Archive", sequence = 1.0),
                            BookSeries(seriesId = "s2", seriesName = "The Cosmere", sequence = 7.0),
                        ),
                    onOpenSeries = { opened += it },
                )

            (root.querySelectorAll(".bd-series-chip").item(1) as HTMLElement).click()

            opened shouldBe listOf("s2")
        }

        // A chip goes somewhere, so it must be reachable by keyboard and announce itself as a
        // control — a styled <span> with a click handler is neither.
        test("a series chip is a real button") {
            val root =
                bookDetailPage(
                    series = listOf(BookSeries(seriesId = "s1", seriesName = "The Stormlight Archive", sequence = 1.0)),
                )

            (root.querySelector(".bd-series-chip") as HTMLElement).tagName shouldBe "BUTTON"
        }
    })
