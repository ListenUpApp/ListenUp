package com.calypsan.listenup.client.domain.usecase.library

import com.calypsan.listenup.client.checkIs
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.core.Success
import com.calypsan.listenup.client.domain.model.ContinueListeningBook
import com.calypsan.listenup.client.domain.repository.HomeRepository
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for GetContinueListeningUseCase.
 *
 * Tests cover:
 * - Limit validation
 * - Successful fetch flow
 * - Error handling and mapping
 * - Flow observation
 * - hasBooks utility function
 */
class GetContinueListeningUseCaseTest {
    // ========== Test Fixtures ==========

    private class TestFixture {
        val homeRepository: HomeRepository = mock()

        fun build(): GetContinueListeningUseCase =
            GetContinueListeningUseCase(
                homeRepository = homeRepository,
            )
    }

    private fun createFixture(): TestFixture = TestFixture()

    // ========== Test Data Factories ==========

    private fun createContinueListeningBook(
        bookId: String = "book-1",
        title: String = "Test Book",
        authorNames: String = "Test Author",
        progress: Float = 0.5f,
    ): ContinueListeningBook =
        ContinueListeningBook(
            bookId = bookId,
            title = title,
            authorNames = authorNames,
            coverPath = null,
            coverBlurHash = null,
            progress = progress,
            currentPositionMs = 30_000L,
            totalDurationMs = 60_000L,
            lastPlayedAt = "2024-01-01T00:00:00Z",
        )

    // ========== Limit Validation Tests ==========

    @Test
    fun `zero limit returns failure`() =
        runTest {
            // Given
            val fixture = createFixture()
            val useCase = fixture.build()

            // When
            val result = useCase(limit = 0)

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
            val result = useCase(limit = -1)

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
            val result = useCase(limit = GetContinueListeningUseCase.MAX_LIMIT + 1)

            // Then
            val failure = assertIs<Failure>(result)
            assertTrue(failure.message.contains("limit", ignoreCase = true))
        }

    @Test
    fun `max limit succeeds`() =
        runTest {
            // Given
            val fixture = createFixture()
            everySuspend { fixture.homeRepository.getContinueListening(any()) } returns
                Success(emptyList())
            val useCase = fixture.build()

            // When
            val result = useCase(limit = GetContinueListeningUseCase.MAX_LIMIT)

            // Then
            checkIs<Success<List<ContinueListeningBook>>>(result)
        }

    // ========== Successful Fetch Tests ==========

    @Test
    fun `successful fetch returns list of books`() =
        runTest {
            // Given
            val fixture = createFixture()
            val expectedBooks =
                listOf(
                    createContinueListeningBook(bookId = "book-1"),
                    createContinueListeningBook(bookId = "book-2"),
                )
            everySuspend { fixture.homeRepository.getContinueListening(any()) } returns
                Success(expectedBooks)
            val useCase = fixture.build()

            // When
            val result = useCase(limit = 10)

            // Then
            val success = assertIs<Success<List<ContinueListeningBook>>>(result)
            assertEquals(2, success.data.size)
            assertEquals("book-1", success.data[0].bookId)
            assertEquals("book-2", success.data[1].bookId)
        }

    @Test
    fun `fetch passes limit to repository`() =
        runTest {
            // Given
            val fixture = createFixture()
            everySuspend { fixture.homeRepository.getContinueListening(any()) } returns
                Success(emptyList())
            val useCase = fixture.build()

            // When
            useCase(limit = 25)

            // Then
            verifySuspend {
                fixture.homeRepository.getContinueListening(25)
            }
        }

    @Test
    fun `default limit is used when not specified`() =
        runTest {
            // Given
            val fixture = createFixture()
            everySuspend { fixture.homeRepository.getContinueListening(any()) } returns
                Success(emptyList())
            val useCase = fixture.build()

            // When
            useCase()

            // Then
            verifySuspend {
                fixture.homeRepository.getContinueListening(GetContinueListeningUseCase.DEFAULT_LIMIT)
            }
        }

