package com.calypsan.listenup.web.features.bookdetail

import com.calypsan.listenup.client.domain.model.BookSeries
import com.calypsan.listenup.client.presentation.bookdetail.BookSeriesPath
import com.calypsan.listenup.client.presentation.seriesdetail.SeriesCrumb
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList

/**
 * Where a book sits among series, on Book Detail.
 *
 * What these pin: one path line per series ("Cosmere › Mistborn › Mistborn Era 1 #1"), every name
 * in it a real button that opens THAT series, the position formatted the way a person writes it, a
 * path four or more levels deep folding its middle into a "…" that expands in place, and a
 * standalone book growing no empty row.
 */
class BookDetailSeriesTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        fun bookDetailPage(
            series: List<BookSeries> = emptyList(),
            seriesPaths: List<BookSeriesPath>? = null,
            onOpenSeries: (String) -> Unit = {},
        ): HTMLElement =
            mounts.mount {
                BookDetailPage(
                    state =
                        if (seriesPaths == null) {
                            readyBook(series = series)
                        } else {
                            readyBook(series = series, seriesPaths = seriesPaths)
                        },
                    tab = "overview",
                    onSelectTab = {},
                    onOpenLibrary = {},
                    onPlay = {},
                    onOpenSeries = onOpenSeries,
                    onRetryConnection = {},
                )
            }

        fun HTMLElement.lines() = querySelectorAll(".bd-series-line").asList().map { it as HTMLElement }

        fun HTMLElement.linkNames() = querySelectorAll(".bd-series-link").asList().map { it.textContent.orEmpty() }

        val era1Path =
            BookSeriesPath(
                seriesId = "era1",
                seriesName = "Mistborn Era 1",
                sequence = "1",
                ancestors = listOf(SeriesCrumb("cosmere", "Cosmere"), SeriesCrumb("mistborn", "Mistborn")),
            )

        test("a book in no series renders no series row") {
            val root = bookDetailPage()

            root.querySelector(".bd-series") shouldBe null
        }

        test("the path names every series from the top down, the book's position on the last") {
            val root = bookDetailPage(seriesPaths = listOf(era1Path))

            root.linkNames() shouldContainExactly listOf("Cosmere", "Mistborn", "Mistborn Era 1#1")
            (root.querySelector(".bd-series-seq") as HTMLElement).textContent shouldBe "#1"
        }

        test("the paths are a group named 'Series path', each line an ordered list") {
            val root = bookDetailPage(seriesPaths = listOf(era1Path))

            val group = root.querySelector(".bd-series") as HTMLElement
            group.getAttribute("role") shouldBe "group"
            group.getAttribute("aria-label") shouldBe "Series path"
            root.lines().single().tagName shouldBe "OL"
        }

        // Every name is a way into that series — the ancestors too, not just the one the book is in.
        test("each name in the path opens its own series") {
            val opened = mutableListOf<String>()
            val root = bookDetailPage(seriesPaths = listOf(era1Path), onOpenSeries = { opened += it })

            root.querySelectorAll(".bd-series-link").asList().forEach { (it as HTMLElement).click() }

            opened shouldBe listOf("cosmere", "mistborn", "era1")
        }

        test("every name in the path is a real button") {
            val root = bookDetailPage(seriesPaths = listOf(era1Path))

            root
                .querySelectorAll(".bd-series-link")
                .asList()
                .map { (it as HTMLElement).tagName }
                .distinct() shouldBe
                listOf("BUTTON")
        }

        test("a book in two series gets one path line per series") {
            val root =
                bookDetailPage(
                    seriesPaths =
                        listOf(
                            era1Path,
                            BookSeriesPath("s2", "Stand-alones", null, ancestors = emptyList()),
                        ),
                )

            root.lines().size shouldBe 2
        }

        test("an unnumbered membership renders the name without a position") {
            val root = bookDetailPage(series = listOf(BookSeries(seriesId = "s1", seriesName = "The Cosmere", sequence = null)))

            root.querySelector(".bd-series-seq") shouldBe null
            root.linkNames() shouldBe listOf("The Cosmere")
        }

        // ⛔ Pins the OUTPUT: the ViewModel formats `sequence` from `sequenceLabel`, so "#1" here is
        // what a person writes, never "#1.0".
        test("a whole-numbered position reads '#1', never '#1.0'") {
            val root = bookDetailPage(series = listOf(BookSeries(seriesId = "s1", seriesName = "Stormlight", sequence = 1.0)))

            (root.querySelector(".bd-series-seq") as HTMLElement).textContent shouldBe "#1"
        }

        test("an interquel keeps its fractional position") {
            val root = bookDetailPage(series = listOf(BookSeries(seriesId = "s1", seriesName = "Stormlight", sequence = 2.5)))

            (root.querySelector(".bd-series-seq") as HTMLElement).textContent shouldBe "#2.5"
        }

        // Four levels and up fold the middle, so the line still fits beside the cover; the "…" is a
        // real control that brings the hidden names back in place.
        test("a path four levels deep folds its middle into a '…' that expands in place") {
            val deep =
                BookSeriesPath(
                    seriesId = "d",
                    seriesName = "Deep",
                    sequence = "2",
                    ancestors = listOf(SeriesCrumb("a", "Alpha"), SeriesCrumb("b", "Beta"), SeriesCrumb("c", "Gamma")),
                )
            val root = bookDetailPage(seriesPaths = listOf(deep))

            root.linkNames() shouldContainExactly listOf("Alpha", "Gamma", "Deep#2")
            val fold = root.querySelector(".bd-series-fold") as HTMLElement
            fold.tagName shouldBe "BUTTON"
            fold.getAttribute("aria-label") shouldBe "Show the full series path"

            fold.click()
            awaitFrame()

            root.linkNames() shouldContainExactly listOf("Alpha", "Beta", "Gamma", "Deep#2")
            root.querySelector(".bd-series-fold") shouldBe null
        }

        test("a path three levels deep never folds") {
            val root = bookDetailPage(seriesPaths = listOf(era1Path))

            root.querySelector(".bd-series-fold") shouldBe null
        }
    })
