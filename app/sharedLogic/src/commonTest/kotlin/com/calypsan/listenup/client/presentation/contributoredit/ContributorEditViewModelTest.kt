package com.calypsan.listenup.client.presentation.contributoredit

import com.calypsan.listenup.api.result.AppResult
import app.cash.turbine.test
import com.calypsan.listenup.client.data.local.db.ContributorAliasDao
import com.calypsan.listenup.client.data.local.db.ContributorDao
import com.calypsan.listenup.client.data.local.db.ContributorEntity
import com.calypsan.listenup.client.domain.model.Contributor
import com.calypsan.listenup.client.domain.repository.ContributorEditRepository
import com.calypsan.listenup.client.domain.repository.ContributorRepository
import com.calypsan.listenup.client.domain.repository.ImageRepository
import com.calypsan.listenup.client.domain.repository.ImageStagingRepository
import com.calypsan.listenup.client.domain.usecase.contributor.UpdateContributorUseCase
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.core.error.ErrorBus
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verify.VerifyMode
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
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
            val imageStagingRepository: ImageStagingRepository = mock()
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
                    imageStagingRepository = imageStagingRepository,
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

        fun createContributorEntity(
            id: String,
            name: String,
            deletedAt: Long? = null,
        ): ContributorEntity =
            ContributorEntity(
                id =
                    com.calypsan.listenup.core
                        .ContributorId(id),
                name = name,
                description = null,
                imagePath = null,
                deletedAt = deletedAt,
                createdAt = Timestamp(0),
                updatedAt = Timestamp(0),
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

        // ========== Image staging ==========

        test("picking an image stages it for preview WITHOUT uploading to the server") {
            runTest {
                // Given
                val fixture = createFixture()
                val contributor = createContributor()
                everySuspend { fixture.contributorRepository.getById("contributor-1") } returns contributor
                everySuspend {
                    fixture.imageStagingRepository.saveContributorImageStaging(any(), any())
                } returns AppResult.Success(Unit)
                every {
                    fixture.imageStagingRepository.getContributorImageStagingPath("contributor-1")
                } returns "/staging/contributor-1_staging.jpg"

                val viewModel = fixture.build()
                viewModel.loadContributor("contributor-1")
                advanceUntilIdle()

                // When
                viewModel.onEvent(ContributorEditUiEvent.UploadImage(byteArrayOf(1, 2, 3), "avatar.jpg"))
                advanceUntilIdle()

                // Then - staged for preview, marked changed, and NOT uploaded yet
                val state = viewModel.state.value
                state.stagingImagePath shouldBe "/staging/contributor-1_staging.jpg"
                state.displayImagePath shouldBe "/staging/contributor-1_staging.jpg"
                state.pendingImageFilename shouldBe "avatar.jpg"
                state.hasChanges shouldBe true
                verifySuspend(VerifyMode.not) {
                    fixture.imageRepository.uploadContributorImage(any(), any(), any())
                }
            }
        }

        test("saving a staged image commits it locally and uploads it to the server") {
            runTest {
                // Given a staged image
                val fixture = createFixture()
                val contributor = createContributor()
                everySuspend { fixture.contributorRepository.getById("contributor-1") } returns contributor
                everySuspend {
                    fixture.imageStagingRepository.saveContributorImageStaging(any(), any())
                } returns AppResult.Success(Unit)
                every {
                    fixture.imageStagingRepository.getContributorImageStagingPath("contributor-1")
                } returns "/staging/contributor-1_staging.jpg"
                everySuspend { fixture.updateContributorUseCase.invoke(any()) } returns AppResult.Success(Unit)
                everySuspend {
                    fixture.imageStagingRepository.commitContributorImageStaging("contributor-1")
                } returns AppResult.Success(Unit)
                everySuspend {
                    fixture.imageRepository.uploadContributorImage(any(), any(), any())
                } returns AppResult.Success("https://example/photo.jpg")

                val viewModel = fixture.build()
                viewModel.loadContributor("contributor-1")
                advanceUntilIdle()
                viewModel.onEvent(ContributorEditUiEvent.UploadImage(byteArrayOf(1, 2, 3), "avatar.jpg"))
                advanceUntilIdle()

                // When
                viewModel.navActions.test {
                    viewModel.onEvent(ContributorEditUiEvent.Save)
                    advanceUntilIdle()

                    // Then - commit + upload happen only now, at save time
                    verifySuspend(VerifyMode.exactly(1)) {
                        fixture.imageStagingRepository.commitContributorImageStaging("contributor-1")
                    }
                    verifySuspend(VerifyMode.exactly(1)) {
                        fixture.imageRepository.uploadContributorImage(any(), any(), any())
                    }
                    awaitItem() shouldBe ContributorEditNavAction.SaveSuccess
                }

                viewModel.state.value.stagingImagePath shouldBe null
                viewModel.state.value.pendingImageData shouldBe null
            }
        }

        // ========== Merge candidates ==========

        test("merge candidates stay empty and the DAO is never collected while the picker is hidden") {
            runTest {
                // Given: a library with contributors, but the merge picker never opened.
                val fixture = createFixture()
                var daoCollections = 0
                every { fixture.contributorDao.observeAll() } returns
                    flow {
                        daoCollections++
                        emit(listOf(createContributorEntity(id = "other-1", name = "Brandon Sanderson")))
                    }
                everySuspend { fixture.contributorRepository.getById("contributor-1") } returns createContributor()

                val viewModel = fixture.build()
                viewModel.loadContributor("contributor-1")
                advanceUntilIdle()

                // When: the screen collects candidates (it always does), dialog stays closed.
                viewModel.mergeCandidates.test {
                    awaitItem() shouldBe emptyList()
                    advanceUntilIdle()
                    expectNoEvents()
                }

                // Then: the whole-table scan never ran.
                daoCollections shouldBe 0
            }
        }

        test("opening the picker computes candidates, excluding self and deleted contributors") {
            runTest {
                // Given
                val fixture = createFixture()
                every { fixture.contributorDao.observeAll() } returns
                    flowOf(
                        listOf(
                            createContributorEntity(id = "contributor-1", name = "Self"),
                            createContributorEntity(id = "other-2", name = "Terry Pratchett"),
                            createContributorEntity(id = "other-1", name = "Brandon Sanderson"),
                            createContributorEntity(id = "other-3", name = "Deleted Author", deletedAt = 123L),
                        ),
                    )
                everySuspend { fixture.contributorRepository.getById("contributor-1") } returns createContributor()

                val viewModel = fixture.build()
                viewModel.loadContributor("contributor-1")
                advanceUntilIdle()

                // When / Then
                viewModel.mergeCandidates.test {
                    awaitItem() shouldBe emptyList()

                    viewModel.onEvent(ContributorEditUiEvent.MergeDialogOpened)
                    advanceUntilIdle()

                    awaitItem().map { it.displayName } shouldBe listOf("Brandon Sanderson", "Terry Pratchett")
                }
            }
        }

        test("typing in the picker's query re-filters candidates") {
            runTest {
                // Given: the picker is open over two candidates.
                val fixture = createFixture()
                every { fixture.contributorDao.observeAll() } returns
                    flowOf(
                        listOf(
                            createContributorEntity(id = "other-2", name = "Terry Pratchett"),
                            createContributorEntity(id = "other-1", name = "Brandon Sanderson"),
                        ),
                    )
                everySuspend { fixture.contributorRepository.getById("contributor-1") } returns createContributor()

                val viewModel = fixture.build()
                viewModel.loadContributor("contributor-1")
                advanceUntilIdle()

                viewModel.mergeCandidates.test {
                    awaitItem() shouldBe emptyList()
                    viewModel.onEvent(ContributorEditUiEvent.MergeDialogOpened)
                    advanceUntilIdle()
                    awaitItem().size shouldBe 2

                    // When
                    viewModel.onMergeQueryChange("terry")
                    advanceUntilIdle()

                    // Then
                    awaitItem().map { it.displayName } shouldBe listOf("Terry Pratchett")
                }
            }
        }

        test("dismissing the picker clears candidates and resets the query") {
            runTest {
                // Given: the picker is open with a query typed.
                val fixture = createFixture()
                every { fixture.contributorDao.observeAll() } returns
                    flowOf(listOf(createContributorEntity(id = "other-1", name = "Brandon Sanderson")))
                everySuspend { fixture.contributorRepository.getById("contributor-1") } returns createContributor()

                val viewModel = fixture.build()
                viewModel.loadContributor("contributor-1")
                advanceUntilIdle()

                viewModel.mergeCandidates.test {
                    awaitItem() shouldBe emptyList()
                    viewModel.onEvent(ContributorEditUiEvent.MergeDialogOpened)
                    viewModel.onMergeQueryChange("brandon")
                    advanceUntilIdle()
                    awaitItem().size shouldBe 1

                    // When
                    viewModel.onEvent(ContributorEditUiEvent.MergeDialogDismissed)
                    advanceUntilIdle()

                    // Then
                    awaitItem() shouldBe emptyList()
                }
                viewModel.state.value.mergeQuery shouldBe ""
                viewModel.state.value.mergeDialogVisible shouldBe false
            }
        }

        test("candidates are capped at MAX_MERGE_CANDIDATES") {
            runTest {
                // Given: more live contributors than one dialog page.
                val fixture = createFixture()
                every { fixture.contributorDao.observeAll() } returns
                    flowOf(
                        (1..MAX_MERGE_CANDIDATES + 5).map { index ->
                            createContributorEntity(id = "other-$index", name = "Author $index")
                        },
                    )
                everySuspend { fixture.contributorRepository.getById("contributor-1") } returns createContributor()

                val viewModel = fixture.build()
                viewModel.loadContributor("contributor-1")
                advanceUntilIdle()

                viewModel.mergeCandidates.test {
                    awaitItem() shouldBe emptyList()
                    viewModel.onEvent(ContributorEditUiEvent.MergeDialogOpened)
                    advanceUntilIdle()

                    // Then: capped — a full page is the dialog's truncation signal.
                    awaitItem().size shouldBe MAX_MERGE_CANDIDATES
                }
            }
        }

        test("confirming a merge closes the picker and clears the query") {
            runTest {
                // Given: the picker is open with a query typed.
                val fixture = createFixture()
                everySuspend { fixture.contributorRepository.getById("viewed-1") } returns
                    createContributor(id = "viewed-1")
                everySuspend { fixture.contributorEditRepository.mergeContributor(any(), any()) } returns
                    AppResult.Success(Unit)

                val viewModel = fixture.build()
                viewModel.loadContributor("viewed-1")
                advanceUntilIdle()
                viewModel.onEvent(ContributorEditUiEvent.MergeDialogOpened)
                viewModel.onMergeQueryChange("stale")
                viewModel.state.value.mergeDialogVisible shouldBe true

                // When
                viewModel.onEvent(
                    ContributorEditUiEvent.MergeInto(
                        com.calypsan.listenup.core
                            .ContributorId("chosen-2"),
                    ),
                )
                advanceUntilIdle()

                // Then: closed, and no stale pre-filtered query behind the next open.
                viewModel.state.value.mergeDialogVisible shouldBe false
                viewModel.state.value.mergeQuery shouldBe ""
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

        // An alias merge keeps the VIEWED contributor as the survivor, but the hosts still
        // normalize the stack onto it — pop the editor and the stale detail page, land on a fresh
        // survivor page — so the emission carries the survivor's id, exactly as the series editor
        // does. `NavigateBack` would leave a detail page rendered from pre-merge state.
        test("a committed alias merge lands on the surviving contributor, the viewed one") {
            runTest {
                val fixture = createFixture()
                val viewed = createContributor(id = "viewed-1", name = "J.K. Rowling")
                everySuspend { fixture.contributorRepository.getById("viewed-1") } returns viewed
                everySuspend { fixture.contributorEditRepository.mergeContributor(any(), any()) } returns
                    AppResult.Success(Unit)

                val viewModel = fixture.build()
                viewModel.loadContributor("viewed-1")
                advanceUntilIdle()

                viewModel.navActions.test {
                    viewModel.onEvent(
                        ContributorEditUiEvent.MergeInto(
                            com.calypsan.listenup.core
                                .ContributorId("chosen-2"),
                        ),
                    )
                    advanceUntilIdle()

                    awaitItem() shouldBe
                        ContributorEditNavAction.NavigateToMerged(
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

        // ========== Rename-collision prompt ==========

        test("save with a colliding rename holds back the save and surfaces the collision candidate") {
            runTest {
                // Given: another live contributor whose name is a punctuation variant away
                // from the name we're about to rename into.
                val fixture = createFixture()
                val contributor = createContributor(name = "George Martin")
                everySuspend { fixture.contributorRepository.getById("contributor-1") } returns contributor
                every { fixture.contributorDao.observeAll() } returns
                    flowOf(listOf(createContributorEntity(id = "other-1", name = "George R. R. Martin")))

                val viewModel = fixture.build()
                viewModel.loadContributor("contributor-1")
                advanceUntilIdle()

                // When: rename into a name that collides with "other-1" under normalization.
                viewModel.onEvent(ContributorEditUiEvent.NameChanged("George R.R. Martin"))
                viewModel.onEvent(ContributorEditUiEvent.Save)
                advanceUntilIdle()

                // Then: the rename is held back — no persist, no nav — and the candidate surfaces.
                val candidate =
                    viewModel.state.value.renameCollisionCandidate
                        .shouldNotBeNull()
                candidate.id shouldBe
                    com.calypsan.listenup.core
                        .ContributorId("other-1")
                candidate.displayName shouldBe "George R. R. Martin"
                verifySuspend(VerifyMode.not) { fixture.updateContributorUseCase.invoke(any()) }
            }
        }

        // The rename-collision merge soft-deletes the contributor being EDITED, so `NavigateBack`
        // would pop the editor onto the detail page of something that no longer exists. The
        // candidate is the survivor and the only destination that means anything.
        test("confirming merge on rename merges the edited contributor into the candidate and lands on it") {
            runTest {
                // Given: a rename collision has surfaced.
                val fixture = createFixture()
                val contributor = createContributor(name = "George Martin")
                everySuspend { fixture.contributorRepository.getById("contributor-1") } returns contributor
                every { fixture.contributorDao.observeAll() } returns
                    flowOf(listOf(createContributorEntity(id = "other-1", name = "George R. R. Martin")))
                everySuspend { fixture.contributorEditRepository.mergeContributor(any(), any()) } returns
                    AppResult.Success(Unit)

                val viewModel = fixture.build()
                viewModel.loadContributor("contributor-1")
                advanceUntilIdle()
                viewModel.onEvent(ContributorEditUiEvent.NameChanged("George R.R. Martin"))
                viewModel.onEvent(ContributorEditUiEvent.Save)
                advanceUntilIdle()

                // When / Then: merging folds the EDITED contributor (source) into the
                // CANDIDATE (target) — the candidate survives under its own name.
                viewModel.navActions.test {
                    viewModel.onEvent(ContributorEditUiEvent.ConfirmMergeOnRename)
                    advanceUntilIdle()

                    verifySuspend(VerifyMode.exactly(1)) {
                        fixture.contributorEditRepository.mergeContributor(
                            com.calypsan.listenup.core
                                .ContributorId("contributor-1"),
                            com.calypsan.listenup.core
                                .ContributorId("other-1"),
                        )
                    }
                    verifySuspend(VerifyMode.not) { fixture.updateContributorUseCase.invoke(any()) }
                    awaitItem() shouldBe
                        ContributorEditNavAction.NavigateToMerged(
                            com.calypsan.listenup.core
                                .ContributorId("other-1"),
                        )
                }
            }
        }

        test("keeping separate on rename proceeds with the save using the typed name") {
            runTest {
                // Given: a rename collision has surfaced.
                val fixture = createFixture()
                val contributor = createContributor(name = "George Martin")
                everySuspend { fixture.contributorRepository.getById("contributor-1") } returns contributor
                every { fixture.contributorDao.observeAll() } returns
                    flowOf(listOf(createContributorEntity(id = "other-1", name = "George R. R. Martin")))
                everySuspend { fixture.updateContributorUseCase.invoke(any()) } returns AppResult.Success(Unit)

                val viewModel = fixture.build()
                viewModel.loadContributor("contributor-1")
                advanceUntilIdle()
                viewModel.onEvent(ContributorEditUiEvent.NameChanged("George R.R. Martin"))
                viewModel.onEvent(ContributorEditUiEvent.Save)
                advanceUntilIdle()

                // When / Then: keeping separate proceeds with the rename exactly as typed.
                viewModel.navActions.test {
                    viewModel.onEvent(ContributorEditUiEvent.KeepSeparateOnRename)
                    advanceUntilIdle()

                    verifySuspend(VerifyMode.exactly(1)) {
                        fixture.updateContributorUseCase.invoke(any())
                    }
                    verifySuspend(VerifyMode.not) {
                        fixture.contributorEditRepository.mergeContributor(any(), any())
                    }
                    awaitItem() shouldBe ContributorEditNavAction.SaveSuccess
                }
                viewModel.state.value.renameCollisionCandidate
                    .shouldBeNull()
            }
        }

        test("dismissing the rename-collision prompt clears it without saving or merging") {
            runTest {
                // Given: a rename collision has surfaced.
                val fixture = createFixture()
                val contributor = createContributor(name = "George Martin")
                everySuspend { fixture.contributorRepository.getById("contributor-1") } returns contributor
                every { fixture.contributorDao.observeAll() } returns
                    flowOf(listOf(createContributorEntity(id = "other-1", name = "George R. R. Martin")))

                val viewModel = fixture.build()
                viewModel.loadContributor("contributor-1")
                advanceUntilIdle()
                viewModel.onEvent(ContributorEditUiEvent.NameChanged("George R.R. Martin"))
                viewModel.onEvent(ContributorEditUiEvent.Save)
                advanceUntilIdle()

                // When
                viewModel.onEvent(ContributorEditUiEvent.DismissRenameCollision)
                advanceUntilIdle()

                // Then: prompt clears, but nothing persisted or merged — the typed name is
                // still sitting unsaved, so the user can decide again.
                viewModel.state.value.renameCollisionCandidate
                    .shouldBeNull()
                viewModel.state.value.name shouldBe "George R.R. Martin"
                verifySuspend(VerifyMode.not) { fixture.updateContributorUseCase.invoke(any()) }
                verifySuspend(VerifyMode.not) {
                    fixture.contributorEditRepository.mergeContributor(any(), any())
                }
            }
        }
    })
