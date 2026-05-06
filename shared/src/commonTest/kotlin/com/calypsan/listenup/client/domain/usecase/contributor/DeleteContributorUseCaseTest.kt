package com.calypsan.listenup.client.domain.usecase.contributor

import com.calypsan.listenup.client.checkIs
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.core.Success
import com.calypsan.listenup.client.domain.repository.ContributorRepository
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import com.calypsan.listenup.client.core.failureOf

/**
 * Tests for DeleteContributorUseCase.
 *
 * Tests cover:
 * - Successful deletion
 * - Repository error propagation
 */
class DeleteContributorUseCaseTest {
    // ========== Test Fixtures ==========

    private fun createFixture(): TestFixture {
        val fixture = TestFixture()
        // Default: successful deletion
        everySuspend { fixture.contributorRepository.deleteContributor(any()) } returns Success(Unit)
        return fixture
    }

    private class TestFixture {
        val contributorRepository: ContributorRepository = mock()

        fun build(): DeleteContributorUseCase =
            DeleteContributorUseCase(
                contributorRepository = contributorRepository,
            )
    }

    // ========== Success Tests ==========

    @Test
    fun `delete contributor returns success`() =
        runTest {
            // Given
            val fixture = createFixture()
            val useCase = fixture.build()

            // When
            val result = useCase(contributorId = "contributor-123")

            // Then
            checkIs<Success<Unit>>(result)
        }

    @Test
    fun `delete contributor calls repository with correct ID`() =
        runTest {
            // Given
            val fixture = createFixture()
            val useCase = fixture.build()

            // When
            useCase(contributorId = "contributor-456")

            // Then
            verifySuspend { fixture.contributorRepository.deleteContributor("contributor-456") }
        }

    // ========== Error Handling Tests ==========

    @Test
    fun `delete contributor returns failure when repository fails`() =
        runTest {
            // Given
            val fixture = createFixture()
            everySuspend { fixture.contributorRepository.deleteContributor(any()) } returns
                failureOf("Contributor not found")
            val useCase = fixture.build()

            // When
            val result = useCase(contributorId = "contributor-123")

            // Then
            val failure = assertIs<Failure>(result)
            assertEquals("Contributor not found", failure.message)
        }

    @Test
    fun `delete contributor propagates repository exception`() =
        runTest {
            // Given
            val fixture = createFixture()
            // Body-level message convention: pass a typed AppError so the
            // user-facing message survives delegation.
            everySuspend { fixture.contributorRepository.deleteContributor(any()) } returns
                Failure(com.calypsan.listenup.api.error.ValidationError(message = "Network error"))
            val useCase = fixture.build()

            // When
            val result = useCase(contributorId = "contributor-123")

            // Then
            val failure = assertIs<Failure>(result)
            assertEquals("Network error", failure.message)
        }
}
