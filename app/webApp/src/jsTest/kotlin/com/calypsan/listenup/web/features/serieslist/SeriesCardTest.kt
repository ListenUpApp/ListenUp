package com.calypsan.listenup.web.features.serieslist

import com.calypsan.listenup.client.domain.model.BookContributor
import com.calypsan.listenup.client.domain.model.Series
import com.calypsan.listenup.client.domain.model.SeriesProgress
import com.calypsan.listenup.client.domain.model.SeriesWithBooks
import com.calypsan.listenup.client.presentation.library.LibraryUiState
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.web.InShell
import com.calypsan.listenup.web.ViewportFrames
import com.calypsan.listenup.web.awaitFrame
import com.calypsan.listenup.web.features.library.contractBook
import com.calypsan.listenup.web.features.library.contractLibrary
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * A series card (spec c6, board `Library-WebPhone`): up to three overlapping covers with the series' book count on
 * them, "7 books · Matt Dinniman", and how far through it the reader is, as a bar and in words.
 */
class SeriesCardTest :
    FunSpec({
        val frames = ViewportFrames()
        afterTest { frames.disposeAll() }

        fun page(
            width: Int,
            series: List<SeriesWithBooks>,
            progress: Map<SeriesId, SeriesProgress> = emptyMap(),
        ) = frames.mount(width) {
            InShell {
                SeriesListPage(
                    state = contractLibrary(series = series, seriesProgress = progress) as LibraryUiState,
                    onEvent = {},
                    onOpenSeries = {},
                    onSelectFacet = {},
                )
            }
        }

        test("at desktop width a series' three covers share one top") {
            val frame = page(DESKTOP, listOf(dcc(7)))
            awaitFrame()
            frame.findAll(".srs-card .srs-stack .cover").map { frame.rect(it).top }.distinct().size shouldBe 1
            frames.disposeAll()
        }

        test("on a phone the three covers overlap in a row inside the card, with the '7' badge, hidden from screen readers") {
            val frame = page(PHONE, listOf(dcc(7)))
            awaitFrame()

            val covers = frame.findAll(".srs-card .srs-stack .cover")
            covers.size shouldBe 3
            val lefts = covers.map { frame.rect(it).left }
            lefts[1] - lefts[0] shouldBe 20.0
            lefts[2] - lefts[1] shouldBe 20.0
            // Overlapping side by side, not stacked down the card: one top for all three.
            covers.map { frame.rect(it).top }.distinct().size shouldBe 1
            // And the stack stays inside its card, whatever the width.
            val card = frame.rect(frame.find(".srs-card"))
            covers.forEach { cover ->
                val box = frame.rect(cover)
                (box.left >= card.left && box.right <= card.right) shouldBe true
                (box.top >= card.top && box.bottom <= card.bottom) shouldBe true
            }
            val badge = frame.find(".srs-card .srs-badge")
            badge.textContent shouldBe "7"
            // The whole stack is decoration: the meta line says "7 books" in words.
            frame.find(".srs-card .srs-stack").getAttribute("aria-hidden") shouldBe "true"
        }

        test("meta reads '7 books · Matt Dinniman'") {
            val frame = page(PHONE, listOf(dcc(7)))
            awaitFrame()

            frame.find(".srs-count").textContent shouldBe "7 books · Matt Dinniman"
        }

        test("a started series has a progressbar with aria-valuenow and '2 of 7'") {
            val series = dcc(7)
            val frame = page(PHONE, listOf(series), mapOf(series.series.id to SeriesProgress(finishedCount = 2, totalCount = 7)))
            awaitFrame()

            val bar = frame.find(".srs-card [role=progressbar]")
            bar.getAttribute("aria-valuenow") shouldBe "29"
            bar.getAttribute("aria-label") shouldBe "Series progress"
            frame.find(".srs-progress").textContent shouldBe "2 of 7"
        }

        test("complete reads 'Complete' and untouched 'Not started'") {
            val done = seriesOf("done", "Done", 3)
            val fresh = seriesOf("fresh", "Fresh", 3)
            val frame =
                page(
                    PHONE,
                    listOf(done, fresh),
                    mapOf(done.series.id to SeriesProgress(3, 3), fresh.series.id to SeriesProgress(0, 3)),
                )
            awaitFrame()

            frame.findAll(".srs-progress").map { it.textContent } shouldBe listOf("Complete", "Not started")
            frame.findAll(".srs-progress")[0].classList.contains("is-complete") shouldBe true
        }

        test("every card is one height, on a phone and on a desktop (the list is virtualised)") {
            listOf(PHONE, DESKTOP).forEach { width ->
                val long = seriesOf("long", "A Series Whose Name Runs Long Enough To Wrap Twice Over At Least", 1)
                val started = dcc(7)
                val frame =
                    page(width, listOf(long, started), mapOf(started.series.id to SeriesProgress(2, 7)))
                awaitFrame()

                frame
                    .findAll(".srs-card")
                    .map { frame.rect(it).height }
                    .distinct()
                    .size shouldBe 1
                frames.disposeAll()
            }
        }

        test("a phone lists series as one column of row cards; a desktop keeps the grid") {
            val phone = page(PHONE, listOf(dcc(7), seriesOf("b", "B", 2)))
            awaitFrame()
            phone
                .findAll(".srs-card")
                .map { phone.rect(it).left }
                .distinct()
                .size shouldBe 1
            val card = phone.rect(phone.find(".srs-card"))
            val stack = phone.rect(phone.find(".srs-stack"))
            val name = phone.rect(phone.find(".srs-name"))
            (name.left >= stack.right) shouldBe true
            (card.width > card.height) shouldBe true
            frames.disposeAll()

            val desktop = page(DESKTOP, listOf(dcc(7), seriesOf("b", "B", 2)))
            awaitFrame()
            desktop
                .findAll(".srs-card")
                .map { desktop.rect(it).top }
                .distinct()
                .size shouldBe 1
        }
    })

private fun dcc(bookCount: Int): SeriesWithBooks {
    val books =
        (1..bookCount).map {
            contractBook("dcc-b$it", "Dungeon Crawler Carl $it")
                .copy(authors = listOf(BookContributor(id = "md", name = "Matt Dinniman")))
        }
    return SeriesWithBooks(
        series = Series(id = SeriesId("dcc"), name = "Dungeon Crawler Carl"),
        books = books,
        bookSequences = books.associate { it.id.value to null },
    )
}

private fun seriesOf(
    id: String,
    name: String,
    bookCount: Int,
): SeriesWithBooks {
    val books = (1..bookCount).map { contractBook("$id-b$it", "$name $it") }
    return SeriesWithBooks(
        series = Series(id = SeriesId(id), name = name),
        books = books,
        bookSequences = books.associate { it.id.value to null },
    )
}

private const val PHONE = 390
private const val DESKTOP = 1280
