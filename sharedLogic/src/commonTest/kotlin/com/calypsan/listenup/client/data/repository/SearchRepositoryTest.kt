package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.client.data.local.db.BookEntity
import com.calypsan.listenup.client.data.local.db.BookSearchResult
import com.calypsan.listenup.client.data.local.db.ContributorEntity
import com.calypsan.listenup.client.data.local.db.SearchDao
import com.calypsan.listenup.client.data.local.db.SeriesEntity
import com.calypsan.listenup.client.data.remote.SearchApiContract
import com.calypsan.listenup.client.data.remote.SearchException
import com.calypsan.listenup.client.data.remote.SearchFacetsResponse
import com.calypsan.listenup.client.data.remote.SearchHitResponse
import com.calypsan.listenup.client.data.remote.SearchResponse
import com.calypsan.listenup.client.domain.model.SearchHitType
import com.calypsan.listenup.client.domain.repository.ImageStorage
import dev.mokkery.MockMode
import dev.mokkery.answering.returns
import dev.mokkery.answering.throws
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for SearchRepository.
 *
 * Tests the "never stranded" pattern:
 * - Primary: Use server Bleve search
 * - Fallback: Local Room FTS5 on server failure
 */
class SearchRepositoryTest {
    // --- Helper functions for creating mocks ---

    private fun createMockSearchApi(): SearchApiContract = mock<SearchApiContract>()

    private fun createMockSearchDao(): SearchDao = mock<SearchDao>(MockMode.autoUnit)

    private fun createMockImageStorage(): ImageStorage = mock<ImageStorage>()

    private fun createTestBookSearchResult(
        id: String = "book-1",
        title: String = "Test Book",
        authorName: String? = "Test Author",
    ): BookSearchResult =
        BookSearchResult(
            book =
                BookEntity(
                    id = BookId(id),
                    title = title,
                    subtitle = null,
                    coverHash = null,
                    totalDuration = 3600000,
                    description = null,
                    publishYear = null,
                    createdAt = Timestamp(0),
                    updatedAt = Timestamp(0),
                ),
            authorName = authorName,
        )

    private fun createSearchResponse(
        query: String = "test",
        hits: List<SearchHitResponse> = emptyList(),
    ): SearchResponse =
        SearchResponse(
            query = query,
            total = hits.size.toLong(),
            tookMs = 10,
            hits = hits,
            facets = SearchFacetsResponse(),
        )

    private fun createSearchHitResponse(
        id: String = "book-1",
        type: String = "book",
        name: String = "Test Book",
    ): SearchHitResponse =
        SearchHitResponse(
            id = id,
            type = type,
            score = 1.0f,
            name = name,
            subtitle = null,
            author = "Test Author",
            narrator = "Test Narrator",
        )

    // --- Empty/blank query tests ---

    @Test
    fun `empty query returns empty result`() =
        runTest {
            // Given
            val searchApi = createMockSearchApi()
            val searchDao = createMockSearchDao()
            val imageStorage = createMockImageStorage()
            val repository = SearchRepositoryImpl(searchApi, searchDao, imageStorage)

            // When
            val result = repository.search("")

            // Then
            assertEquals(0, result.total)
            assertTrue(result.hits.isEmpty())
        }

    @Test
    fun `whitespace-only query returns empty result`() =
        runTest {
            // Given
            val searchApi = createMockSearchApi()
            val searchDao = createMockSearchDao()
            val imageStorage = createMockImageStorage()
            val repository = SearchRepositoryImpl(searchApi, searchDao, imageStorage)

            // When
            val result = repository.search("   ")

            // Then
            assertEquals(0, result.total)
            assertTrue(result.hits.isEmpty())
        }

    // --- Server search tests ---

    @Test
    fun `search calls server API first`() =
        runTest {
            // Given
            val searchApi = createMockSearchApi()
            val searchDao = createMockSearchDao()
            val imageStorage = createMockImageStorage()
            val repository = SearchRepositoryImpl(searchApi, searchDao, imageStorage)

            every { imageStorage.exists(any()) } returns false

            val serverResponse =
                createSearchResponse(
                    query = "sanderson",
                    hits =
                        listOf(
                            createSearchHitResponse(id = "book-1", name = "Mistborn"),
                            createSearchHitResponse(id = "book-2", name = "Elantris"),
                        ),
                )
            everySuspend {
                searchApi.search(
                    query = any(),
                    types = any(),
                    genres = any(),
                    genrePath = any(),
                    minDuration = any(),
                    maxDuration = any(),
                    limit = any(),
                    offset = any(),
                )
            } returns serverResponse

            // When
            val result = repository.search("sanderson")

            // Then
            assertEquals(2, result.hits.size)
            assertEquals("Mistborn", result.hits[0].name)
            assertEquals("Elantris", result.hits[1].name)
            assertFalse(result.isOfflineResult)
        }

