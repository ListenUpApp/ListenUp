package com.calypsan.listenup.client.presentation.contributoredit

import app.cash.turbine.test
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.core.Success
import com.calypsan.listenup.client.domain.model.Contributor
import com.calypsan.listenup.client.domain.model.ContributorSearchResponse
import com.calypsan.listenup.client.domain.model.ContributorSearchResult
import com.calypsan.listenup.client.domain.repository.ContributorEditRepository
import com.calypsan.listenup.client.domain.repository.ContributorRepository
import com.calypsan.listenup.client.domain.repository.ImageRepository
import com.calypsan.listenup.client.domain.usecase.contributor.UpdateContributorUseCase
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verify.VerifyMode
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
 * - Adding aliases
 * - Merging contributors via offline-first repository
 * - Handling merge errors gracefully
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContributorEditViewModelTest :
    FunSpec({

        val testDispatcher = StandardTestDispatcher()

        // ========== Test Fixture ==========

        class TestFixture {
            val contributorRepository: ContributorRepository = mock()
            val contributorEditRepository: ContributorEditRepository = mock()
            val updateContributorUseCase: UpdateContributorUseCase = mock()
            val imageRepository: ImageRepository = mock()

            fun build(): ContributorEditViewModel =
                ContributorEditViewModel(
                    contributorRepository = contributorRepository,
                    contributorEditRepository = contributorEditRepository,
                    updateContributorUseCase = updateContributorUseCase,
                    imageRepository = imageRepository,
                )
        }

        fun createFixture(): TestFixture = TestFixture()

        // ========== Test Data Factories ==========

        fun createContributor(
            id: String = "contributor-1",
            name: String = "Stephen King",
            aliases: List<String> = emptyList(),
        ): Contributor =
            Contributor(
                id =
                    com.calypsan.listenup.client.core
                        .ContributorId(id),
                name = name,
                description = null,
                imagePath = null,
                imageBlurHash = null,
                website = null,
                birthDate = null,
                deathDate = null,
                aliases = aliases,
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
                val contributor =
                    createContributor(
                        name = "Stephen King",
                        aliases = listOf("Richard Bachman", "John Swithen"),
                    )
                everySuspend { fixture.contributorRepository.getById("contributor-1") } returns contributor

                val viewModel = fixture.build()

                // When
                viewModel.loadContributor("contributor-1")
                advanceUntilIdle()

                // Then
                val state = viewModel.state.value
                (state.isLoading) shouldBe false
                state.name shouldBe "Stephen King"
                state.aliases.size shouldBe 2
                (state.aliases.contains("Richard Bachman")) shouldBe true
                (state.aliases.contains("John Swithen")) shouldBe true
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
                (state.isLoading) shouldBe false
                state.error shouldBe "Contributor not found"
            }
        }

        // ========== Alias Selection Tests ==========

        test("selecting alias from search adds to aliases list and marks for merge") {
            runTest {
                // Given
                val fixture = createFixture()
                val contributor = createContributor()
                everySuspend { fixture.contributorRepository.getById("contributor-1") } returns contributor

                val searchResult =
                    ContributorSearchResult(
                        id = "contributor-2",
                        name = "Richard Bachman",
                        bookCount = 5,
                    )
                everySuspend { fixture.contributorRepository.searchContributors(any(), any()) } returns
                    ContributorSearchResponse(
                        contributors = listOf(searchResult),
                        isOfflineResult = false,
                        tookMs = 10L,
                    )

                val viewModel = fixture.build()
                viewModel.loadContributor("contributor-1")
                advanceUntilIdle()

                // When - select alias from autocomplete
                viewModel.onEvent(ContributorEditUiEvent.AliasSelected(searchResult))

                // Then
                val state = viewModel.state.value
                (state.aliases.contains("Richard Bachman")) shouldBe true
                (state.hasChanges) shouldBe true
            }
        }

        // ========== Save with Merge Tests ==========

        test("save calls use case for new aliases from autocomplete") {
            runTest {
                // Given
                val fixture = createFixture()
                val contributor = createContributor()

                everySuspend { fixture.contributorRepository.getById("contributor-1") } returns contributor
                everySuspend { fixture.updateContributorUseCase.invoke(any()) } returns Success(Unit)

                val viewModel = fixture.build()
                viewModel.loadContributor("contributor-1")
                advanceUntilIdle()

                // Add alias via autocomplete (which tracks for merge)
                val searchResult =
                    ContributorSearchResult(
                        id = "contributor-2",
                        name = "Richard Bachman",
                        bookCount = 5,
                    )
                viewModel.onEvent(ContributorEditUiEvent.AliasSelected(searchResult))

                // When
                viewModel.onEvent(ContributorEditUiEvent.Save)
                advanceUntilIdle()

                // Then - verify use case was called
                verifySuspend(VerifyMode.exactly(1)) {
                    fixture.updateContributorUseCase.invoke(any())
                }
            }
        }

        test("save handles use case failure gracefully") {
            runTest {
                // Given
                val fixture = createFixture()
                val contributor = createContributor()

                everySuspend { fixture.contributorRepository.getById("contributor-1") } returns contributor
                // Use case fails
                // Body-level message convention: pass a typed AppError so the
                // user-facing message survives delegation to the ViewModel.
                everySuspend { fixture.updateContributorUseCase.invoke(any()) } returns
                    Failure(
                        com.calypsan.listenup.api.error
                            .ValidationError(message = "Network error"),
                    )

                val viewModel = fixture.build()
                viewModel.loadContributor("contributor-1")
                advanceUntilIdle()

                // Add alias via autocomplete
                val searchResult =
                    ContributorSearchResult(
                        id = "contributor-2",
                        name = "Richard Bachman",
                        bookCount = 5,
                    )
                viewModel.onEvent(ContributorEditUiEvent.AliasSelected(searchResult))

                // When
                viewModel.onEvent(ContributorEditUiEvent.Save)
                advanceUntilIdle()

                // Then - error should be shown (ViewModel prepends "Failed to save: ")
                viewModel.state.value.error shouldBe "Failed to save: Network error"
            }
        }

        test("manual alias entry calls use case") {
            runTest {
                // Given
                val fixture = createFixture()
                val contributor = createContributor()

                everySuspend { fixture.contributorRepository.getById("contributor-1") } returns contributor
                everySuspend { fixture.updateContributorUseCase.invoke(any()) } returns Success(Unit)

                val viewModel = fixture.build()
                viewModel.loadContributor("contributor-1")
                advanceUntilIdle()

                // Add alias via manual text entry (not autocomplete)
                viewModel.onEvent(ContributorEditUiEvent.AliasEntered("New Pen Name"))

                // When
                viewModel.onEvent(ContributorEditUiEvent.Save)
                advanceUntilIdle()

                // Then - use case should be called
                verifySuspend(VerifyMode.exactly(1)) {
                    fixture.updateContributorUseCase.invoke(any())
                }
            }
        }

        test("removing alias calls repository unmerge") {
            runTest {
                // Given
                val fixture = createFixture()
                val contributor = createContributor(aliases = listOf("Richard Bachman"))

                everySuspend { fixture.contributorRepository.getById("contributor-1") } returns contributor
                everySuspend { fixture.contributorEditRepository.unmergeContributor(any(), any()) } returns
                    Success(Unit)

                val viewModel = fixture.build()
                viewModel.loadContributor("contributor-1")
                advanceUntilIdle()

                // Verify alias is present
                (
                    viewModel.state.value.aliases
                        .contains("Richard Bachman")
                ) shouldBe true
                (viewModel.state.value.hasChanges) shouldBe false

                // When - removing an original alias calls the repository unmerge
                viewModel.onEvent(ContributorEditUiEvent.RemoveAlias("Richard Bachman"))
                advanceUntilIdle()

                // Then
                (
                    viewModel.state.value.aliases
                        .contains("Richard Bachman")
                ) shouldBe false
                // Verify unmerge was called
                verifySuspend(VerifyMode.exactly(1)) {
                    fixture.contributorEditRepository.unmergeContributor("contributor-1", "Richard Bachman")
                }
            }
        }

        test("save calls use case with correct parameters") {
            runTest {
                // Given
                val fixture = createFixture()
                val contributor = createContributor()

                everySuspend { fixture.contributorRepository.getById("contributor-1") } returns contributor
                everySuspend { fixture.updateContributorUseCase.invoke(any()) } returns Success(Unit)

                val viewModel = fixture.build()
                viewModel.loadContributor("contributor-1")
                advanceUntilIdle()

                // Change name
                viewModel.onEvent(ContributorEditUiEvent.NameChanged("Stephen Edwin King"))

                // When / Then
                viewModel.navActions.test {
                    viewModel.onEvent(ContributorEditUiEvent.Save)
                    advanceUntilIdle()

                    // Verify use case was called
                    verifySuspend(VerifyMode.exactly(1)) {
                        fixture.updateContributorUseCase.invoke(any())
                    }

                    // Verify navigation
                    awaitItem() shouldBe ContributorEditNavAction.SaveSuccess
                }
            }
        }
    })
