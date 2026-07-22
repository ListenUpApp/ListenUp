package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.BookEntity
import com.calypsan.listenup.client.data.local.db.BookIdNameRow
import com.calypsan.listenup.client.data.local.db.ContributorDao
import com.calypsan.listenup.client.data.local.db.ContributorEntity
import com.calypsan.listenup.client.data.local.db.ContributorWithAliases
import com.calypsan.listenup.client.data.local.db.SearchDao
import com.calypsan.listenup.client.data.local.db.SeriesDao
import com.calypsan.listenup.client.data.local.db.SeriesEntity
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import kotlinx.coroutines.test.runTest

/**
 * Tests for FtsPopulator.
 *
 * Tests cover:
 * - Full FTS rebuild (books, contributors, series)
 * - Individual table rebuilds
 * - Exception handling for individual inserts
 *
 * Uses Mokkery for mocking all DAOs.
 */
class FtsPopulatorTest :
    FunSpec({
        // ========== Test Fixtures ==========

        class TestFixture {
            val bookDao: BookDao = mock()
            val contributorDao: ContributorDao = mock()
            val seriesDao: SeriesDao = mock()
            val searchDao: SearchDao = mock()

            // Inline fake that executes the block directly — no DB needed in mock tests.
            val transactionRunner: TransactionRunner =
                object : TransactionRunner {
                    override suspend fun <R> atomically(block: suspend () -> R): R = block()
                }

            fun build(): FtsPopulator =
                FtsPopulator(
                    bookDao = bookDao,
                    contributorDao = contributorDao,
                    seriesDao = seriesDao,
                    searchDao = searchDao,
                    transactionRunner = transactionRunner,
                )
        }

        fun createFixture(): TestFixture {
            val fixture = TestFixture()

            // Default stubs - empty lists
            everySuspend { fixture.bookDao.getAllLive() } returns emptyList()
            everySuspend { fixture.contributorDao.getAll() } returns emptyList()
            everySuspend { fixture.contributorDao.getAllWithAliases() } returns emptyList()
            everySuspend { fixture.seriesDao.getAll() } returns emptyList()

            // Stub clear and insert operations
            everySuspend { fixture.searchDao.clearBooksFts() } returns Unit
            everySuspend { fixture.searchDao.clearContributorsFts() } returns Unit
            everySuspend { fixture.searchDao.clearSeriesFts() } returns Unit

            // Batch query stubs (the new API — no per-book queries)
            everySuspend { fixture.searchDao.getAllPrimaryAuthorNames() } returns emptyList()
            everySuspend { fixture.searchDao.getAllPrimaryNarratorNames() } returns emptyList()
            everySuspend { fixture.searchDao.getAllSeriesNamesGrouped() } returns emptyList()
            everySuspend { fixture.searchDao.getAllGenreNamesGrouped() } returns emptyList()

            everySuspend { fixture.searchDao.insertBookFts(any(), any(), any(), any(), any(), any(), any(), any()) } returns
                Unit
            everySuspend { fixture.searchDao.insertContributorFts(any(), any(), any(), any(), any()) } returns Unit
            everySuspend { fixture.searchDao.insertSeriesFts(any(), any(), any()) } returns Unit

            return fixture
        }

        // ========== Test Data Factories ==========

        fun createBookEntity(
            id: String = "book-1",
            title: String = "Test Book",
            subtitle: String? = null,
            description: String? = null,
        ): BookEntity =
            BookEntity(
                id = BookId(id),
                libraryId = LibraryId("test-library"),
                folderId = FolderId("test-folder"),
                title = title,
                subtitle = subtitle,
                coverHash = null,
                totalDuration = 3_600_000L,
                description = description,
                publishYear = 2024,
                createdAt = Timestamp(1704067200000L),
                updatedAt = Timestamp(1704067200000L),
            )

        fun createContributorEntity(
            id: String = "contributor-1",
            name: String = "Test Author",
            description: String? = null,
        ): ContributorEntity =
            ContributorEntity(
                id =
                    com.calypsan.listenup.core
                        .ContributorId(id),
                name = name,
                description = description,
                imagePath = null,
                createdAt = Timestamp(1704067200000L),
                updatedAt = Timestamp(1704067200000L),
            )

        fun createSeriesEntity(
            id: String = "series-1",
            name: String = "Test Series",
            description: String? = null,
        ): SeriesEntity =
            SeriesEntity(
                id =
                    com.calypsan.listenup.core
                        .SeriesId(id),
                name = name,
                description = description,
                createdAt = Timestamp(1704067200000L),
                updatedAt = Timestamp(1704067200000L),
            )

        // ========== Rebuild All Tests ==========

        test("rebuildAll clears all FTS tables") {
            runTest {
                // Given
                val fixture = createFixture()
                val ftsPopulator = fixture.build()

                // When
                ftsPopulator.rebuildAll()

                // Then
                verifySuspend { fixture.searchDao.clearBooksFts() }
                verifySuspend { fixture.searchDao.clearContributorsFts() }
                verifySuspend { fixture.searchDao.clearSeriesFts() }
            }
        }

        test("rebuildAll inserts all books into FTS") {
            runTest {
                // Given
                val fixture = createFixture()
                val book1 = createBookEntity(id = "book-1", title = "Book One")
                val book2 = createBookEntity(id = "book-2", title = "Book Two")

                everySuspend { fixture.bookDao.getAllLive() } returns listOf(book1, book2)
                val ftsPopulator = fixture.build()

                // When
                ftsPopulator.rebuildAll()

                // Then
                verifySuspend { fixture.searchDao.insertBookFts("book-1", "Book One", null, null, null, null, null, null) }
                verifySuspend { fixture.searchDao.insertBookFts("book-2", "Book Two", null, null, null, null, null, null) }
            }
        }

        test("rebuildAll inserts all contributors into FTS") {
            runTest {
                // Given
                val fixture = createFixture()
                val contributor1 = createContributorEntity(id = "contributor-1", name = "Author One")
                val contributor2 = createContributorEntity(id = "contributor-2", name = "Author Two")

                everySuspend { fixture.contributorDao.getAllWithAliases() } returns
                    listOf(
                        ContributorWithAliases(contributor1, emptyList()),
                        ContributorWithAliases(contributor2, emptyList()),
                    )
                val ftsPopulator = fixture.build()

                // When
                ftsPopulator.rebuildAll()

                // Then
                verifySuspend { fixture.searchDao.insertContributorFts("contributor-1", "Author One", null, null, null) }
                verifySuspend { fixture.searchDao.insertContributorFts("contributor-2", "Author Two", null, null, null) }
            }
        }

        test("rebuildAll indexes contributor sortName and aliases so pen names are searchable") {
            runTest {
                val fixture = createFixture()
                val contributor =
                    createContributorEntity(id = "c1", name = "J.K. Rowling").copy(sortName = "Rowling, J.K.")
                everySuspend { fixture.contributorDao.getAllWithAliases() } returns
                    listOf(ContributorWithAliases(contributor, listOf("Robert Galbraith", "Newt Scamander")))
                val ftsPopulator = fixture.build()

                ftsPopulator.rebuildAll()

                // Aliases are space-joined into the FTS `aliases` column so a search for a pen name hits.
                verifySuspend {
                    fixture.searchDao.insertContributorFts(
                        "c1",
                        "J.K. Rowling",
                        "Rowling, J.K.",
                        "Robert Galbraith Newt Scamander",
                        null,
                    )
                }
            }
        }

        test("rebuildAll inserts all series into FTS") {
            runTest {
                // Given
                val fixture = createFixture()
                val series1 = createSeriesEntity(id = "series-1", name = "Series One")
                val series2 = createSeriesEntity(id = "series-2", name = "Series Two")

                everySuspend { fixture.seriesDao.getAll() } returns listOf(series1, series2)
                val ftsPopulator = fixture.build()

                // When
                ftsPopulator.rebuildAll()

                // Then
                verifySuspend { fixture.searchDao.insertSeriesFts("series-1", "Series One", null) }
                verifySuspend { fixture.searchDao.insertSeriesFts("series-2", "Series Two", null) }
            }
        }

        test("rebuildAll includes book subtitle and description") {
            runTest {
                // Given
                val fixture = createFixture()
                val book =
                    createBookEntity(
                        id = "book-1",
                        title = "Main Title",
                        subtitle = "A Great Subtitle",
                        description = "This is a description",
                    )

                everySuspend { fixture.bookDao.getAllLive() } returns listOf(book)
                val ftsPopulator = fixture.build()

                // When
                ftsPopulator.rebuildAll()

                // Then
                verifySuspend {
                    fixture.searchDao.insertBookFts(
                        "book-1",
                        "Main Title",
                        "A Great Subtitle",
                        "This is a description",
                        null,
                        null,
                        null,
                        null,
                    )
                }
            }
        }

        test("rebuildAll includes author and narrator names from batch lookup") {
            runTest {
                // Given
                val fixture = createFixture()
                val book = createBookEntity(id = "book-1", title = "Test Book")

                everySuspend { fixture.bookDao.getAllLive() } returns listOf(book)
                everySuspend { fixture.searchDao.getAllPrimaryAuthorNames() } returns
                    listOf(BookIdNameRow(bookId = "book-1", authorName = "John Author"))
                everySuspend { fixture.searchDao.getAllPrimaryNarratorNames() } returns
                    listOf(BookIdNameRow(bookId = "book-1", authorName = "Jane Narrator"))
                val ftsPopulator = fixture.build()

                // When
                ftsPopulator.rebuildAll()

                // Then
                verifySuspend {
                    fixture.searchDao.insertBookFts(
                        "book-1",
                        "Test Book",
                        null,
                        null,
                        "John Author",
                        "Jane Narrator",
                        null,
                        null,
                    )
                }
            }
        }

        test("rebuildAll includes genres in FTS via batch lookup") {
            runTest {
                // Given — genre names come from getAllGenreNamesGrouped batch query
                val fixture = createFixture()
                val book =
                    createBookEntity(
                        id = "book-1",
                        title = "Fantasy Book",
                    )

                everySuspend { fixture.bookDao.getAllLive() } returns listOf(book)
                everySuspend { fixture.searchDao.getAllGenreNamesGrouped() } returns
                    listOf(BookIdNameRow(bookId = "book-1", authorName = "Fantasy, Adventure"))
                val ftsPopulator = fixture.build()

                // When
                ftsPopulator.rebuildAll()

                // Then — both series and genre names come from batch queries, not per-book calls
                verifySuspend {
                    fixture.searchDao.insertBookFts(
                        "book-1",
                        "Fantasy Book",
                        null,
                        null,
                        null,
                        null,
                        null, // Series comes from batch query
                        "Fantasy, Adventure", // Genres come from batch query
                    )
                }
            }
        }

        test("rebuildAll includes contributor description") {
            runTest {
                // Given
                val fixture = createFixture()
                val contributor =
                    createContributorEntity(
                        id = "contributor-1",
                        name = "Famous Author",
                        description = "An award-winning author",
                    )

                everySuspend { fixture.contributorDao.getAllWithAliases() } returns
                    listOf(ContributorWithAliases(contributor, emptyList()))
                val ftsPopulator = fixture.build()

                // When
                ftsPopulator.rebuildAll()

                // Then
                verifySuspend {
                    fixture.searchDao.insertContributorFts(
                        "contributor-1",
                        "Famous Author",
                        null,
                        null,
                        "An award-winning author",
                    )
                }
            }
        }

        test("rebuildAll includes series description") {
            runTest {
                // Given
                val fixture = createFixture()
                val series =
                    createSeriesEntity(
                        id = "series-1",
                        name = "Epic Series",
                        description = "An epic fantasy saga",
                    )

                everySuspend { fixture.seriesDao.getAll() } returns listOf(series)
                val ftsPopulator = fixture.build()

                // When
                ftsPopulator.rebuildAll()

                // Then
                verifySuspend {
                    fixture.searchDao.insertSeriesFts(
                        "series-1",
                        "Epic Series",
                        "An epic fantasy saga",
                    )
                }
            }
        }

        // ========== Incremental refresh: threshold fallback ==========

        test("refreshSince falls back to a full rebuild when the delta exceeds the threshold") {
            runTest {
                // Given — 501 changed book ids (over FTS_FULL_REBUILD_THRESHOLD = 500)
                val fixture = createFixture()
                val changedIds = (1..501).map { "book-$it" }
                everySuspend { fixture.searchDao.bookIdsChangedSince(any()) } returns changedIds
                everySuspend { fixture.searchDao.bookIdsWithContributorsChangedSince(any()) } returns emptyList()
                everySuspend { fixture.searchDao.bookIdsWithSeriesChangedSince(any()) } returns emptyList()
                everySuspend { fixture.searchDao.bookIdsWithGenresChangedSince(any()) } returns emptyList()
                everySuspend { fixture.searchDao.countContributorsChangedSince(any()) } returns 0
                everySuspend { fixture.searchDao.countSeriesChangedSince(any()) } returns 0
                val ftsPopulator = fixture.build()

                // When
                ftsPopulator.refreshSince(SearchIndexWatermark(0, 0, 0, 0))

                // Then — the full rebuild path ran (proven by the books_fts clear it performs)
                verifySuspend { fixture.searchDao.clearBooksFts() }
            }
        }

        // ========== Empty Data Tests ==========

        test("rebuildAll handles empty books list") {
            runTest {
                // Given
                val fixture = createFixture()
                everySuspend { fixture.bookDao.getAllLive() } returns emptyList()
                val ftsPopulator = fixture.build()

                // When
                ftsPopulator.rebuildAll()

                // Then - should clear but not insert
                verifySuspend { fixture.searchDao.clearBooksFts() }
            }
        }

        test("rebuildAll handles empty contributors list") {
            runTest {
                // Given
                val fixture = createFixture()
                everySuspend { fixture.contributorDao.getAll() } returns emptyList()
                val ftsPopulator = fixture.build()

                // When
                ftsPopulator.rebuildAll()

                // Then - should clear but not insert
                verifySuspend { fixture.searchDao.clearContributorsFts() }
            }
        }

        test("rebuildAll handles empty series list") {
            runTest {
                // Given
                val fixture = createFixture()
                everySuspend { fixture.seriesDao.getAll() } returns emptyList()
                val ftsPopulator = fixture.build()

                // When
                ftsPopulator.rebuildAll()

                // Then - should clear but not insert
                verifySuspend { fixture.searchDao.clearSeriesFts() }
            }
        }
    })
