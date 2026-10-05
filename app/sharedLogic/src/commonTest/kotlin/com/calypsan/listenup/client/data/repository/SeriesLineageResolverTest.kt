package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.domain.model.BookContributor
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.BookSeries
import com.calypsan.listenup.client.domain.model.Series
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.Timestamp
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe

class SeriesLineageResolverTest :
    FunSpec({
        fun series(
            id: String,
            parent: String? = null,
            position: Int? = null,
        ) = Series(id = SeriesId(id), name = id, parentId = parent?.let(::SeriesId), parentPosition = position)

        fun book(
            id: String,
            vararg memberships: Pair<String, Double?>,
        ) = BookListItem(
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
            series = memberships.map { (seriesId, sequence) -> BookSeries(seriesId, seriesId, sequence) },
        )

        val all =
            listOf(
                series("cosmere"),
                series("mistborn", parent = "cosmere", position = 0),
                series("stormlight", parent = "cosmere", position = 1),
                series("era1", parent = "mistborn", position = 0),
                series("narnia"),
            )
        val resolver = SeriesLineageResolver(all)

        test("the subtree is what the book query must cover") {
            resolver.subtreeOf("cosmere") shouldContainExactlyInAnyOrder listOf("cosmere", "mistborn", "stormlight", "era1")
            resolver.hasSubSeries("cosmere") shouldBe true
            resolver.hasSubSeries("narnia") shouldBe false
        }

        test("a parent resolves its sub-series in order, each with its own subtree's books") {
            val books =
                listOf(
                    book("warbreaker", "cosmere" to null),
                    book("way-of-kings", "stormlight" to 1.0),
                    book("well", "era1" to 2.0),
                    book("final-empire", "era1" to 1.0, "cosmere" to 9.0),
                )

            val lineage = resolver.resolve("cosmere", books)

            lineage.ancestors.shouldBeEmpty()
            lineage.children.map { it.series.id.value } shouldContainExactly listOf("mistborn", "stormlight")
            lineage.children[0].bookIds shouldContainExactly listOf("final-empire", "well")
            lineage.children[1].bookIds shouldContainExactly listOf("way-of-kings")
            lineage.subtreeBooks.map { it.id.value } shouldContainExactly
                listOf("final-empire", "well", "way-of-kings", "warbreaker")
        }

        test("every series of the subtree knows the books first reached there, nested as the tree is") {
            val books =
                listOf(
                    book("warbreaker", "cosmere" to null),
                    book("way-of-kings", "stormlight" to 1.0),
                    book("well", "era1" to 2.0),
                    book("final-empire", "era1" to 1.0, "cosmere" to 9.0),
                    book("secret-history", "mistborn" to 3.5),
                )

            val lineage = resolver.resolve("cosmere", books)

            lineage.ownBookIds shouldContainExactly listOf("warbreaker")
            val mistborn = lineage.children[0]
            mistborn.ownBookIds shouldContainExactly listOf("secret-history")
            mistborn.children.map { it.series.id.value } shouldContainExactly listOf("era1")
            mistborn.children[0].ownBookIds shouldContainExactly listOf("final-empire", "well")
            mistborn.children[0].bookIds shouldContainExactly listOf("final-empire", "well")
            lineage.children[1].children.shouldBeEmpty()
            lineage.children[1].ownBookIds shouldContainExactly listOf("way-of-kings")
        }

        test("books that tie on sequence, or have none, order by title whatever order the rows arrive in") {
            val books =
                listOf(
                    book("id-1", "cosmere" to null).copy(title = "Warbreaker"),
                    book("id-2", "cosmere" to null).copy(title = "Elantris"),
                    book("id-3", "era1" to 1.0).copy(title = "The Well of Ascension"),
                    book("id-4", "era1" to 1.0).copy(title = "The Final Empire"),
                )
            val expected = listOf("id-4", "id-3", "id-2", "id-1")

            resolver.resolve("cosmere", books).subtreeBooks.map { it.id.value } shouldContainExactly expected
            resolver.resolve("cosmere", books.reversed()).subtreeBooks.map { it.id.value } shouldContainExactly expected
            resolver.resolve("cosmere", books.reversed()).children[0].bookIds shouldContainExactly listOf("id-4", "id-3")
        }

        test("a nested series lists its ancestors root first") {
            resolver.resolve("era1", emptyList()).ancestors.map { it.id.value } shouldContainExactly
                listOf("cosmere", "mistborn")
        }

        test("a flat series has no lineage") {
            val lineage = resolver.resolve("narnia", listOf(book("lion", "narnia" to 1.0)))
            lineage.ancestors.shouldBeEmpty()
            lineage.children.shouldBeEmpty()
            lineage.subtreeBooks.shouldBeEmpty()
        }

        test("memberships outside the subtree are ignored") {
            val lineage = resolver.resolve("mistborn", listOf(book("final-empire", "era1" to 1.0, "narnia" to 1.0)))
            lineage.subtreeBooks.map { it.id.value } shouldContainExactly listOf("final-empire")
        }
    })