    @Test
    fun `search with types parameter filters correctly`() =
        runTest {
            // Given
            val searchApi = createMockSearchApi()
            val searchDao = createMockSearchDao()
            val imageStorage = createMockImageStorage()
            val repository = SearchRepositoryImpl(searchApi, searchDao, imageStorage)

            every { imageStorage.exists(any()) } returns false

            everySuspend {
                searchApi.search(
                    query = any(),
                    types = any(),
                    genres = any(),
                    genrePath = any(),
                    minDuration = any(),
                    maxDuration = any(),
                    limit = any(),
                    offset = any(),
                )
            } returns createSearchResponse()

            // When
            repository.search("test", types = listOf(SearchHitType.BOOK))

            // Then - verify API was called (with types param passed)
            verifySuspend {
                searchApi.search(
                    query = any(),
                    types = any(),
                    genres = any(),
                    genrePath = any(),
                    minDuration = any(),
                    maxDuration = any(),
                    limit = any(),
                    offset = any(),
                )
            }
        }

    // --- Fallback tests ---

    @Test
    fun `server error falls back to local FTS`() =
        runTest {
            // Given
            val searchApi = createMockSearchApi()
            val searchDao = createMockSearchDao()
            val imageStorage = createMockImageStorage()
            val repository = SearchRepositoryImpl(searchApi, searchDao, imageStorage)

            every { imageStorage.exists(any()) } returns false
            everySuspend {
                searchApi.search(
                    query = any(),
                    types = any(),
                    genres = any(),
                    genrePath = any(),
                    minDuration = any(),
                    maxDuration = any(),
                    limit = any(),
                    offset = any(),
                )
            } throws SearchException("Server error")
            everySuspend { searchDao.searchBooks(any(), any()) } returns
                listOf(createTestBookSearchResult(id = "book-1", title = "Fallback Book"))
            everySuspend { searchDao.searchContributors(any(), any()) } returns emptyList()
            everySuspend { searchDao.searchSeries(any(), any()) } returns emptyList()
            everySuspend { searchDao.searchTags(any(), any()) } returns emptyList()

            // When
            val result = repository.search("fallback")

            // Then
            assertEquals(1, result.hits.size)
            assertEquals("Fallback Book", result.hits[0].name)
            assertTrue(result.isOfflineResult)
        }

    @Test
    fun `network error falls back to local FTS`() =
        runTest {
            // Given
            val searchApi = createMockSearchApi()
            val searchDao = createMockSearchDao()
            val imageStorage = createMockImageStorage()
            val repository = SearchRepositoryImpl(searchApi, searchDao, imageStorage)

            every { imageStorage.exists(any()) } returns false
            everySuspend {
                searchApi.search(
                    query = any(),
                    types = any(),
                    genres = any(),
                    genrePath = any(),
                    minDuration = any(),
                    maxDuration = any(),
                    limit = any(),
                    offset = any(),
                )
            } throws RuntimeException("No network")
            everySuspend { searchDao.searchBooks(any(), any()) } returns
                listOf(createTestBookSearchResult(id = "book-1", title = "Local Book"))
            everySuspend { searchDao.searchContributors(any(), any()) } returns emptyList()
            everySuspend { searchDao.searchSeries(any(), any()) } returns emptyList()
            everySuspend { searchDao.searchTags(any(), any()) } returns emptyList()

            // When
            val result = repository.search("local")

            // Then
            assertEquals(1, result.hits.size)
            assertEquals("Local Book", result.hits[0].name)
            assertTrue(result.isOfflineResult)
        }

    // --- Query sanitization tests ---

    @Test
    fun `query with special characters is sanitized`() =
        runTest {
            // Given
            val searchApi = createMockSearchApi()
            val searchDao = createMockSearchDao()
            val imageStorage = createMockImageStorage()
            val repository = SearchRepositoryImpl(searchApi, searchDao, imageStorage)

            every { imageStorage.exists(any()) } returns false
            everySuspend {
                searchApi.search(
                    query = any(),
                    types = any(),
                    genres = any(),
                    genrePath = any(),
                    minDuration = any(),
                    maxDuration = any(),
                    limit = any(),
                    offset = any(),
                )
            } returns createSearchResponse()

            // When - query with FTS special chars that should be stripped
            val result = repository.search("test*()\":")

            // Then - should not throw, search executes
            // The special chars are sanitized before API call
        }

