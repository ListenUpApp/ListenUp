package com.calypsan.listenup.client.presentation.contributoredit

import com.calypsan.listenup.api.result.AppResult
import app.cash.turbine.test
import com.calypsan.listenup.client.data.local.db.ContributorAliasDao
import com.calypsan.listenup.client.data.local.db.ContributorDao
import com.calypsan.listenup.client.domain.model.Contributor
import com.calypsan.listenup.client.domain.repository.ContributorEditRepository
import com.calypsan.listenup.client.domain.repository.ContributorRepository
import com.calypsan.listenup.client.domain.repository.ImageRepository
import com.calypsan.listenup.client.domain.usecase.contributor.UpdateContributorUseCase
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.core.error.ErrorBus
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verify.VerifyMode
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Tests for ContributorEditViewModel.
 *
 * Tests cover:
 * - Loading contributor for editing
 * - Saving metadata via UpdateContributorUseCase
 * - Failure-branch error surfacing
 *
 * Alias-related tests were removed with Books-C1's deletion of client-side
 * merge/unmerge — server-canonical alias management lands in Books-C2.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContributorEditViewModelTest :
    FunSpec({

        val testDispatcher = StandardTestDispatcher()

        // ========== Test Fixture ==========

        class TestFixture {
            val contributorRepository: ContributorRepository = mock()
            val updateContributorUseCase: UpdateContributorUseCase = mock()
            val imageRepository: ImageRepository = mock()
            val contributorEditRepository: ContributorEditRepository = mock()
            val contributorAliasDao: ContributorAliasDao =
                mock {
                    every { observeForContributor(any()) } returns flowOf(emptyList())
                }
            val contributorDao: ContributorDao =
                mock {
                    every { observeAll() } returns flowOf(emptyList())
                }
            val errorBus: ErrorBus = ErrorBus()

            fun build(): ContributorEditViewModel =
                ContributorEditViewModel(
                    contributorRepository = contributorRepository,
                    updateContributorUseCase = updateContributorUseCase,
                    imageRepository = imageRepository,
                    contributorEditRepository = contributorEditRepository,
                    contributorAliasDao = contributorAliasDao,
                    contributorDao = contributorDao,
                    errorBus = errorBus,
                )
        }

        fun createFixture(): TestFixture = TestFixture()

        // ========== Test Data Factories ==========

        fun createContributor(
            id: String = "contributor-1",
            name: String = "Stephen King",
        ): Contributor =
            Contributor(
                id =
                    com.calypsan.listenup.core
                        .ContributorId(id),
                name = name,
                description = null,
                imagePath = null,
                website = null,
                birthDate = null,
                deathDate = null,
                aliases = emptyList(),
            )

        beforeTest {
            Dispatchers.setMain(testDispatcher)
        }

        afterTest {
            Dispatchers.resetMain()
        }

        // ========== Load Contributor Tests ==========

        test("loadContributor populates state with contributor data") {
            runTest {
                // Given
                val fixture = createFixture()
                val contributor = createContributor(name = "Stephen King")
                everySuspend { fixture.contributorRepository.getById("contributor-1") } returns contributor

                val viewModel = fixture.build()

                // When
                viewModel.loadContributor("contributor-1")
                advanceUntilIdle()

                // Then
                val state = viewModel.state.value
                state.isLoading shouldBe false
                state.name shouldBe "Stephen King"
            }
        }

        test("loadContributor handles contributor not found") {
            runTest {
                // Given
                val fixture = createFixture()
                everySuspend { fixture.contributorRepository.getById("nonexistent") } returns null

                val viewModel = fixture.build()

                // When
                viewModel.loadContributor("nonexistent")
                advanceUntilIdle()

                // Then
                val state = viewModel.state.value
                state.isLoading shouldBe false
                state.error shouldBe "Contributor not found"
            }
        }

        // ========== Save Tests ==========

        test("save calls use case with correct parameters and navigates on success") {
            runTest {
                // Given
                val fixture = createFixture()
                val contributor = createContributor()

                everySuspend { fixture.contributorRepository.getById("contributor-1") } returns contributor
                everySuspend { fixture.updateContributorUseCase.invoke(any()) } returns AppResult.Success(Unit)

                val viewModel = fixture.build()
                viewModel.loadContributor("contributor-1")
                advanceUntilIdle()

                // Change name to mark hasChanges
                viewModel.onEvent(ContributorEditUiEvent.NameChanged("Stephen Edwin King"))

                // When / Then
                viewModel.navActions.test {
                    viewModel.onEvent(ContributorEditUiEvent.Save)
                    advanceUntilIdle()

                    verifySuspend(VerifyMode.exactly(1)) {
                        fixture.updateContributorUseCase.invoke(any())
                    }

                    awaitItem() shouldBe ContributorEditNavAction.SaveSuccess
                }
            }
        }

        test("save handles use case failure gracefully") {
            runTest {
                // Given
                val fixture = createFixture()
                val contributor = createContributor()

                everySuspend { fixture.contributorRepository.getById("contributor-1") } returns contributor
                // Body-level message convention: pass a typed AppError so the
                // user-facing message survives delegation to the ViewModel.
                everySuspend { fixture.updateContributorUseCase.invoke(any()) } returns
                    AppResult.Failure(
                        com.calypsan.listenup.api.error
                            .ValidationError(message = "Network error"),
                    )

                val viewModel = fixture.build()
                viewModel.loadContributor("contributor-1")
                advanceUntilIdle()

                // Mark hasChanges so save actually runs
                viewModel.onEvent(ContributorEditUiEvent.NameChanged("Stephen Edwin King"))

                // When
                viewModel.onEvent(ContributorEditUiEvent.Save)
                advanceUntilIdle()

                // Then - error should be shown (ViewModel prepends "Failed to save: ")
                viewModel.state.value.error shouldBe "Failed to save: Network error"
            }
        }

        // ========== Merge direction ==========

        test("merge folds the CHOSEN contributor into the VIEWED one (viewed is the canonical target)") {
            runTest {
                // Given: we're on contributor "viewed-1"'s edit page.
                val fixture = createFixture()
                val viewed = createContributor(id = "viewed-1", name = "J.K. Rowling")
                everySuspend { fixture.contributorRepository.getById("viewed-1") } returns viewed
                everySuspend { fixture.contributorEditRepository.mergeContributor(any(), any()) } returns
                    AppResult.Success(Unit)

                val viewModel = fixture.build()
                viewModel.loadContributor("viewed-1")
                advanceUntilIdle()

                // When: the user picks "chosen-2" (e.g. Robert Galbraith) to add as an alias.
                viewModel.onEvent(
                    ContributorEditUiEvent.MergeInto(
                        com.calypsan.listenup.core
                            .ContributorId("chosen-2"),
                    ),
                )
                advanceUntilIdle()

                // Then: the chosen contributor is the merge SOURCE (folded in / soft-deleted) and the
                // viewed contributor is the TARGET (canonical survivor). Inverting these would delete
                // the page the user is on — the reported bug.
                verifySuspend(VerifyMode.exactly(1)) {
                    fixture.contributorEditRepository.mergeContributor(
                        com.calypsan.listenup.core
                            .ContributorId("chosen-2"),
                        com.calypsan.listenup.core
                            .ContributorId("viewed-1"),
                    )
                }
            }
        }

        test("save failure emits typed AppError to ErrorBus for global snackbar") {
            runTest {
                // Given
                val fixture = createFixture()
                val contributor = createContributor()

                everySuspend { fixture.contributorRepository.getById("contributor-1") } returns contributor
                everySuspend { fixture.updateContributorUseCase.invoke(any()) } returns
                    AppResult.Failure(
                        com.calypsan.listenup.api.error
                            .ValidationError(message = "Network error"),
                    )

                val viewModel = fixture.build()
                viewModel.loadContributor("contributor-1")
                advanceUntilIdle()
                viewModel.onEvent(ContributorEditUiEvent.NameChanged("Stephen Edwin King"))

                // When / Then
                fixture.errorBus.errors.test {
                    viewModel.onEvent(ContributorEditUiEvent.Save)
                    advanceUntilIdle()
                    awaitItem().message shouldBe "Network error"
                }
            }
        }
    })
