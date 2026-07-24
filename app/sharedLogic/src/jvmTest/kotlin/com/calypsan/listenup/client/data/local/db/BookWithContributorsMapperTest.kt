package com.calypsan.listenup.client.data.local.db

import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.client.domain.model.BookContributor
import com.calypsan.listenup.client.domain.model.Genre
import com.calypsan.listenup.client.domain.model.Tag
import com.calypsan.listenup.client.domain.repository.ImageStorage
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Golden-output tests for the canonical mappers in [BookEntityMapper].
 *
 * These tests pin canonical mapper behavior so a future regression fails
 * immediately. Covers [BookWithContributors.toListItem] and
 * [BookWithContributors.toDetail].
 */
class BookWithContributorsMapperTest :
    FunSpec({
        val bookId = BookId("book-1")
        val createdAt = Timestamp(1_700_000_000_000L)
        val updatedAt = Timestamp(1_700_000_001_000L)

        fun makeBook() =
            BookEntity(
                id = bookId,
                libraryId = LibraryId("test-library"),
                folderId = FolderId("test-folder"),
                title = "The Way of Kings",
                sortTitle = "Way of Kings, The",
                subtitle = "The Stormlight Archive",
                coverHash = null,
                totalDuration = 72_000_000L,
                description = "A fantasy epic.",
                publishYear = 2010,
                publisher = "Tor Books",
                language = "en",
                isbn = "978-0-7653-2637-9",
                asin = "B003P2WO5E",
                abridged = false,
                createdAt = createdAt,
                updatedAt = updatedAt,
            )

        val authorId = ContributorId("contrib-author")
        val narratorId = ContributorId("contrib-narrator")
        val narrator2Id = ContributorId("contrib-narrator2")
        val seriesId = SeriesId("series-1")

        fun makeContributors() =
            listOf(
                ContributorEntity(
                    id = authorId,
                    name = "Brandon Sanderson",
                    sortName = "Sanderson, Brandon",
                    asin = null,
                    description = null,
                    imagePath = null,
                    createdAt = createdAt,
                    updatedAt = updatedAt,
                ),
                ContributorEntity(
                    id = narratorId,
                    name = "Michael Kramer",
                    sortName = null,
                    asin = null,
                    description = null,
                    imagePath = null,
                    createdAt = createdAt,
                    updatedAt = updatedAt,
                ),
                ContributorEntity(
                    id = narrator2Id,
                    name = "Kate Reading",
                    sortName = null,
                    asin = null,
                    description = null,
                    imagePath = null,
                    createdAt = createdAt,
                    updatedAt = updatedAt,
                ),
            )

        fun makeContributorRoles() =
            listOf(
                BookContributorCrossRef(
                    bookId = bookId,
                    contributorId = authorId,
                    role = "author",
                    creditedAs = null,
                ),
                BookContributorCrossRef(
                    bookId = bookId,
                    contributorId = narratorId,
                    role = "narrator",
                    creditedAs = null,
                ),
                BookContributorCrossRef(
                    bookId = bookId,
                    contributorId = narrator2Id,
                    role = "narrator",
                    creditedAs = null,
                ),
            )

        fun makeSeries() =
            listOf(
                SeriesEntity(
                    id = seriesId,
                    name = "The Stormlight Archive",
                    description = null,
                    createdAt = createdAt,
                    updatedAt = updatedAt,
                ),
            )

        fun makeSeriesSequences() =
            listOf(
                BookSeriesCrossRef(
                    bookId = bookId,
                    seriesId = seriesId,
                    sequence = "1",
                ),
            )

        test("toListItem returns BookListItem with authors and narrators populated, no detail-only fields") {
            val imageStorage = mock<ImageStorage>()
            every { imageStorage.exists(any()) } returns false

            val bookWithContributors =
                BookWithContributors(
                    book = makeBook(),
                    contributors = makeContributors(),
                    contributorRoles = makeContributorRoles(),
                    series = makeSeries(),
                    seriesSequences = makeSeriesSequences(),
                )

            val result = bookWithContributors.toListItem(imageStorage)

            result.id shouldBe bookId
            result.title shouldBe "The Way of Kings"
            // Authors
            result.authors shouldBe listOf(BookContributor(id = "contrib-author", name = "Brandon Sanderson"))
            // Narrators
            result.narrators shouldBe
                listOf(
                    BookContributor(id = "contrib-narrator", name = "Michael Kramer"),
                    BookContributor(id = "contrib-narrator2", name = "Kate Reading"),
                )
            // Series populated from junction.
            result.series.size shouldBe 1
            result.series.first().seriesName shouldBe "The Stormlight Archive"
        }

        test("toDetail computes allContributors via dedup-and-role-aggregation, genres+tags forwarded as-is") {
            val imageStorage = mock<ImageStorage>()
            every { imageStorage.exists(any()) } returns false

            val genres = listOf(Genre(id = "genre-1", name = "Fantasy", slug = "fantasy", path = "/fantasy"))
            val tags = listOf(Tag(id = "tag-1", name = "Epic", slug = "epic"))

            val bookWithContributors =
                BookWithContributors(
                    book = makeBook(),
                    contributors = makeContributors(),
                    contributorRoles = makeContributorRoles(),
                    series = makeSeries(),
                    seriesSequences = makeSeriesSequences(),
                )

            val result = bookWithContributors.toDetail(imageStorage, genres, tags, emptyList())

            // allContributors: every distinct contributor with all roles grouped.
            // The fixture has authorId (role: author), narratorId (role: narrator), narrator2Id (role: narrator) — 3 distinct contributors.
            result.allContributors.size shouldBe 3
            val sandersonRoles = result.allContributors.first { it.id == "contrib-author" }.roles
            sandersonRoles shouldBe listOf("author")
            val kramerRoles = result.allContributors.first { it.id == "contrib-narrator" }.roles
            kramerRoles shouldBe listOf("narrator")
            val readingRoles = result.allContributors.first { it.id == "contrib-narrator2" }.roles
            readingRoles shouldBe listOf("narrator")

            // Genres + tags forwarded verbatim.
            result.genres shouldBe genres
            result.tags shouldBe tags
        }

        test("toDetail allContributors handles same person in multiple roles by grouping") {
            val imageStorage = mock<ImageStorage>()
            every { imageStorage.exists(any()) } returns false

            // Sanderson is BOTH author and narrator on this book.
            val authorAndNarratorRoles =
                listOf(
                    BookContributorCrossRef(bookId, authorId, role = "author", creditedAs = null),
                    BookContributorCrossRef(bookId, authorId, role = "narrator", creditedAs = null),
                )
            val singleContributor = makeContributors().first { it.id == authorId }

            val bookWithContributors =
                BookWithContributors(
                    book = makeBook(),
                    contributors = listOf(singleContributor),
                    contributorRoles = authorAndNarratorRoles,
                    series = emptyList(),
                    seriesSequences = emptyList(),
                )

            val result = bookWithContributors.toDetail(imageStorage, emptyList(), emptyList(), emptyList())

            result.allContributors.size shouldBe 1
            result.allContributors.first().roles shouldBe listOf("author", "narrator")
        }

        test("toDetail allContributors prefers creditedAs over canonical contributor name when creditedAs is non-null") {
            val imageStorage = mock<ImageStorage>()
            every { imageStorage.exists(any()) } returns false

            // Same Sanderson contributor, but the cross-ref carries a different attribution
            // (e.g., the book was originally credited to the author's pen-name before merging).
            val rolesWithCreditedAs =
                listOf(
                    BookContributorCrossRef(
                        bookId = bookId,
                        contributorId = authorId,
                        role = "author",
                        creditedAs = "B. Sanderson (originally credited)",
                    ),
                )

            val bookWithContributors =
                BookWithContributors(
                    book = makeBook(),
                    contributors = listOf(makeContributors().first { it.id == authorId }),
                    contributorRoles = rolesWithCreditedAs,
                    series = emptyList(),
                    seriesSequences = emptyList(),
                )

            val result = bookWithContributors.toDetail(imageStorage, emptyList(), emptyList(), emptyList())

            result.allContributors.size shouldBe 1
            result.allContributors.first().name shouldBe "B. Sanderson (originally credited)"
        }

        test("toDetail allContributors falls back to canonical contributor name when creditedAs is null") {
            val imageStorage = mock<ImageStorage>()
            every { imageStorage.exists(any()) } returns false

            val rolesWithoutCreditedAs =
                listOf(
                    BookContributorCrossRef(
                        bookId = bookId,
                        contributorId = authorId,
                        role = "author",
                        creditedAs = null,
                    ),
                )

            val bookWithContributors =
                BookWithContributors(
                    book = makeBook(),
                    contributors = listOf(makeContributors().first { it.id == authorId }),
                    contributorRoles = rolesWithoutCreditedAs,
                    series = emptyList(),
                    seriesSequences = emptyList(),
                )

            val result = bookWithContributors.toDetail(imageStorage, emptyList(), emptyList(), emptyList())

            result.allContributors.first().name shouldBe "Brandon Sanderson"
        }

        test("toListItem carries libraryId and folderId from BookEntity") {
            val imageStorage = mock<ImageStorage>()
            every { imageStorage.exists(any()) } returns false

            val bookWithContributors =
                BookWithContributors(
                    book = makeBook(),
                    contributors = emptyList(),
                    contributorRoles = emptyList(),
                    series = emptyList(),
                    seriesSequences = emptyList(),
                )

            val result = bookWithContributors.toListItem(imageStorage)

            result.libraryId shouldBe LibraryId("test-library")
            result.folderId shouldBe FolderId("test-folder")
        }

        test("toDetail carries libraryId and folderId from BookEntity") {
            val imageStorage = mock<ImageStorage>()
            every { imageStorage.exists(any()) } returns false

            val bookWithContributors =
                BookWithContributors(
                    book = makeBook(),
                    contributors = emptyList(),
                    contributorRoles = emptyList(),
                    series = emptyList(),
                    seriesSequences = emptyList(),
                )

            val result = bookWithContributors.toDetail(imageStorage, emptyList(), emptyList(), emptyList())

            result.libraryId shouldBe LibraryId("test-library")
            result.folderId shouldBe FolderId("test-folder")
        }

        test("toDetail then toListItem equals toListItem for same input") {
            val imageStorage = mock<ImageStorage>()
            every { imageStorage.exists(any()) } returns false

            val bookWithContributors =
                BookWithContributors(
                    book = makeBook(),
                    contributors = makeContributors(),
                    contributorRoles = makeContributorRoles(),
                    series = makeSeries(),
                    seriesSequences = makeSeriesSequences(),
                )

            val viaDetail = bookWithContributors.toDetail(imageStorage, emptyList(), emptyList(), emptyList()).toListItem()
            val direct = bookWithContributors.toListItem(imageStorage)

            direct shouldBe viaDetail
        }
    })
