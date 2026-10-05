package com.calypsan.listenup.client.presentation.bookdetail

import com.calypsan.listenup.client.domain.model.BookSeries
import com.calypsan.listenup.client.domain.model.Series
import com.calypsan.listenup.client.domain.model.SeriesHierarchy
import com.calypsan.listenup.client.presentation.seriesdetail.SeriesCrumb
import com.calypsan.listenup.core.SeriesId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

class BookSeriesPathsTest :
    FunSpec({
        fun series(
            id: String,
            name: String,
            parent: String? = null,
        ) = Series(id = SeriesId(id), name = name, parentId = parent?.let(::SeriesId), parentPosition = 0)

        val hierarchy =
            SeriesHierarchy(
                series =
                    listOf(
                        series("cosmere", "Cosmere"),
                        series("mistborn", "Mistborn", "cosmere"),
                        series("era1", "Mistborn Era 1", "mistborn"),
                        series("dune", "Dune"),
                    ),
                memberships = emptyList(),
            )

        test("each membership becomes a line carrying its whole path, root first") {
            val paths = bookSeriesPaths(listOf(BookSeries("era1", "Mistborn Era 1", 1.0)), hierarchy)

            paths shouldBe
                listOf(
                    BookSeriesPath(
                        seriesId = "era1",
                        seriesName = "Mistborn Era 1",
                        sequence = "1",
                        ancestors = listOf(SeriesCrumb("cosmere", "Cosmere"), SeriesCrumb("mistborn", "Mistborn")),
                    ),
                )
        }

        test("a book in several series gets one line per series, in membership order") {
            val paths =
                bookSeriesPaths(
                    listOf(BookSeries("dune", "Dune", 2.0), BookSeries("era1", "Mistborn Era 1", 1.5)),
                    hierarchy,
                )

            paths.map { it.seriesId } shouldBe listOf("dune", "era1")
            paths[0].ancestors.shouldBeEmpty()
            paths[1].sequence shouldBe "1.5"
        }

        test("a membership in an ancestor of another membership is dropped — the deeper path already says it") {
            val paths =
                bookSeriesPaths(
                    listOf(BookSeries("cosmere", "Cosmere", 4.0), BookSeries("era1", "Mistborn Era 1", 1.0)),
                    hierarchy,
                )

            paths.map { it.seriesId } shouldBe listOf("era1")
        }

        test("a series the hierarchy doesn't know yet still gets a line, with no path") {
            val paths = bookSeriesPaths(listOf(BookSeries("new", "Brand New", null)), hierarchy)

            paths.single().ancestors.shouldBeEmpty()
            paths.single().sequence shouldBe null
        }
    })
