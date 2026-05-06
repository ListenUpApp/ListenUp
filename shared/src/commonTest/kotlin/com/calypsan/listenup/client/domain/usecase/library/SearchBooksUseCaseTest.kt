package com.calypsan.listenup.client.domain.usecase.library

import com.calypsan.listenup.client.checkIs
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.core.Success
import com.calypsan.listenup.client.domain.model.SearchHit
import com.calypsan.listenup.client.domain.model.SearchHitType
import com.calypsan.listenup.client.domain.model.SearchResult
import com.calypsan.listenup.client.domain.repository.SearchRepository
import dev.mokkery.answering.returns
import dev.mokkery.answering.throws
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for SearchBooksUseCase.
 *
 * Tests cover:
 * - Query validation (minimum length, trimming)
 * - Limit validation (range checking)
 * - Successful search flow
 * - Error handling and propagation
 * - Parameter passing to repository
 */
class SearchBooksUseCaseTest {
    // ========== Test Fixtures ==========

    private class TestFixture {
        val searchRepository: SearchRepository = mock()

        fun build(): SearchBooksUseCase =
            SearchBooksUseCase(
                searchRepository = searchRepository,
            )
    }

    private fun createFixture(): TestFixture = TestFixture()

    // ========== Test Data Factories ==========

    private fun createSearchResult(
        query: String = "test",
        hits: List<SearchHit> = emptyList(),
        total: Int = hits.size,
    ): SearchResult =
        SearchResult(
            query = query,
            total = total,
            tookMs = 10L,
            hits = hits,
        )

    private fun createBookHit(
        id: String = "book-1",
        name: String = "Test Book",
    ): SearchHit =
        SearchHit(
            id = id,
            type = SearchHitType.BOOK,
            name = name,
        )

    // ========== Query Validation Tests ==========

    @Test
    fun `empty query returns empty result without calling repository`() =
        runTest {
            // Given
            val fixture = createFixture()
            val useCase = fixture.build()

            // When
            val result = useCase(query = "")

            // Then
            val success = assertIs<Success<SearchResult>>(result)
            assertEquals(0, success.data.total)
            assertTrue(success.data.hits.isEmpty())
        }

    @Test
    fun `whitespace-only query returns empty result`() =
        runTest {
            // Given
            val fixture = createFixture()
            val useCase = fixture.build()

            // When
            val result = useCase(query = "   ")

            // Then
            val success = assertIs<Success<SearchResult>>(result)
            assertEquals(0, success.data.total)
        }

    @Test
    fun `single character query returns failure`() =
        runTest {
            // Given
            val fixture = createFixture()
            val useCase = fixture.build()

            // When
            val result = useCase(query = "a")

            // Then
            val failure = assertIs<Failure>(result)
            assertTrue(failure.message.contains("2", ignoreCase = true))
        }

    @Test
    fun `two character query succeeds`() =
        runTest {
            // Given
            val fixture = createFixture()
            everySuspend { fixture.searchRepository.search(any(), any(), any(), any(), any()) } returns
                createSearchResult(query = "ab")
            val useCase = fixture.build()

            // When
            val result = useCase(query = "ab")

            // Then
            checkIs<Success<SearchResult>>(result)
        }

    @Test
    fun `query is trimmed before search`() =
        runTest {
            // Given
            val fixture = createFixture()
            everySuspend { fixture.searchRepository.search(any(), any(), any(), any(), any()) } returns
                createSearchResult(query = "test")
            val useCase = fixture.build()

            // When
            useCase(query = "  test  ")

            // Then
            verifySuspend {
                fixture.searchRepository.search(
                    query = "test",
                    types = null,
                    genres = null,
                    genrePath = null,
                    limit = SearchBooksUseCase.DEFAULT_RESULT_LIMIT,
                )
            }
        }

    // ========== Limit Validation Tests ==========

    @Test
    fun `zero limit returns failure`() =
        runTest {
            // Given
            val fixture = createFixture()
            val useCase = fixture.build()

            // When
            val result = useCase(query = "test", limit = 0)

            // Then
            val failure = assertIs<Failure>(result)
            assertTrue(failure.message.contains("limit", ignoreCase = true))
        }

