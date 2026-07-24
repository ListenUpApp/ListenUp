package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.client.data.local.db.BookEntity
import com.calypsan.listenup.client.data.local.db.BookSearchResult
import com.calypsan.listenup.client.data.local.db.ContributorEntity
import com.calypsan.listenup.client.data.local.db.SearchDao
import com.calypsan.listenup.client.data.local.db.SeriesEntity
import com.calypsan.listenup.client.domain.model.SearchHitType
import com.calypsan.listenup.client.domain.repository.ImageStorage
import dev.mokkery.MockMode
import dev.mokkery.answering.returns
import dev.mokkery.answering.throws
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/**
 * Tests for [SearchRepositoryImpl] — local FTS5 search.
 *
 * Search is served entirely from the local Room index (the server runs the same FTS5 algorithm, so a
 * client holding the library locally has nothing to gain from a round-trip). These tests verify the
 * blank-query short-circuit, the federated mapping across the four hit types, that results are not
 * flagged as a degraded "offline" fallback, and that one failing per-type query can't sink the rest.
 */
class SearchRepositoryTest :
    FunSpec({

        fun bookResult(
            id: String = "book-1",
            title: String = "Test Book",
            authorName: String? = "Test Author",
            coverHash: String? = null,
        ) = BookSearchResult(
            book =
                BookEntity(
                    id = BookId(id),
                    libraryId = LibraryId("test-library"),
                    folderId = FolderId("test-folder"),
                    title = title,
                    subtitle = null,
                    coverHash = coverHash,
                    totalDuration = 3_600_000,
                    description = null,
                    publishYear = null,
                    createdAt = Timestamp(0),
                    updatedAt = Timestamp(0),
                ),
            authorName = authorName,
        )

        fun contributor(
            id: String = "c1",
            name: String = "Author",
        ) = ContributorEntity(
            id = ContributorId(id),
            name = name,
            description = null,
            imagePath = null,
            createdAt = Timestamp(0),
            updatedAt = Timestamp(0),
        )

        fun series(
            id: String = "s1",
            name: String = "Series",
        ) = SeriesEntity(
            id = SeriesId(id),
            name = name,
            description = null,
            createdAt = Timestamp(0),
            updatedAt = Timestamp(0),
        )

        fun repository(configure: SearchDao.() -> Unit): SearchRepositoryImpl {
            val imageStorage = mock<ImageStorage> { every { exists(any()) } returns false }
            val searchDao = mock<SearchDao>(MockMode.autoUnit) { configure() }
            return SearchRepositoryImpl(searchDao, imageStorage)
        }

        test("blank query short-circuits to an empty result without touching the index") {
            runTest {
                val result = repository { }.search("   ")
                result.total shouldBe 0
                result.hits.shouldBeEmpty()
            }
        }

        test("federates across books, contributors, series, and tags") {
            runTest {
                val repo =
                    repository {
                        everySuspend { searchBooks(any(), any()) } returns listOf(bookResult(title = "Mistborn"))
                        everySuspend { searchContributors(any(), any()) } returns listOf(contributor(name = "Sanderson"))
                        everySuspend { searchSeries(any(), any()) } returns listOf(series(name = "Stormlight"))
                        everySuspend { searchTags(any(), any()) } returns emptyList()
                    }

                val result = repo.search("brandon")

                result.hits.map { it.type } shouldContainExactlyInAnyOrder
                    listOf(SearchHitType.BOOK, SearchHitType.CONTRIBUTOR, SearchHitType.SERIES)
                result.hits.first { it.type == SearchHitType.BOOK }.name shouldBe "Mistborn"
            }
        }

        test("local results are not flagged as an offline fallback") {
            runTest {
                val repo =
                    repository {
                        everySuspend { searchBooks(any(), any()) } returns listOf(bookResult())
                        everySuspend { searchContributors(any(), any()) } returns emptyList()
                        everySuspend { searchSeries(any(), any()) } returns emptyList()
                        everySuspend { searchTags(any(), any()) } returns emptyList()
                    }

                repo.search("test").isOfflineResult.shouldBeFalse()
            }
        }

        test("book hits carry coverHash through to the SearchHit") {
            runTest {
                val repo =
                    repository {
                        everySuspend { searchBooks(any(), any()) } returns
                            listOf(bookResult(title = "Mistborn", coverHash = "abc123"))
                        everySuspend { searchContributors(any(), any()) } returns emptyList()
                        everySuspend { searchSeries(any(), any()) } returns emptyList()
                        everySuspend { searchTags(any(), any()) } returns emptyList()
                    }

                val result = repo.search("brandon", types = listOf(SearchHitType.BOOK))

                result.hits.first { it.type == SearchHitType.BOOK }.coverHash shouldBe "abc123"
            }
        }

        test("a failing per-type query does not sink the rest of the search") {
            runTest {
                val repo =
                    repository {
                        everySuspend { searchBooks(any(), any()) } throws RuntimeException("FTS boom")
                        everySuspend { searchContributors(any(), any()) } returns listOf(contributor(name = "Survivor"))
                        everySuspend { searchSeries(any(), any()) } returns emptyList()
                        everySuspend { searchTags(any(), any()) } returns emptyList()
                    }

                val result = repo.search("test", types = listOf(SearchHitType.BOOK, SearchHitType.CONTRIBUTOR))

                result.hits.map { it.name } shouldBe listOf("Survivor")
            }
        }
    })
