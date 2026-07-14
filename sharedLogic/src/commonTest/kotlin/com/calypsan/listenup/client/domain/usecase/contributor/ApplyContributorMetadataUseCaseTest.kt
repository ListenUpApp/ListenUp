package com.calypsan.listenup.client.domain.usecase.contributor

import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.client.domain.repository.MetadataRepository
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.ContributorId
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

class ApplyContributorMetadataUseCaseTest :
    FunSpec({

        fun buildUseCase(repo: MetadataRepository = mock()): ApplyContributorMetadataUseCase = ApplyContributorMetadataUseCase(metadataRepository = repo)

        fun buildRequest(
            contributorId: String = "contributor-123",
            asin: String = "B001ABC123",
            region: MetadataLocale = MetadataLocale.DEFAULT,
        ): ApplyContributorMetadataRequest =
            ApplyContributorMetadataRequest(
                contributorId = contributorId,
                asin = asin,
                region = region,
                selections = MetadataFieldSelections(),
            )

        test("delegates to MetadataRepository.applyContributorMetadata") {
            runTest {
                val repo = mock<MetadataRepository>()
                everySuspend {
                    repo.applyContributorMetadata(any(), any(), any())
                } returns AppResult.Success(Unit)

                buildUseCase(repo)(buildRequest())

                verifySuspend {
                    repo.applyContributorMetadata(
                        contributorId = ContributorId("contributor-123"),
                        asin = "B001ABC123",
                        region = MetadataLocale.DEFAULT,
                    )
                }
            }
        }

        test("returns Success when repository succeeds") {
            runTest {
                val repo = mock<MetadataRepository>()
                everySuspend { repo.applyContributorMetadata(any(), any(), any()) } returns AppResult.Success(Unit)

                val result = buildUseCase(repo)(buildRequest())

                result.shouldBeInstanceOf<AppResult.Success<Unit>>()
            }
        }

        test("returns Failure when repository returns Failure") {
            runTest {
                val error = ValidationError(message = "Not found.")
                val repo = mock<MetadataRepository>()
                everySuspend { repo.applyContributorMetadata(any(), any(), any()) } returns AppResult.Failure(error)

                val result = buildUseCase(repo)(buildRequest())

                val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                failure.error.message shouldBe "Not found."
            }
        }

        test("forwards the correct region from the request") {
            runTest {
                val repo = mock<MetadataRepository>()
                everySuspend { repo.applyContributorMetadata(any(), any(), any()) } returns AppResult.Success(Unit)

                buildUseCase(repo)(buildRequest(region = MetadataLocale("uk")))

                verifySuspend {
                    repo.applyContributorMetadata(any(), any(), MetadataLocale("uk"))
                }
            }
        }
    })