    @Test
    fun `negative limit returns failure`() =
        runTest {
            // Given
            val fixture = createFixture()
            val useCase = fixture.build()

            // When
            val result = useCase(query = "test", limit = -1)

            // Then
            val failure = assertIs<Failure>(result)
            assertTrue(failure.message.contains("limit", ignoreCase = true))
        }

    @Test
    fun `limit exceeding max returns failure`() =
        runTest {
            // Given
            val fixture = createFixture()
            val useCase = fixture.build()

            // When
            val result = useCase(query = "test", limit = SearchBooksUseCase.MAX_RESULT_LIMIT + 1)

            // Then
            val failure = assertIs<Failure>(result)
            assertTrue(failure.message.contains("limit", ignoreCase = true))
        }

    @Test
    fun `max limit succeeds`() =
        runTest {
            // Given
            val fixture = createFixture()
            everySuspend { fixture.searchRepository.search(any(), any(), any(), any(), any()) } returns
                createSearchResult()
            val useCase = fixture.build()

            // When
            val result = useCase(query = "test", limit = SearchBooksUseCase.MAX_RESULT_LIMIT)

            // Then
            checkIs<Success<SearchResult>>(result)
        }

    // ========== Successful Search Tests ==========

    @Test
    fun `successful search returns search result`() =
        runTest {
            // Given
            val fixture = createFixture()
            val expectedHits = listOf(createBookHit(id = "book-1"), createBookHit(id = "book-2"))
            everySuspend { fixture.searchRepository.search(any(), any(), any(), any(), any()) } returns
                createSearchResult(hits = expectedHits)
            val useCase = fixture.build()

            // When
            val result = useCase(query = "fantasy")

            // Then
            val success = assertIs<Success<SearchResult>>(result)
            assertEquals(2, success.data.hits.size)
            assertEquals("book-1", success.data.hits[0].id)
            assertEquals("book-2", success.data.hits[1].id)
        }

    @Test
    fun `search passes all parameters to repository`() =
        runTest {
            // Given
            val fixture = createFixture()
            everySuspend { fixture.searchRepository.search(any(), any(), any(), any(), any()) } returns
                createSearchResult()
            val useCase = fixture.build()

            // When
            useCase(
                query = "test",
                types = listOf(SearchHitType.BOOK, SearchHitType.SERIES),
                genres = listOf("fantasy", "scifi"),
                genrePath = "fiction",
                limit = 50,
            )

            // Then
            verifySuspend {
                fixture.searchRepository.search(
                    query = "test",
                    types = listOf(SearchHitType.BOOK, SearchHitType.SERIES),
                    genres = listOf("fantasy", "scifi"),
                    genrePath = "fiction",
                    limit = 50,
                )
            }
        }

    @Test
    fun `search with null types passes null to repository`() =
        runTest {
            // Given
            val fixture = createFixture()
            everySuspend { fixture.searchRepository.search(any(), any(), any(), any(), any()) } returns
                createSearchResult()
            val useCase = fixture.build()

            // When
            useCase(query = "test", types = null)

            // Then
            verifySuspend {
                fixture.searchRepository.search(
                    query = "test",
                    types = null,
                    genres = null,
                    genrePath = null,
                    limit = SearchBooksUseCase.DEFAULT_RESULT_LIMIT,
                )
            }
        }

    // ========== Error Handling Tests ==========

    @Test
    fun `repository exception returns failure`() =
        runTest {
            // Test cares that the failure path is hit. Specific message-text
            // preservation is ErrorMapper / Failure(throwable)'s contract, tested
            // at that layer (AppResultTest); not a use-case concern.
            val fixture = createFixture()
            everySuspend {
                fixture.searchRepository.search(any(), any(), any(), any(), any())
            } throws Exception("repo failed")
            val useCase = fixture.build()

            val result = useCase(query = "test")

            assertIs<Failure>(result)
        }
}
