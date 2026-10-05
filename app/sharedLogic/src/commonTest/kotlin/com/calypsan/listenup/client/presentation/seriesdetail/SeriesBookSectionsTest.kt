package com.calypsan.listenup.client.presentation.seriesdetail

import com.calypsan.listenup.client.domain.model.BookContributor
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.Series
import com.calypsan.listenup.client.domain.model.SeriesChild
import com.calypsan.listenup.client.domain.model.SeriesLineage
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.Timestamp
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class SeriesBookSectionsTest :
    FunSpec({
        fun book(id: String) =
            BookListItem(
                id = BookId(id),
                libraryId = LibraryId("lib"),
                folderId = FolderId("folder"),
                title = id,
                subtitle = null,
                authors = listOf(BookContributor(id = "a", name = "Author", roles = listOf("Author"))),
                narrators = emptyList(),
                duration = 1_000L,
                coverPath = null,
                addedAt = Timestamp(0),
                updatedAt = Timestamp(0),
            )

        fun series(
            id: String,
            name: String,
        ) = Series(id = SeriesId(id), name = name)

        val books = listOf("fe", "well", "alloy", "sh", "wok", "elantris", "warbreaker").associateWith(::book)

        // Cosmere ─┬─ Mistborn ─┬─ Era 1 (fe, well)
        //          │            ├─ Era 2 (alloy)
        //          │            └─ own (sh)
        //          ├─ Stormlight (wok)
        //          ├─ Elantris (elantris)
        //          └─ own (warbreaker)
        val lineage =
            SeriesLineage(
                ancestors = emptyList(),
                children =
                    listOf(
                        SeriesChild(
                            series = series("mistborn", "Mistborn"),
                            bookIds = listOf("fe", "well", "alloy", "sh"),
                            ownBookIds = listOf("sh"),
                            children =
                                listOf(
                                    SeriesChild(series("era1", "Mistborn Era 1"), listOf("fe", "well"), listOf("fe", "well")),
                                    SeriesChild(series("era2", "Mistborn Era 2"), listOf("alloy"), listOf("alloy")),
                                ),
                        ),
                        SeriesChild(series("stormlight", "Stormlight"), listOf("wok"), listOf("wok")),
                        SeriesChild(series("elantris", "Elantris"), listOf("elantris"), listOf("elantris")),
                    ),
                subtreeBooks = books.values.toList(),
                ownBookIds = listOf("warbreaker"),
            )

        fun build(
            finished: Set<String> = emptySet(),
            overrides: Map<String, Boolean> = emptyMap(),
        ) = seriesBookSections(
            pageId = "cosmere",
            pageName = "Cosmere",
            lineage = lineage,
            flatBooks = emptyList(),
            booksById = books,
            finishedBookIds = finished.mapTo(HashSet(), ::BookId),
            expandOverrides = overrides,
        )

        test("a parent page groups its books by sub-series in tree order, its own books last") {
            val sections = build()

            sections.map { Triple(it.kind, it.title, it.depth) } shouldContainExactly
                listOf(
                    Triple(SeriesSectionKind.SUB_SERIES, "Mistborn", 1),
                    Triple(SeriesSectionKind.SUB_SERIES, "Mistborn Era 1", 2),
                    Triple(SeriesSectionKind.SUB_SERIES, "Mistborn Era 2", 2),
                    Triple(SeriesSectionKind.OWN_BOOKS, "Mistborn", 2),
                    Triple(SeriesSectionKind.SUB_SERIES, "Stormlight", 1),
                    Triple(SeriesSectionKind.SUB_SERIES, "Elantris", 1),
                    Triple(SeriesSectionKind.OWN_BOOKS, "Cosmere", 1),
                )
            sections[0].books shouldBe emptyList()
            sections[0].bookCount shouldBe 4
            sections[1].books.map { it.id.value } shouldContainExactly listOf("fe", "well")
            sections[1].path shouldContainExactly listOf("Mistborn", "Mistborn Era 1")
            sections.last().books.map { it.id.value } shouldContainExactly listOf("warbreaker")
        }

        test("a fully finished sub-series starts collapsed, and its sub-sections are hidden with it") {
            val sections = build(finished = setOf("fe", "well", "alloy", "sh", "elantris"))

            val mistborn = sections.first { it.seriesId == "mistborn" && it.kind == SeriesSectionKind.SUB_SERIES }
            mistborn.isCollapsed shouldBe true
            mistborn.finishedCount shouldBe 4
            sections.none { it.seriesId == "era1" } shouldBe true
            sections.first { it.seriesId == "elantris" }.isCollapsed shouldBe true
            sections.first { it.seriesId == "stormlight" }.isCollapsed shouldBe false
        }

        test("the reader's choice beats the default, both ways") {
            val sections =
                build(
                    finished = setOf("elantris"),
                    overrides = mapOf("elantris" to true, "stormlight" to false),
                )

            sections.first { it.seriesId == "elantris" }.isCollapsed shouldBe false
            sections.first { it.seriesId == "stormlight" }.isCollapsed shouldBe true
        }

        test("a flat page is one untitled run of its own books") {
            val flat =
                seriesBookSections(
                    pageId = "narnia",
                    pageName = "Narnia",
                    lineage = SeriesLineage.Flat,
                    flatBooks = listOf(book("lion"), book("caspian")),
                    booksById = emptyMap(),
                    finishedBookIds = emptySet(),
                    expandOverrides = emptyMap(),
                )

            flat.single().kind shouldBe SeriesSectionKind.OWN_BOOKS
            flat.single().books.map { it.id.value } shouldContainExactly listOf("lion", "caspian")
        }
    })
