package com.calypsan.listenup.client.domain.usecase.contributor

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.failureOf
import com.calypsan.listenup.client.checkIs
import com.calypsan.listenup.client.domain.repository.ContributorEditRepository
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

/**
 * Tests for DeleteContributorUseCase.
 *
 * The use case now dispatches through the RPC-backed [ContributorEditRepository] — mutation
 * belongs on the edit repo per the observe/edit split, and [ContributorEditRepositoryImpl]'s
 * `deleteContributor` is a pure `channel.call { it.deleteContributor(id) }` RPC dispatcher. The
 * client→RPC→server round trip for that path is covered end-to-end by
 * `ContributorDeleteCascadeE2ETest`; here we pin that the use case delegates to the edit repo
 * with the id wrapped into a typed [ContributorId], and that failures propagate untouched.
 */
class DeleteContributorUseCaseTest :
    FunSpec({

        test("delete contributor delegates to the edit repo with the wrapped ContributorId") {
            runTest {
                val editRepository: ContributorEditRepository = mock()
                everySuspend { editRepository.deleteContributor(any()) } returns AppResult.Success(Unit)
                val useCase = DeleteContributorUseCase(contributorEditRepository = editRepository)

                val result = useCase(contributorId = "contributor-456")

                checkIs<AppResult.Success<Unit>>(result)
                verifySuspend { editRepository.deleteContributor(ContributorId("contributor-456")) }
            }
        }

        test("delete contributor returns failure when the edit repo fails") {
            runTest {
                val editRepository: ContributorEditRepository = mock()
                everySuspend { editRepository.deleteContributor(any()) } returns failureOf("Contributor not found")
                val useCase = DeleteContributorUseCase(contributorEditRepository = editRepository)

                val result = useCase(contributorId = "contributor-123")

                val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                failure.message shouldBe "Contributor not found"
            }
        }

        test("delete contributor propagates a typed AppError from the edit repo") {
            runTest {
                val editRepository: ContributorEditRepository = mock()
                // Body-level message convention: pass a typed AppError so the user-facing message survives.
                everySuspend { editRepository.deleteContributor(any()) } returns
                    AppResult.Failure(
                        com.calypsan.listenup.api.error
                            .ValidationError(message = "Network error"),
                    )
                val useCase = DeleteContributorUseCase(contributorEditRepository = editRepository)

                val result = useCase(contributorId = "contributor-123")

                val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                failure.message shouldBe "Network error"
            }
        }
    })
