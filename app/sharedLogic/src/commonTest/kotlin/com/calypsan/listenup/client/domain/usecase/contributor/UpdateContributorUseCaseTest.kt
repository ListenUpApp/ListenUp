package com.calypsan.listenup.client.domain.usecase.contributor

import com.calypsan.listenup.api.dto.ContributorUpdate
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.domain.repository.ContributorEditRepository
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

class UpdateContributorUseCaseTest :
    FunSpec({

        // ========== Test Fixture ==========

        class TestFixture {
            val contributorEditRepository: ContributorEditRepository = mock()

            fun build(): UpdateContributorUseCase =
                UpdateContributorUseCase(
                    contributorEditRepository = contributorEditRepository,
                )
        }

        fun createFixture(): TestFixture {
            val fixture = TestFixture()

            // Default stub for successful update
            everySuspend {
                fixture.contributorEditRepository.updateContributor(any(), any())
            } returns AppResult.Success(Unit)

            return fixture
        }

        // ========== Test Data Factories ==========

        fun createRequest(
            contributorId: String = "contributor-123",
            name: String = "Test Author",
            biography: String? = "Test biography",
            website: String? = "https://example.com",
            birthDate: String? = "1970-01-01",
            deathDate: String? = null,
        ): ContributorUpdateRequest =
            ContributorUpdateRequest(
                contributorId = contributorId,
                name = name,
                biography = biography,
                website = website,
                birthDate = birthDate,
                deathDate = deathDate,
            )

        // ========== Success Tests ==========

        test("updates contributor successfully") {
            runTest {
                // Given
                val fixture = createFixture()
                val useCase = fixture.build()

                // When
                val result = useCase(createRequest())

                // Then
                result.shouldBeInstanceOf<AppResult.Success<Unit>>()
                verifySuspend {
                    fixture.contributorEditRepository.updateContributor(
                        ContributorId("contributor-123"),
                        ContributorUpdate(
                            name = "Test Author",
                            description = "Test biography",
                            website = "https://example.com",
                            birthDate = "1970-01-01",
                            deathDate = null,
                        ),
                    )
                }
            }
        }

        test("converts blank biography to null in patch") {
            runTest {
                // Given
                val fixture = createFixture()
                val useCase = fixture.build()

                // When
                useCase(createRequest(biography = "  "))

                // Then
                verifySuspend {
                    fixture.contributorEditRepository.updateContributor(
                        any(),
                        ContributorUpdate(
                            name = "Test Author",
                            description = null,
                            website = "https://example.com",
                            birthDate = "1970-01-01",
                            deathDate = null,
                        ),
                    )
                }
            }
        }

        test("converts blank website to null in patch") {
            runTest {
                // Given
                val fixture = createFixture()
                val useCase = fixture.build()

                // When
                useCase(createRequest(website = ""))

                // Then
                verifySuspend {
                    fixture.contributorEditRepository.updateContributor(
                        any(),
                        ContributorUpdate(
                            name = "Test Author",
                            description = "Test biography",
                            website = null,
                            birthDate = "1970-01-01",
                            deathDate = null,
                        ),
                    )
                }
            }
        }

        // ========== Error Handling Tests ==========

        test("returns failure when update fails") {
            runTest {
                // Given
                val fixture = createFixture()
                everySuspend {
                    fixture.contributorEditRepository.updateContributor(any(), any())
                } returns
                    AppResult.Failure(
                        com.calypsan.listenup.api.error
                            .ValidationError(message = "Update failed"),
                    )
                val useCase = fixture.build()

                // When
                val result = useCase(createRequest())

                // Then
                result.shouldBeInstanceOf<AppResult.Failure>()
                result.message shouldBe "Update failed"
            }
        }

        // ========== Empty/Null Field Tests ==========

        test("handles null optional fields") {
            runTest {
                // Given
                val fixture = createFixture()
                val useCase = fixture.build()

                val request =
                    createRequest(
                        biography = null,
                        website = null,
                        birthDate = null,
                        deathDate = null,
                    )

                // When
                val result = useCase(request)

                // Then
                result.shouldBeInstanceOf<AppResult.Success<Unit>>()
            }
        }
    })
