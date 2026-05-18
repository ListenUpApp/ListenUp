package com.calypsan.listenup.client.domain.usecase.contributor

import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.core.Success
import com.calypsan.listenup.client.domain.model.ContributorSearchResult
import com.calypsan.listenup.client.domain.repository.ContributorEditRepository
import dev.mokkery.answering.returns
import dev.mokkery.answering.throws
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verify.VerifyMode
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
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

            // Default stubs for successful operations
            everySuspend {
                fixture.contributorEditRepository.updateContributor(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returns Success(Unit)
            everySuspend {
                fixture.contributorEditRepository.mergeContributor(any(), any())
            } returns Success(Unit)

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
            aliases: List<String> = emptyList(),
            newAliases: Set<String> = emptySet(),
            contributorsToMerge: Map<String, ContributorSearchResult> = emptyMap(),
        ): ContributorUpdateRequest =
            ContributorUpdateRequest(
                contributorId = contributorId,
                name = name,
                biography = biography,
                website = website,
                birthDate = birthDate,
                deathDate = deathDate,
                aliases = aliases,
                newAliases = newAliases,
                contributorsToMerge = contributorsToMerge,
            )

        fun createSearchResult(
            id: String = "contributor-456",
            name: String = "Other Author",
            bookCount: Int = 5,
        ): ContributorSearchResult =
            ContributorSearchResult(
                id = id,
                name = name,
                bookCount = bookCount,
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
                result.shouldBeInstanceOf<Success<Unit>>()
                verifySuspend {
                    fixture.contributorEditRepository.updateContributor(
                        "contributor-123",
                        "Test Author",
                        "Test biography",
                        "https://example.com",
                        "1970-01-01",
                        null,
                        emptyList(),
                    )
                }
            }
        }

        test("converts blank biography to null") {
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
                        any(),
                        null, // biography should be null
                        any(),
                        any(),
                        any(),
                        any(),
                    )
                }
            }
        }

        test("converts blank website to null") {
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
                        any(),
                        any(),
                        null, // website should be null
                        any(),
                        any(),
                        any(),
                    )
                }
            }
        }

        // ========== Alias Merge Tests ==========

        test("merges contributor when alias is selected from search") {
            runTest {
                // Given
                val fixture = createFixture()
                val useCase = fixture.build()
                val searchResult = createSearchResult(id = "source-id", name = "Pen Name")

                val request =
                    createRequest(
                        contributorId = "target-id",
                        aliases = listOf("Pen Name"),
                        newAliases = setOf("Pen Name"),
                        contributorsToMerge = mapOf("pen name" to searchResult),
                    )

                // When
                val result = useCase(request)

                // Then
                result.shouldBeInstanceOf<Success<Unit>>()
                verifySuspend {
                    fixture.contributorEditRepository.mergeContributor(
                        targetId = "target-id",
                        sourceId = "source-id",
                    )
                }
            }
        }

        test("does not merge when alias name matches current contributor") {
            runTest {
                // Given
                val fixture = createFixture()
                val useCase = fixture.build()
                val searchResult = createSearchResult(id = "contributor-123", name = "Same Author")

                val request =
                    createRequest(
                        contributorId = "contributor-123",
                        aliases = listOf("Same Author"),
                        newAliases = setOf("Same Author"),
                        contributorsToMerge = mapOf("same author" to searchResult),
                    )

                // When
                useCase(request)

                // Then - should not merge with self
                verifySuspend(VerifyMode.not) {
                    fixture.contributorEditRepository.mergeContributor(any(), any())
                }
            }
        }

        test("does not merge when alias not in contributorsToMerge map") {
            runTest {
                // Given
                val fixture = createFixture()
                val useCase = fixture.build()

                val request =
                    createRequest(
                        aliases = listOf("Manual Alias"),
                        newAliases = setOf("Manual Alias"),
                        contributorsToMerge = emptyMap(), // No tracked contributor
                    )

                // When
                useCase(request)

                // Then - should not attempt merge
                verifySuspend(VerifyMode.not) {
                    fixture.contributorEditRepository.mergeContributor(any(), any())
                }
            }
        }

        test("continues update even when merge fails") {
            runTest {
                // Given
                val fixture = createFixture()
                everySuspend {
                    fixture.contributorEditRepository.mergeContributor(any(), any())
                } returns Failure(Exception("Merge failed"))
                val useCase = fixture.build()

                val searchResult = createSearchResult(id = "source-id", name = "Pen Name")
                val request =
                    createRequest(
                        contributorId = "target-id",
                        aliases = listOf("Pen Name"),
                        newAliases = setOf("Pen Name"),
                        contributorsToMerge = mapOf("pen name" to searchResult),
                    )

                // When
                val result = useCase(request)

                // Then - should still succeed (merge is non-critical)
                result.shouldBeInstanceOf<Success<Unit>>()
                verifySuspend {
                    fixture.contributorEditRepository.updateContributor(
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                    )
                }
            }
        }

        test("merges multiple contributors when multiple aliases selected") {
            runTest {
                // Given
                val fixture = createFixture()
                val useCase = fixture.build()

                val request =
                    createRequest(
                        contributorId = "target-id",
                        aliases = listOf("Alias One", "Alias Two"),
                        newAliases = setOf("Alias One", "Alias Two"),
                        contributorsToMerge =
                            mapOf(
                                "alias one" to createSearchResult(id = "source-1", name = "Alias One"),
                                "alias two" to createSearchResult(id = "source-2", name = "Alias Two"),
                            ),
                    )

                // When
                useCase(request)

                // Then
                verifySuspend { fixture.contributorEditRepository.mergeContributor("target-id", "source-1") }
                verifySuspend { fixture.contributorEditRepository.mergeContributor("target-id", "source-2") }
            }
        }

        test("only merges new aliases not existing ones") {
            runTest {
                // Given
                val fixture = createFixture()
                val useCase = fixture.build()

                val request =
                    createRequest(
                        contributorId = "target-id",
                        aliases = listOf("Existing Alias", "New Alias"),
                        newAliases = setOf("New Alias"), // Only "New Alias" is new
                        contributorsToMerge =
                            mapOf(
                                "existing alias" to createSearchResult(id = "source-1", name = "Existing Alias"),
                                "new alias" to createSearchResult(id = "source-2", name = "New Alias"),
                            ),
                    )

                // When
                useCase(request)

                // Then - only merge the new alias
                verifySuspend(VerifyMode.not) { fixture.contributorEditRepository.mergeContributor("target-id", "source-1") }
                verifySuspend { fixture.contributorEditRepository.mergeContributor("target-id", "source-2") }
            }
        }

        // ========== Error Handling Tests ==========

        test("returns failure when update fails") {
            runTest {
                // Given
                val fixture = createFixture()
                everySuspend {
                    fixture.contributorEditRepository.updateContributor(
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                    )
                } returns Failure(Exception("Update failed"))
                val useCase = fixture.build()

                // When
                val result = useCase(createRequest())

                // Then
                result.shouldBeInstanceOf<Failure>()
            }
        }

        test("returns failure when update throws exception") {
            runTest {
                // Given
                val fixture = createFixture()
                everySuspend {
                    fixture.contributorEditRepository.updateContributor(
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                    )
                } throws RuntimeException("Network error")
                val useCase = fixture.build()

                // When
                val result = useCase(createRequest())

                // Then
                result.shouldBeInstanceOf<Failure>()
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
                result.shouldBeInstanceOf<Success<Unit>>()
            }
        }

        test("handles empty aliases list") {
            runTest {
                // Given
                val fixture = createFixture()
                val useCase = fixture.build()

                val request = createRequest(aliases = emptyList())

                // When
                val result = useCase(request)

                // Then
                result.shouldBeInstanceOf<Success<Unit>>()
                verifySuspend {
                    fixture.contributorEditRepository.updateContributor(
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        emptyList(),
                    )
                }
            }
        }
    })