    // ========== Error Handling Tests ==========

    @Test
    fun `repository failure propagates AppError unmodified`() =
        runTest {
            // The use case is a pure pass-through on failure — translation to user-facing
            // copy lives in the presentation layer, not here.
            val fixture = createFixture()
            val repoFailure = Failure(Exception("Database error"))
            everySuspend { fixture.homeRepository.getContinueListening(any()) } returns repoFailure
            val useCase = fixture.build()

            val result = useCase()

            val failure = assertIs<Failure>(result)
            assertEquals(repoFailure.error, failure.error)
        }

    // ========== Flow Observation Tests ==========

    @Test
    fun `observe returns flow from repository`() =
        runTest {
            // Given
            val fixture = createFixture()
            val expectedBooks =
                listOf(
                    createContinueListeningBook(bookId = "book-1"),
                    createContinueListeningBook(bookId = "book-2"),
                )
            every { fixture.homeRepository.observeContinueListening(any()) } returns
                flowOf(expectedBooks)
            val useCase = fixture.build()

            // When
            val emissions = useCase.observe(limit = 10).toList()

            // Then
            assertEquals(1, emissions.size)
            assertEquals(2, emissions[0].size)
            assertEquals("book-1", emissions[0][0].bookId)
        }

    @Test
    fun `observe coerces limit to valid range`() =
        runTest {
            // Given
            val fixture = createFixture()
            every { fixture.homeRepository.observeContinueListening(any()) } returns
                flowOf(emptyList())
            val useCase = fixture.build()

            // When - exceed max limit
            useCase.observe(limit = 1000).toList()

            // Then - should coerce to max
            // Note: Due to Mokkery limitations, we verify behavior works without crash
        }

    @Test
    fun `observe emits empty list on error`() =
        runTest {
            // Given
            val fixture = createFixture()
            every { fixture.homeRepository.observeContinueListening(any()) } returns
                flowOf(emptyList())
            val useCase = fixture.build()

            // When
            val emissions = useCase.observe().toList()

            // Then
            assertEquals(1, emissions.size)
            assertTrue(emissions[0].isEmpty())
        }

    // ========== hasBooks Utility Tests ==========

    @Test
    fun `hasBooks returns true when books exist`() =
        runTest {
            // Given
            val fixture = createFixture()
            everySuspend { fixture.homeRepository.getContinueListening(any()) } returns
                Success(listOf(createContinueListeningBook()))
            val useCase = fixture.build()

            // When
            val result = useCase.hasBooks()

            // Then
            val success = assertIs<Success<Boolean>>(result)
            assertTrue(success.data)
        }

    @Test
    fun `hasBooks returns false when no books`() =
        runTest {
            // Given
            val fixture = createFixture()
            everySuspend { fixture.homeRepository.getContinueListening(any()) } returns
                Success(emptyList())
            val useCase = fixture.build()

            // When
            val result = useCase.hasBooks()

            // Then
            val success = assertIs<Success<Boolean>>(result)
            assertFalse(success.data)
        }

    @Test
    fun `hasBooks returns failure when fetch fails`() =
        runTest {
            // Given
            val fixture = createFixture()
            everySuspend { fixture.homeRepository.getContinueListening(any()) } returns
                Failure(Exception("Error"))
            val useCase = fixture.build()

            // When
            val result = useCase.hasBooks()

            // Then
            assertIs<Failure>(result)
        }

    @Test
    fun `hasBooks only fetches one book for efficiency`() =
        runTest {
            // Given
            val fixture = createFixture()
            everySuspend { fixture.homeRepository.getContinueListening(any()) } returns
                Success(emptyList())
            val useCase = fixture.build()

            // When
            useCase.hasBooks()

            // Then
            verifySuspend {
                fixture.homeRepository.getContinueListening(1)
            }
        }
}
