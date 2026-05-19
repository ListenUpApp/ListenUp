package com.calypsan.listenup.client.domain.usecase.contributor

import com.calypsan.listenup.core.Failure
import com.calypsan.listenup.core.Success
import com.calypsan.listenup.client.domain.model.Contributor
import com.calypsan.listenup.client.domain.model.ContributorMetadataResult
import com.calypsan.listenup.client.domain.model.ContributorWithMetadata
import com.calypsan.listenup.client.domain.repository.ContributorRepository
import com.calypsan.listenup.client.domain.repository.ImageRepository
import com.calypsan.listenup.client.domain.repository.MetadataRepository
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verify.VerifyMode
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

class ApplyContributorMetadataUseCaseTest :
    FunSpec({

        // ========== Test Fixture ==========

        class TestFixture {
            val metadataRepository: MetadataRepository = mock()
            val imageRepository: ImageRepository = mock()
            val contributorRepository: ContributorRepository = mock()

            fun build(): ApplyContributorMetadataUseCase =
                ApplyContributorMetadataUseCase(
                    metadataRepository = metadataRepository,
                    imageRepository = imageRepository,
                    contributorRepository = contributorRepository,
                )
        }

        fun createFixture(): TestFixture {
            val fixture = TestFixture()

            // Default stubs
            everySuspend { fixture.contributorRepository.getById(any()) } returns null
            everySuspend { fixture.contributorRepository.upsertContributor(any()) } returns Unit

            return fixture
        }

        // ========== Test Data Factories ==========

        fun createRequest(
            contributorId: String = "contributor-123",
            asin: String = "B001ABC123",
            imageUrl: String? = "https://audible.com/image.jpg",
            name: Boolean = true,
            biography: Boolean = true,
            image: Boolean = true,
        ): ApplyContributorMetadataRequest =
            ApplyContributorMetadataRequest(
                contributorId = contributorId,
                asin = asin,
                imageUrl = imageUrl,
                selections =
                    MetadataFieldSelections(
                        name = name,
                        biography = biography,
                        image = image,
                    ),
            )

        fun createContributorWithMetadata(
            id: String = "contributor-123",
            name: String = "Updated Author",
            biography: String? = "Updated biography",
            imageUrl: String? = "https://server.com/contributor-image.jpg",
            imageBlurHash: String? = null,
        ): ContributorWithMetadata =
            ContributorWithMetadata(
                id = id,
                name = name,
                biography = biography,
                imageUrl = imageUrl,
                imageBlurHash = imageBlurHash,
            )

        // ========== Validation Tests ==========

        test("returns failure when no fields selected") {
            runTest {
                // Given
                val fixture = createFixture()
                val useCase = fixture.build()
                val request = createRequest(name = false, biography = false, image = false)

                // When
                val result = useCase(request)

                // Then
                result.shouldBeInstanceOf<Failure>()
                result.message shouldBe "Please select at least one field to apply"
            }
        }

        // ========== Success Tests ==========

        test("applies metadata successfully") {
            runTest {
                // Given
                val fixture = createFixture()
                val contributorData = createContributorWithMetadata(imageUrl = null)
                everySuspend {
                    fixture.metadataRepository.applyContributorMetadata(any(), any(), any(), any(), any(), any())
                } returns ContributorMetadataResult.Success(contributor = contributorData)
                val useCase = fixture.build()

                // When
                val result = useCase(createRequest())

                // Then
                val success = result.shouldBeInstanceOf<Success<Contributor>>()
                success.data.id.value shouldBe "contributor-123"
                success.data.name shouldBe "Updated Author"
            }
        }

        test("passes correct parameters to repository") {
            runTest {
                // Given
                val fixture = createFixture()
                val contributorData = createContributorWithMetadata(imageUrl = null)
                everySuspend {
                    fixture.metadataRepository.applyContributorMetadata(any(), any(), any(), any(), any(), any())
                } returns ContributorMetadataResult.Success(contributor = contributorData)
                val useCase = fixture.build()

                val request =
                    createRequest(
                        contributorId = "my-contributor",
                        asin = "MY-ASIN",
                        imageUrl = "https://example.com/img.jpg",
                        name = true,
                        biography = false,
                        image = true,
                    )

                // When
                useCase(request)

                // Then
                verifySuspend {
                    fixture.metadataRepository.applyContributorMetadata(
                        contributorId = "my-contributor",
                        asin = "MY-ASIN",
                        imageUrl = "https://example.com/img.jpg",
                        applyName = true,
                        applyBiography = false,
                        applyImage = true,
                    )
                }
            }
        }

        test("upserts entity to repository") {
            runTest {
                // Given
                val fixture = createFixture()
                val contributorData = createContributorWithMetadata(imageUrl = null)
                everySuspend {
                    fixture.metadataRepository.applyContributorMetadata(any(), any(), any(), any(), any(), any())
                } returns ContributorMetadataResult.Success(contributor = contributorData)
                val useCase = fixture.build()

                // When
                useCase(createRequest())

                // Then
                verifySuspend {
                    fixture.contributorRepository.upsertContributor(any())
                }
            }
        }

        // ========== Image Download Tests ==========

        test("downloads and saves image when imageUrl present") {
            runTest {
                // Given
                val fixture = createFixture()
                val contributorData = createContributorWithMetadata(imageUrl = "https://server.com/image.jpg")
                val imageData = byteArrayOf(1, 2, 3, 4)

                everySuspend {
                    fixture.metadataRepository.applyContributorMetadata(any(), any(), any(), any(), any(), any())
                } returns ContributorMetadataResult.Success(contributor = contributorData)
                everySuspend {
                    fixture.imageRepository.downloadContributorImage("contributor-123")
                } returns Success(imageData)
                everySuspend {
                    fixture.imageRepository.saveContributorImage(any(), any())
                } returns Success(Unit)
                everySuspend {
                    fixture.imageRepository.getContributorImagePath("contributor-123")
                } returns "/images/contributor-123.jpg"

                val useCase = fixture.build()

                // When
                val result = useCase(createRequest())

                // Then
                val success = result.shouldBeInstanceOf<Success<Contributor>>()
                success.data.imagePath shouldBe "/images/contributor-123.jpg"
            }
        }

        test("skips image download when imageUrl is null") {
            runTest {
                // Given
                val fixture = createFixture()
                val contributorData = createContributorWithMetadata(imageUrl = null)
                everySuspend {
                    fixture.metadataRepository.applyContributorMetadata(any(), any(), any(), any(), any(), any())
                } returns ContributorMetadataResult.Success(contributor = contributorData)
                val useCase = fixture.build()

                // When
                useCase(createRequest())

                // Then - should not attempt to download
                verifySuspend(VerifyMode.not) {
                    fixture.imageRepository.downloadContributorImage(any())
                }
            }
        }

        test("succeeds when image download fails") {
            runTest {
                // Given
                val fixture = createFixture()
                val contributorData = createContributorWithMetadata(imageUrl = "https://server.com/image.jpg")

                everySuspend {
                    fixture.metadataRepository.applyContributorMetadata(any(), any(), any(), any(), any(), any())
                } returns ContributorMetadataResult.Success(contributor = contributorData)
                everySuspend {
                    fixture.imageRepository.downloadContributorImage(any())
                } returns Failure(Exception("Download failed"))

                val useCase = fixture.build()

                // When
                val result = useCase(createRequest())

                // Then - should succeed without image
                result.shouldBeInstanceOf<Success<Contributor>>()
            }
        }

        test("succeeds when image save fails") {
            runTest {
                // Given
                val fixture = createFixture()
                val contributorData = createContributorWithMetadata(imageUrl = "https://server.com/image.jpg")
                val imageData = byteArrayOf(1, 2, 3, 4)

                everySuspend {
                    fixture.metadataRepository.applyContributorMetadata(any(), any(), any(), any(), any(), any())
                } returns ContributorMetadataResult.Success(contributor = contributorData)
                everySuspend {
                    fixture.imageRepository.downloadContributorImage(any())
                } returns Success(imageData)
                everySuspend {
                    fixture.imageRepository.saveContributorImage(any(), any())
                } returns Failure(Exception("Save failed"))

                val useCase = fixture.build()

                // When
                val result = useCase(createRequest())

                // Then - should succeed without saved image
                result.shouldBeInstanceOf<Success<Contributor>>()
            }
        }

        // ========== API Error Tests ==========

        test("returns failure when repository returns error") {
            runTest {
                // Given
                val fixture = createFixture()
                everySuspend {
                    fixture.metadataRepository.applyContributorMetadata(any(), any(), any(), any(), any(), any())
                } returns ContributorMetadataResult.Error("Server rejected request")
                val useCase = fixture.build()

                // When
                val result = useCase(createRequest())

                // Then
                val failure = result.shouldBeInstanceOf<Failure>()
                failure.message shouldBe "Server rejected request"
            }
        }

        test("returns failure when repository returns disambiguation") {
            runTest {
                // Given
                val fixture = createFixture()
                everySuspend {
                    fixture.metadataRepository.applyContributorMetadata(any(), any(), any(), any(), any(), any())
                } returns ContributorMetadataResult.NeedsDisambiguation(emptyList())
                val useCase = fixture.build()

                // When
                val result = useCase(createRequest())

                // Then
                val failure = result.shouldBeInstanceOf<Failure>()
                failure.message shouldBe "Unexpected disambiguation request"
            }
        }

        // ========== Field Selection Tests ==========

        test("accepts request with only name selected") {
            runTest {
                // Given
                val fixture = createFixture()
                val contributorData = createContributorWithMetadata(imageUrl = null)
                everySuspend {
                    fixture.metadataRepository.applyContributorMetadata(any(), any(), any(), any(), any(), any())
                } returns ContributorMetadataResult.Success(contributor = contributorData)
                val useCase = fixture.build()

                val request = createRequest(name = true, biography = false, image = false)

                // When
                val result = useCase(request)

                // Then
                result.shouldBeInstanceOf<Success<Contributor>>()
            }
        }

        test("accepts request with only biography selected") {
            runTest {
                // Given
                val fixture = createFixture()
                val contributorData = createContributorWithMetadata(imageUrl = null)
                everySuspend {
                    fixture.metadataRepository.applyContributorMetadata(any(), any(), any(), any(), any(), any())
                } returns ContributorMetadataResult.Success(contributor = contributorData)
                val useCase = fixture.build()

                val request = createRequest(name = false, biography = true, image = false)

                // When
                val result = useCase(request)

                // Then
                result.shouldBeInstanceOf<Success<Contributor>>()
            }
        }

        test("accepts request with only image selected") {
            runTest {
                // Given
                val fixture = createFixture()
                val contributorData = createContributorWithMetadata(imageUrl = null)
                everySuspend {
                    fixture.metadataRepository.applyContributorMetadata(any(), any(), any(), any(), any(), any())
                } returns ContributorMetadataResult.Success(contributor = contributorData)
                val useCase = fixture.build()

                val request = createRequest(name = false, biography = false, image = true)

                // When
                val result = useCase(request)

                // Then
                result.shouldBeInstanceOf<Success<Contributor>>()
            }
        }
    })
