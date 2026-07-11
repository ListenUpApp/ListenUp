package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.BookEntity
import com.calypsan.listenup.client.data.local.db.BookSeriesCrossRef
import com.calypsan.listenup.client.data.local.db.BookWithContributors
import com.calypsan.listenup.client.data.local.db.SearchDao
import com.calypsan.listenup.client.data.local.db.SeriesDao
import com.calypsan.listenup.client.data.local.db.SeriesEntity
import com.calypsan.listenup.api.SeriesService
import com.calypsan.listenup.api.sync.SeriesSyncPayload
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.SeriesApiContract
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.data.sync.SyncDomainHandler
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.NetworkMonitor
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.SeriesWithBooks as SeriesWithBooksRelation
import dev.mokkery.MockMode
import dev.mokkery.answering.calls
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verify
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

/**
 * Tests for SeriesRepositoryImpl.
 *
 * Verifies:
 * - All SeriesRepository interface methods are correctly delegated to SeriesDao
 * - SeriesEntity to Series domain model conversion
 * - Proper handling of empty results and null cases
 * - Flow emissions for reactive queries
 */
class SeriesRepositoryImplTest :
    FunSpec({

        // ========== Test Fixtures ==========

        fun createMockDao(): SeriesDao = mock<SeriesDao>(MockMode.autoUnit)

        fun createRepository(dao: SeriesDao): SeriesRepositoryImpl {
            val networkMonitor = mock<NetworkMonitor>()
            // Stub isOnline() to false so the cache-miss RPC path is never triggered
            // in these unit tests; the jvmTest RPC-fallback tests exercise that path.
            every { networkMonitor.isOnline() } returns false
            return SeriesRepositoryImpl(
                seriesDao = dao,
                bookDao = mock<BookDao>(MockMode.autoUnit),
                searchDao = mock<SearchDao>(MockMode.autoUnit),
                api = mock<SeriesApiContract>(),
                networkMonitor = networkMonitor,
                imageStorage = mock<ImageStorage>(),
                channel = RpcChannel.forTest(mock<SeriesService>(MockMode.autoUnit)),
                seriesSyncHandler = mock<SyncDomainHandler<SeriesSyncPayload>>(MockMode.autoUnit),
            )
        }

        fun createTestSeriesEntity(
            id: String = "series-1",
            name: String = "The Stormlight Archive",
            description: String? = null,
            createdAt: Long = 1000L,
            updatedAt: Long = 1000L,
        ): SeriesEntity =
            SeriesEntity(
                id =
                    com.calypsan.listenup.core
                        .SeriesId(id),
                name = name,
                description = description,
                createdAt = Timestamp(createdAt),
                updatedAt = Timestamp(updatedAt),
            )

        fun createRepositoryWithBookDao(
            seriesDao: SeriesDao,
            bookDao: BookDao,
        ): SeriesRepositoryImpl {
            val imageStorage = mock<ImageStorage>(MockMode.autoUnit)
            every { imageStorage.exists(any()) } returns false
            val networkMonitor = mock<NetworkMonitor>()
            every { networkMonitor.isOnline() } returns false
            return SeriesRepositoryImpl(
                seriesDao = seriesDao,
                bookDao = bookDao,
                searchDao = mock<SearchDao>(MockMode.autoUnit),
                api = mock<SeriesApiContract>(),
                networkMonitor = networkMonitor,
                imageStorage = imageStorage,
                channel = RpcChannel.forTest(mock<SeriesService>(MockMode.autoUnit)),
                seriesSyncHandler = mock<SyncDomainHandler<SeriesSyncPayload>>(MockMode.autoUnit),
            )
        }

        fun makeBookEntity(
            id: String,
            title: String,
        ): BookEntity =
            BookEntity(
                id = BookId(id),
                libraryId = LibraryId("test-library"),
                folderId = FolderId("test-folder"),
                title = title,
                sortTitle = title,
                subtitle = null,
                coverHash = null,
                coverBlurHash = null,
                totalDuration = 1_000L,
                description = null,
                publishYear = null,
                publisher = null,
                language = null,
                isbn = null,
                asin = null,
                abridged = false,
                createdAt = Timestamp(1_000L),
                updatedAt = Timestamp(1_000L),
            )

        fun makeBookWithContributors(book: BookEntity): BookWithContributors =
            BookWithContributors(
                book = book,
                contributors = emptyList(),
                contributorRoles = emptyList(),
                series = emptyList(),
                seriesSequences = emptyList(),
            )

        // ========== observeAll Tests ==========

        test("observeAll returns empty list when no series exist") {
            runTest {
                // Given
                val dao = createMockDao()
                every { dao.observeAll() } returns flowOf(emptyList())
                val repository = createRepository(dao)

                // When
                val result = repository.observeAll().first()

                // Then
                (result.isEmpty()) shouldBe true
            }
        }

        test("observeAll transforms entities to domain models") {
            runTest {
                // Given
                val entities =
                    listOf(
                        createTestSeriesEntity(
                            id = "series-1",
                            name = "The Stormlight Archive",
                            description = "Epic fantasy series",
                        ),
                        createTestSeriesEntity(
                            id = "series-2",
                            name = "Mistborn",
                            description = "Fantasy trilogy",
                        ),
                    )
                val dao = createMockDao()
                every { dao.observeAll() } returns flowOf(entities)
                val repository = createRepository(dao)

                // When
                val result = repository.observeAll().first()

                // Then
                result.size shouldBe 2
                result[0].id.value shouldBe "series-1"
                result[0].name shouldBe "The Stormlight Archive"
                result[0].description shouldBe "Epic fantasy series"
                result[1].id.value shouldBe "series-2"
                result[1].name shouldBe "Mistborn"
                result[1].description shouldBe "Fantasy trilogy"
            }
        }

        test("observeAll preserves entity order from dao") {
            runTest {
                // Given - entities ordered by name
                val entities =
                    listOf(
                        createTestSeriesEntity(id = "s1", name = "Alpha Series"),
                        createTestSeriesEntity(id = "s2", name = "Beta Series"),
                        createTestSeriesEntity(id = "s3", name = "Gamma Series"),
                    )
                val dao = createMockDao()
                every { dao.observeAll() } returns flowOf(entities)
                val repository = createRepository(dao)

                // When
                val result = repository.observeAll().first()

                // Then
                result[0].name shouldBe "Alpha Series"
                result[1].name shouldBe "Beta Series"
                result[2].name shouldBe "Gamma Series"
            }
        }

        test("observeAll delegates to dao observeAll") {
            runTest {
                // Given
                val dao = createMockDao()
                every { dao.observeAll() } returns flowOf(emptyList())
                val repository = createRepository(dao)

                // When
                repository.observeAll().first()

                // Then
                verify { dao.observeAll() }
            }
        }

        // ========== observeById Tests ==========

        test("observeById returns null when series not found") {
            runTest {
                // Given
                val dao = createMockDao()
                every { dao.observeById("nonexistent") } returns flowOf(null)
                val repository = createRepository(dao)

                // When
                val result = repository.observeById("nonexistent").first()

                // Then
                result shouldBe null
            }
        }

        test("observeById returns series when found") {
            runTest {
                // Given
                val entity =
                    createTestSeriesEntity(
                        id = "series-1",
                        name = "The Wheel of Time",
                        description = "Epic fantasy series by Robert Jordan",
                    )
                val dao = createMockDao()
                every { dao.observeById("series-1") } returns flowOf(entity)
                val repository = createRepository(dao)

                // When
                val result = repository.observeById("series-1").first()

                // Then
                result.shouldNotBeNull()
                result.id.value shouldBe "series-1"
                result.name shouldBe "The Wheel of Time"
                result.description shouldBe "Epic fantasy series by Robert Jordan"
            }
        }

        test("observeById transforms entity correctly") {
            runTest {
                // Given
                val entity =
                    createTestSeriesEntity(
                        id = "series-42",
                        name = "Test Series",
                        description = "Test description",
                    )
                val dao = createMockDao()
                every { dao.observeById("series-42") } returns flowOf(entity)
                val repository = createRepository(dao)

                // When
                val result = repository.observeById("series-42").first()

                // Then
                result.shouldNotBeNull()
                result.id.value shouldBe "series-42"
                result.name shouldBe "Test Series"
                result.description shouldBe "Test description"
            }
        }

        test("observeById delegates to dao with correct id") {
            runTest {
                // Given
                val dao = createMockDao()
                every { dao.observeById("target-id") } returns flowOf(null)
                val repository = createRepository(dao)

                // When
                repository.observeById("target-id").first()

                // Then
                verify { dao.observeById("target-id") }
            }
        }

        test("observeById swallows a sync-handler write failure so the observing flow survives (never stranded)") {
            runTest {
                // The on-demand cache fill succeeds at the RPC leg, but the Room write-through throws.
                // That must NOT propagate into the observing flow and kill the screen's collector — the
                // observer keeps emitting null rather than crashing.
                val payload =
                    SeriesSyncPayload(
                        id = "series-x",
                        name = "X",
                        sortName = null,
                        revision = 1L,
                        updatedAt = 0L,
                        createdAt = 0L,
                        deletedAt = null,
                    )
                val service = mock<SeriesService>(MockMode.autoUnit)
                everySuspend { service.getSeries(any()) } returns AppResult.Success(payload)
                val handler = mock<SyncDomainHandler<SeriesSyncPayload>>()
                everySuspend { handler.onCatchUpItem(any(), any()) } calls {
                    throw RuntimeException("Room write blew up")
                }

                val dao = createMockDao()
                every { dao.observeById("series-x") } returns flowOf(null)
                val networkMonitor = mock<NetworkMonitor>()
                every { networkMonitor.isOnline() } returns true

                val repository =
                    SeriesRepositoryImpl(
                        seriesDao = dao,
                        bookDao = mock<BookDao>(MockMode.autoUnit),
                        searchDao = mock<SearchDao>(MockMode.autoUnit),
                        api = mock<SeriesApiContract>(),
                        networkMonitor = networkMonitor,
                        imageStorage = mock<ImageStorage>(),
                        channel = RpcChannel.forTest(service),
                        seriesSyncHandler = handler,
                    )

                // The write-through was exercised (and threw) but the flow still emitted null cleanly.
                val result = repository.observeById("series-x").first()

                result shouldBe null
                verifySuspend { handler.onCatchUpItem(any(), any()) }
            }
        }

        // ========== getById Tests ==========

        test("getById returns null when series not found") {
            runTest {
                // Given
                val dao = createMockDao()
                everySuspend { dao.getById("nonexistent") } returns null
                val repository = createRepository(dao)

                // When
                val result = repository.getById("nonexistent")

                // Then
                result shouldBe null
            }
        }

        test("getById returns series when found") {
            runTest {
                // Given
                val entity =
                    createTestSeriesEntity(
                        id = "series-1",
                        name = "Harry Potter",
                        description = "Wizarding world",
                    )
                val dao = createMockDao()
                everySuspend { dao.getById("series-1") } returns entity
                val repository = createRepository(dao)

                // When
                val result = repository.getById("series-1")

                // Then
                result.shouldNotBeNull()
                result.id.value shouldBe "series-1"
                result.name shouldBe "Harry Potter"
                result.description shouldBe "Wizarding world"
            }
        }

        test("getById transforms all entity fields correctly") {
            runTest {
                // Given
                val entity =
                    createTestSeriesEntity(
                        id = "complete-series",
                        name = "Complete Series Name",
                        description = "Full description here",
                    )
                val dao = createMockDao()
                everySuspend { dao.getById("complete-series") } returns entity
                val repository = createRepository(dao)

                // When
                val result = repository.getById("complete-series")

                // Then
                result.shouldNotBeNull()
                result.id.value shouldBe "complete-series"
                result.name shouldBe "Complete Series Name"
                result.description shouldBe "Full description here"
            }
        }

        test("getById passes correct id to dao") {
            runTest {
                // Given
                val dao = createMockDao()
                everySuspend { dao.getById("target-id") } returns null
                val repository = createRepository(dao)

                // When
                repository.getById("target-id")

                // Then
                verifySuspend { dao.getById("target-id") }
            }
        }

        // ========== observeByBookId Tests ==========

        test("observeByBookId returns null when book has no series") {
            runTest {
                // Given
                val dao = createMockDao()
                every { dao.observeByBookId("book-1") } returns flowOf(null)
                val repository = createRepository(dao)

                // When
                val result = repository.observeByBookId("book-1").first()

                // Then
                result shouldBe null
            }
        }

        test("observeByBookId returns series for book") {
            runTest {
                // Given
                val entity =
                    createTestSeriesEntity(
                        id = "series-1",
                        name = "The Cosmere",
                        description = "Shared universe",
                    )
                val dao = createMockDao()
                every { dao.observeByBookId("book-1") } returns flowOf(entity)
                val repository = createRepository(dao)

                // When
                val result = repository.observeByBookId("book-1").first()

                // Then
                result.shouldNotBeNull()
                result.id.value shouldBe "series-1"
                result.name shouldBe "The Cosmere"
                result.description shouldBe "Shared universe"
            }
        }

        test("observeByBookId transforms entity to domain model") {
            runTest {
                // Given
                val entity =
                    createTestSeriesEntity(
                        id = "series-42",
                        name = "Book Series",
                        description = "Description",
                    )
                val dao = createMockDao()
                every { dao.observeByBookId("book-42") } returns flowOf(entity)
                val repository = createRepository(dao)

                // When
                val result = repository.observeByBookId("book-42").first()

                // Then
                result.shouldNotBeNull()
                result.id.value shouldBe "series-42"
                result.name shouldBe "Book Series"
                result.description shouldBe "Description"
            }
        }

        test("observeByBookId passes correct bookId to dao") {
            runTest {
                // Given
                val dao = createMockDao()
                every { dao.observeByBookId("my-book-id") } returns flowOf(null)
                val repository = createRepository(dao)

                // When
                repository.observeByBookId("my-book-id").first()

                // Then
                verify { dao.observeByBookId("my-book-id") }
            }
        }

        // ========== getBookIdsForSeries Tests ==========

        test("getBookIdsForSeries returns empty list when series has no books") {
            runTest {
                // Given
                val dao = createMockDao()
                everySuspend { dao.getBookIdsForSeries("series-1") } returns emptyList()
                val repository = createRepository(dao)

                // When
                val result = repository.getBookIdsForSeries("series-1")

                // Then
                (result.isEmpty()) shouldBe true
            }
        }

        test("getBookIdsForSeries returns book IDs for series") {
            runTest {
                // Given
                val bookIds = listOf("book-1", "book-2", "book-3")
                val dao = createMockDao()
                everySuspend { dao.getBookIdsForSeries("series-1") } returns bookIds
                val repository = createRepository(dao)

                // When
                val result = repository.getBookIdsForSeries("series-1")

                // Then
                result.size shouldBe 3
                result[0] shouldBe "book-1"
                result[1] shouldBe "book-2"
                result[2] shouldBe "book-3"
            }
        }

        test("getBookIdsForSeries passes correct seriesId to dao") {
            runTest {
                // Given
                val dao = createMockDao()
                everySuspend { dao.getBookIdsForSeries("specific-series-id") } returns emptyList()
                val repository = createRepository(dao)

                // When
                repository.getBookIdsForSeries("specific-series-id")

                // Then
                verifySuspend { dao.getBookIdsForSeries("specific-series-id") }
            }
        }

        // ========== observeBookIdsForSeries Tests ==========

        test("observeBookIdsForSeries returns empty list when series has no books") {
            runTest {
                // Given
                val dao = createMockDao()
                every { dao.observeBookIdsForSeries("series-1") } returns flowOf(emptyList())
                val repository = createRepository(dao)

                // When
                val result = repository.observeBookIdsForSeries("series-1").first()

                // Then
                (result.isEmpty()) shouldBe true
            }
        }

        test("observeBookIdsForSeries returns book IDs for series") {
            runTest {
                // Given
                val bookIds = listOf("book-a", "book-b")
                val dao = createMockDao()
                every { dao.observeBookIdsForSeries("series-1") } returns flowOf(bookIds)
                val repository = createRepository(dao)

                // When
                val result = repository.observeBookIdsForSeries("series-1").first()

                // Then
                result.size shouldBe 2
                result[0] shouldBe "book-a"
                result[1] shouldBe "book-b"
            }
        }

        test("observeBookIdsForSeries passes correct seriesId to dao") {
            runTest {
                // Given
                val dao = createMockDao()
                every { dao.observeBookIdsForSeries("target-series") } returns flowOf(emptyList())
                val repository = createRepository(dao)

                // When
                repository.observeBookIdsForSeries("target-series").first()

                // Then
                verify { dao.observeBookIdsForSeries("target-series") }
            }
        }

        // ========== Entity to Domain Conversion Tests ==========

        test("toDomain converts all entity fields correctly") {
            runTest {
                // Given
                val entity =
                    createTestSeriesEntity(
                        id = "conversion-test",
                        name = "Full Name",
                        description = "Full description",
                    )
                val dao = createMockDao()
                everySuspend { dao.getById("conversion-test") } returns entity
                val repository = createRepository(dao)

                // When
                val result = repository.getById("conversion-test")

                // Then - verify all fields are mapped
                result.shouldNotBeNull()
                result.id.value shouldBe "conversion-test"
                result.name shouldBe "Full Name"
                result.description shouldBe "Full description"
            }
        }

        test("toDomain handles null optional fields") {
            runTest {
                // Given
                val entity =
                    createTestSeriesEntity(
                        id = "minimal-series",
                        name = "Minimal Series",
                        description = null,
                    )
                val dao = createMockDao()
                everySuspend { dao.getById("minimal-series") } returns entity
                val repository = createRepository(dao)

                // When
                val result = repository.getById("minimal-series")

                // Then
                result.shouldNotBeNull()
                result.id.value shouldBe "minimal-series"
                result.name shouldBe "Minimal Series"
                result.description shouldBe null
            }
        }

        // ========== Multiple Items Tests ==========

        test("observeAll handles large number of series") {
            runTest {
                // Given
                val entities =
                    (1..100).map { i ->
                        createTestSeriesEntity(
                            id = "series-$i",
                            name = "Series $i",
                        )
                    }
                val dao = createMockDao()
                every { dao.observeAll() } returns flowOf(entities)
                val repository = createRepository(dao)

                // When
                val result = repository.observeAll().first()

                // Then
                result.size shouldBe 100
                result[0].id.value shouldBe "series-1"
                result[99].id.value shouldBe "series-100"
            }
        }

        test("getBookIdsForSeries handles large number of books") {
            runTest {
                // Given
                val bookIds = (1..200).map { "book-$it" }
                val dao = createMockDao()
                everySuspend { dao.getBookIdsForSeries("large-series") } returns bookIds
                val repository = createRepository(dao)

                // When
                val result = repository.getBookIdsForSeries("large-series")

                // Then
                result.size shouldBe 200
                result[0] shouldBe "book-1"
                result[199] shouldBe "book-200"
            }
        }

        // ========== Edge Cases Tests ==========

        test("observeAll handles series with empty description") {
            runTest {
                // Given
                val entity =
                    createTestSeriesEntity(
                        id = "series-1",
                        name = "Series Name",
                        description = "",
                    )
                val dao = createMockDao()
                every { dao.observeAll() } returns flowOf(listOf(entity))
                val repository = createRepository(dao)

                // When
                val result = repository.observeAll().first()

                // Then
                result.size shouldBe 1
                result[0].description shouldBe ""
            }
        }

        test("observeById handles series with special characters in name") {
            runTest {
                // Given
                val entity =
                    createTestSeriesEntity(
                        id = "series-special",
                        name = "Series: The \"Special\" One (2024)",
                        description = null,
                    )
                val dao = createMockDao()
                every { dao.observeById("series-special") } returns flowOf(entity)
                val repository = createRepository(dao)

                // When
                val result = repository.observeById("series-special").first()

                // Then
                result.shouldNotBeNull()
                result.name shouldBe "Series: The \"Special\" One (2024)"
            }
        }

        test("getBookIdsForSeries returns single book ID") {
            runTest {
                // Given
                val dao = createMockDao()
                everySuspend { dao.getBookIdsForSeries("single-book-series") } returns listOf("only-book")
                val repository = createRepository(dao)

                // When
                val result = repository.getBookIdsForSeries("single-book-series")

                // Then
                result.size shouldBe 1
                result[0] shouldBe "only-book"
            }
        }

        // ========== observeAllWithBooks Tests ==========

        test("observeAllWithBooks returns empty list when no series exist") {
            runTest {
                val seriesDao = createMockDao()
                val bookDao = mock<BookDao>(MockMode.autoUnit)
                every { seriesDao.observeAllWithBooks() } returns flowOf(emptyList())
                every { bookDao.observeAllWithContributors() } returns flowOf(emptyList())
                val repository = createRepositoryWithBookDao(seriesDao, bookDao)

                val result = repository.observeAllWithBooks().first()

                (result.isEmpty()) shouldBe true
            }
        }

        test("observeAllWithBooks joins series with books from book dao by id") {
            runTest {
                val seriesEntity = createTestSeriesEntity(id = "series-1", name = "Stormlight")
                val book1 = makeBookEntity("book-1", "Way of Kings")
                val book2 = makeBookEntity("book-2", "Words of Radiance")
                val seriesRelation =
                    SeriesWithBooksRelation(
                        series = seriesEntity,
                        books = listOf(book1, book2),
                        bookSequences =
                            listOf(
                                BookSeriesCrossRef(BookId("book-1"), SeriesId("series-1"), "1"),
                                BookSeriesCrossRef(BookId("book-2"), SeriesId("series-1"), "2"),
                            ),
                    )

                val seriesDao = createMockDao()
                val bookDao = mock<BookDao>(MockMode.autoUnit)
                every { seriesDao.observeAllWithBooks() } returns flowOf(listOf(seriesRelation))
                every { bookDao.observeAllWithContributors() } returns
                    flowOf(listOf(makeBookWithContributors(book1), makeBookWithContributors(book2)))
                val repository = createRepositoryWithBookDao(seriesDao, bookDao)

                val result = repository.observeAllWithBooks().first()

                result.size shouldBe 1
                result[0].series.id.value shouldBe "series-1"
                result[0].books.size shouldBe 2
                result[0].books[0].id.value shouldBe "book-1"
                result[0].books[0].title shouldBe "Way of Kings"
                result[0].bookSequences["book-1"] shouldBe "1"
                result[0].bookSequences["book-2"] shouldBe "2"
            }
        }

        test("observeAllWithBooks silently skips orphan book ids missing from book dao") {
            runTest {
                // Series references two books, but bookDao only knows about one.
                val seriesEntity = createTestSeriesEntity(id = "series-1", name = "Series")
                val book1 = makeBookEntity("book-1", "Known Book")
                val orphanBook = makeBookEntity("book-orphan", "Stale Reference")
                val seriesRelation =
                    SeriesWithBooksRelation(
                        series = seriesEntity,
                        books = listOf(book1, orphanBook),
                        bookSequences = emptyList(),
                    )

                val seriesDao = createMockDao()
                val bookDao = mock<BookDao>(MockMode.autoUnit)
                every { seriesDao.observeAllWithBooks() } returns flowOf(listOf(seriesRelation))
                // Only book-1 is in bookDao's flow; book-orphan is missing.
                every { bookDao.observeAllWithContributors() } returns
                    flowOf(listOf(makeBookWithContributors(book1)))
                val repository = createRepositoryWithBookDao(seriesDao, bookDao)

                val result = repository.observeAllWithBooks().first()

                result.size shouldBe 1
                result[0].books.size shouldBe 1
                result[0].books[0].id.value shouldBe "book-1"
            }
        }

        // ========== observeSeriesWithBooks Tests ==========

        test("observeSeriesWithBooks returns null when series relation is null") {
            runTest {
                val seriesDao = createMockDao()
                val bookDao = mock<BookDao>(MockMode.autoUnit)
                every { seriesDao.observeByIdWithBooks("missing") } returns flowOf(null)
                every { bookDao.observeBySeriesIdWithContributors("missing") } returns flowOf(emptyList())
                val repository = createRepositoryWithBookDao(seriesDao, bookDao)

                val result = repository.observeSeriesWithBooks("missing").first()

                result shouldBe null
            }
        }

        test("observeSeriesWithBooks combines series relation with contributor-enriched books") {
            runTest {
                val seriesEntity = createTestSeriesEntity(id = "series-1", name = "Cosmere")
                val book1 = makeBookEntity("book-1", "Book One")
                val book2 = makeBookEntity("book-2", "Book Two")
                val seriesRelation =
                    SeriesWithBooksRelation(
                        series = seriesEntity,
                        books = listOf(book1, book2),
                        bookSequences =
                            listOf(
                                BookSeriesCrossRef(BookId("book-1"), SeriesId("series-1"), "1"),
                                BookSeriesCrossRef(BookId("book-2"), SeriesId("series-1"), "2"),
                            ),
                    )

                val seriesDao = createMockDao()
                val bookDao = mock<BookDao>(MockMode.autoUnit)
                every { seriesDao.observeByIdWithBooks("series-1") } returns flowOf(seriesRelation)
                every { bookDao.observeBySeriesIdWithContributors("series-1") } returns
                    flowOf(listOf(makeBookWithContributors(book1), makeBookWithContributors(book2)))
                val repository = createRepositoryWithBookDao(seriesDao, bookDao)

                val result = repository.observeSeriesWithBooks("series-1").first()

                result.shouldNotBeNull()
                result.series.id.value shouldBe "series-1"
                result.books.size shouldBe 2
                result.books[0].id.value shouldBe "book-1"
                result.books[1].id.value shouldBe "book-2"
                result.bookSequences["book-1"] shouldBe "1"
                result.bookSequences["book-2"] shouldBe "2"
            }
        }

        // ========== B2a Enrichment Field Round-Trip Tests (M1) ==========

        test("toDomain carries coverPath through entity→domain boundary") {
            runTest {
                val entity =
                    SeriesEntity(
                        id =
                            com.calypsan.listenup.core
                                .SeriesId("series-cover"),
                        name = "Stormlight Archive",
                        description = null,
                        coverPath = ".listenup-meta/series/stormlight.jpg",
                        coverBlurHash = null,
                        asin = null,
                        createdAt = Timestamp(1000L),
                        updatedAt = Timestamp(1000L),
                    )
                val dao = createMockDao()
                everySuspend { dao.getById("series-cover") } returns entity
                val repository = createRepository(dao)

                val result = repository.getById("series-cover")

                result.shouldNotBeNull()
                result.coverPath shouldBe ".listenup-meta/series/stormlight.jpg"
                result.coverBlurHash shouldBe null
                result.asin shouldBe null
            }
        }

        test("toDomain carries coverBlurHash and asin through entity→domain boundary") {
            runTest {
                val entity =
                    SeriesEntity(
                        id =
                            com.calypsan.listenup.core
                                .SeriesId("series-full"),
                        name = "Mistborn",
                        description = "Ash falls from the sky.",
                        coverPath = ".listenup-meta/series/mistborn.jpg",
                        coverBlurHash = "L6Pj0^jE.AyE_3t7t7R**0o#DgR4",
                        asin = "B017V4IM1G",
                        createdAt = Timestamp(2000L),
                        updatedAt = Timestamp(2000L),
                    )
                val dao = createMockDao()
                everySuspend { dao.getById("series-full") } returns entity
                val repository = createRepository(dao)

                val result = repository.getById("series-full")

                result.shouldNotBeNull()
                result.coverPath shouldBe ".listenup-meta/series/mistborn.jpg"
                result.coverBlurHash shouldBe "L6Pj0^jE.AyE_3t7t7R**0o#DgR4"
                result.asin shouldBe "B017V4IM1G"
            }
        }

        test("toDomain maps null enrichment fields as null") {
            runTest {
                val entity =
                    SeriesEntity(
                        id =
                            com.calypsan.listenup.core
                                .SeriesId("series-bare"),
                        name = "Bare Series",
                        description = null,
                        coverPath = null,
                        coverBlurHash = null,
                        asin = null,
                        createdAt = Timestamp(1000L),
                        updatedAt = Timestamp(1000L),
                    )
                val dao = createMockDao()
                everySuspend { dao.getById("series-bare") } returns entity
                val repository = createRepository(dao)

                val result = repository.getById("series-bare")

                result.shouldNotBeNull()
                result.coverPath shouldBe null
                result.coverBlurHash shouldBe null
                result.asin shouldBe null
            }
        }
    })
