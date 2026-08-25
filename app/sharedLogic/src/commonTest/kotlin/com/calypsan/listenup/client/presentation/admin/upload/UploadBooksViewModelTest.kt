package com.calypsan.listenup.client.presentation.admin.upload

import app.cash.turbine.test
import com.calypsan.listenup.api.dto.uploads.UploadFinalizeResult
import com.calypsan.listenup.api.dto.uploads.UploadedBook
import com.calypsan.listenup.api.dto.uploads.UploadedBookStatus
import com.calypsan.listenup.api.error.UploadError
import com.calypsan.listenup.client.core.fileSourceOf
import com.calypsan.listenup.client.domain.repository.UploadCandidate
import com.calypsan.listenup.client.domain.repository.UploadRepository
import com.calypsan.listenup.client.domain.repository.UploadStep
import com.calypsan.listenup.core.error.ErrorBus
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * [UploadBooksViewModel] — the mapping from transfer steps to what the admin actually reads.
 *
 * Two things are worth pinning. A **duplicate is not a failure**: the server refusing a book the
 * library already holds is a correct outcome, and lumping it in with real failures would teach the
 * user to distrust a screen that was telling the truth. And an **unknown total means an
 * indeterminate bar**, never a bar pinned at 0% — a progress bar that says "0%" while bytes are
 * moving is the specific kind of lie this app does not tell.
 */
class UploadBooksViewModelTest :
    FunSpec({

        // viewModelScope dispatches on Main; without this the upload coroutine never runs and
        // every assertion below would read a still-Idle state and "pass" for the wrong reason.
        val testDispatcher = StandardTestDispatcher()
        beforeTest { Dispatchers.setMain(testDispatcher) }
        afterTest { Dispatchers.resetMain() }

        fun candidates() = listOf(UploadCandidate("01.m4b", fileSourceOf(ByteArray(4), "01.m4b")))

        fun vmOver(steps: List<UploadStep>): UploadBooksViewModel =
            UploadBooksViewModel(
                uploadRepository =
                    object : UploadRepository {
                        override fun upload(candidates: List<UploadCandidate>): Flow<UploadStep> = flowOf(*steps.toTypedArray())
                    },
                errorBus = ErrorBus(),
            )

        test("sorts a finished run into imported, duplicate and failed") {
            runTest {
                val vm =
                    vmOver(
                        listOf(
                            UploadStep.Done(
                                UploadFinalizeResult(
                                    books =
                                        listOf(
                                            UploadedBook("Landed", UploadedBookStatus.IMPORTED, "A/Landed"),
                                            UploadedBook("Already Here", UploadedBookStatus.DUPLICATE),
                                            UploadedBook("Broke", UploadedBookStatus.FAILED),
                                        ),
                                ),
                            ),
                        ),
                    )

                vm.onFilesPicked(candidates())
                runCurrent()

                val finished = vm.state.value.shouldBeInstanceOf<UploadBooksUiState.Finished>()
                finished.imported.single().title shouldBe "Landed"
                withClue("a book the library already holds was refused correctly, not broken") {
                    finished.duplicates.single().title shouldBe "Already Here"
                    finished.failed.single().title shouldBe "Broke"
                }
            }
        }

        test("reports an indeterminate fraction when the selection could not report its size") {
            runTest {
                val vm =
                    vmOver(
                        listOf(
                            UploadStep.Staging(
                                fileIndex = 0,
                                fileCount = 1,
                                filename = "01.m4b",
                                bytesSent = 0,
                                totalBytes = 0,
                            ),
                        ),
                    )

                vm.onFilesPicked(candidates())
                runCurrent()

                val uploading = vm.state.value.shouldBeInstanceOf<UploadBooksUiState.Uploading>()
                withClue("an unknown total must not render as 0% while bytes are moving") {
                    uploading.fraction shouldBe null
                }
            }
        }

        test("reports a real fraction once totals are known") {
            runTest {
                val vm =
                    vmOver(
                        listOf(
                            UploadStep.Staging(
                                fileIndex = 1,
                                fileCount = 2,
                                filename = "02.m4b",
                                bytesSent = 15,
                                totalBytes = 20,
                            ),
                        ),
                    )

                vm.onFilesPicked(candidates())
                runCurrent()

                vm.state.value
                    .shouldBeInstanceOf<UploadBooksUiState.Uploading>()
                    .fraction shouldBe 0.75f
            }
        }

        test("publishes a failure to the error bus as well as the screen") {
            runTest {
                val errorBus = ErrorBus()
                val error = UploadError.SessionTooLarge()
                val vm =
                    UploadBooksViewModel(
                        uploadRepository =
                            object : UploadRepository {
                                override fun upload(candidates: List<UploadCandidate>): Flow<UploadStep> = flowOf(UploadStep.Failed(error))
                            },
                        errorBus = errorBus,
                    )

                errorBus.errors.test {
                    vm.onFilesPicked(candidates())
                    awaitItem() shouldBe error
                }
                vm.state.value
                    .shouldBeInstanceOf<UploadBooksUiState.Error>()
                    .error shouldBe error
            }
        }

        test("reset returns a finished run to idle so another selection can start") {
            runTest {
                val vm = vmOver(listOf(UploadStep.Done(UploadFinalizeResult(books = emptyList()))))
                vm.onFilesPicked(candidates())
                runCurrent()
                vm.state.value.shouldBeInstanceOf<UploadBooksUiState.Finished>()

                vm.reset()

                vm.state.value shouldBe UploadBooksUiState.Idle
            }
        }
    })