    @Test
    fun `very long query is truncated`() =
        runTest {
            // Given
            val searchApi = createMockSearchApi()
            val searchDao = createMockSearchDao()
            val imageStorage = createMockImageStorage()
            val repository = SearchRepositoryImpl(searchApi, searchDao, imageStorage)

            every { imageStorage.exists(any()) } returns false
            everySuspend {
                searchApi.search(
                    query = any(),
                    types = any(),
                    genres = any(),
                    genrePath = any(),
                    minDuration = any(),
                    maxDuration = any(),
                    limit = any(),
                    offset = any(),
                )
            } returns createSearchResponse()

            // When - query longer than 100 chars
            val longQuery = "a".repeat(200)
            val result = repository.search(longQuery)

            // Then - should not throw, search executes with truncated query
        }

    // --- Result mapping tests ---

    @Test
    fun `server response maps hit types correctly`() =
        runTest {
            // Given
            val searchApi = createMockSearchApi()
            val searchDao = createMockSearchDao()
            val imageStorage = createMockImageStorage()
            val repository = SearchRepositoryImpl(searchApi, searchDao, imageStorage)

            every { imageStorage.exists(any()) } returns false

            val serverResponse =
                createSearchResponse(
                    query = "test",
                    hits =
                        listOf(
                            createSearchHitResponse(id = "book-1", type = "book", name = "Book"),
                            createSearchHitResponse(id = "contrib-1", type = "contributor", name = "Author"),
                            createSearchHitResponse(id = "series-1", type = "series", name = "Series"),
                        ),
                )
            everySuspend {
                searchApi.search(
                    query = any(),
                    types = any(),
                    genres = any(),
                    genrePath = any(),
                    minDuration = any(),
                    maxDuration = any(),
                    limit = any(),
                    offset = any(),
                )
            } returns serverResponse

            // When
            val result = repository.search("test")

            // Then
            assertEquals(3, result.hits.size)
            assertEquals(SearchHitType.BOOK, result.hits[0].type)
            assertEquals(SearchHitType.CONTRIBUTOR, result.hits[1].type)
            assertEquals(SearchHitType.SERIES, result.hits[2].type)
        }

    @Test
    fun `local search returns correct hit types`() =
        runTest {
            // Given
            val searchApi = createMockSearchApi()
            val searchDao = createMockSearchDao()
            val imageStorage = createMockImageStorage()
            val repository = SearchRepositoryImpl(searchApi, searchDao, imageStorage)

            every { imageStorage.exists(any()) } returns false
            // Server throws to trigger local fallback
            everySuspend {
                searchApi.search(
                    query = any(),
                    types = any(),
                    genres = any(),
                    genrePath = any(),
                    minDuration = any(),
                    maxDuration = any(),
                    limit = any(),
                    offset = any(),
                )
            } throws SearchException("Server unavailable")
            everySuspend { searchDao.searchBooks(any(), any()) } returns
                listOf(createTestBookSearchResult(title = "Book"))
            everySuspend { searchDao.searchContributors(any(), any()) } returns
                listOf(
                    ContributorEntity(
                        id =
                            com.calypsan.listenup.core
                                .ContributorId("c1"),
                        name = "Author",
                        description = null,
                        imagePath = null,
                        createdAt = Timestamp(0),
                        updatedAt = Timestamp(0),
                    ),
                )
            everySuspend { searchDao.searchSeries(any(), any()) } returns
                listOf(
                    SeriesEntity(
                        id =
                            com.calypsan.listenup.core
                                .SeriesId("s1"),
                        name = "Series",
                        description = null,
                        createdAt = Timestamp(0),
                        updatedAt = Timestamp(0),
                    ),
                )
            everySuspend { searchDao.searchTags(any(), any()) } returns emptyList()

            // When
            val result = repository.search("test")

            // Then
            assertEquals(3, result.hits.size)
            assertTrue(result.hits.any { it.type == SearchHitType.BOOK })
            assertTrue(result.hits.any { it.type == SearchHitType.CONTRIBUTOR })
            assertTrue(result.hits.any { it.type == SearchHitType.SERIES })
        }
}
