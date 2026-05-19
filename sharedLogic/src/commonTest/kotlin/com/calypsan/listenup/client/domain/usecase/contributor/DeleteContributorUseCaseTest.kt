package com.calypsan.listenup.client.domain.usecase.contributor

import com.calypsan.listenup.client.checkIs
import com.calypsan.listenup.core.Failure
import com.calypsan.listenup.core.Success
import com.calypsan.listenup.client.domain.repository.ContributorRepository
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import com.calypsan.listenup.core.failureOf

/**
 * Tests for DeleteContributorUseCase.
 *
 * Tests cover:
 * - Successful deletion
 * - Repository error propagation
 */
class DeleteContributorUseCaseTest :
    FunSpec({

        // ========== Test Fixtures ==========

        class TestFixture {
            val contributorRepository: ContributorRepository = mock()

            fun build(): DeleteContributorUseCase =
                DeleteContributorUseCase(
                    contributorRepository = contributorRepository,
                )
        }

        fun createFixture(): TestFixture {
            val fixture = TestFixture()
            // Default: successful deletion
            everySuspend { fixture.contributorRepository.deleteContributor(any()) } returns Success(Unit)
            return fixture
        }

        // ========== Success Tests ==========

        test("delete contributor returns success") {
            runTest {
                // Given
                val fixture = createFixture()
                val useCase = fixture.build()

                // When
                val result = useCase(contributorId = "contributor-123")

                // Then
                checkIs<Success<Unit>>(result)
            }
        }

        test("delete contributor calls repository with correct ID") {
            runTest {
                // Given
                val fixture = createFixture()
                val useCase = fixture.build()

                // When
                useCase(contributorId = "contributor-456")

                // Then
                verifySuspend { fixture.contributorRepository.deleteContributor("contributor-456") }
            }
        }

        // ========== Error Handling Tests ==========

        test("delete contributor returns failure when repository fails") {
            runTest {
                // Given
                val fixture = createFixture()
                everySuspend { fixture.contributorRepository.deleteContributor(any()) } returns
                    failureOf("Contributor not found")
                val useCase = fixture.build()

                // When
                val result = useCase(contributorId = "contributor-123")

                // Then
                val failure = result.shouldBeInstanceOf<Failure>()
                failure.message shouldBe "Contributor not found"
            }
        }

        test("delete contributor propagates repository exception") {
            runTest {
                // Given
                val fixture = createFixture()
                // Body-level message convention: pass a typed AppError so the
                // user-facing message survives delegation.
                everySuspend { fixture.contributorRepository.deleteContributor(any()) } returns
                    Failure(
                        com.calypsan.listenup.api.error
                            .ValidationError(message = "Network error"),
                    )
                val useCase = fixture.build()

                // When
                val result = useCase(contributorId = "contributor-123")

                // Then
                val failure = result.shouldBeInstanceOf<Failure>()
                failure.message shouldBe "Network error"
            }
        }
    })
