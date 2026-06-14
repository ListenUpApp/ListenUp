package com.calypsan.listenup.client.presentation.admin

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.remote.ABSImportApiContract
import com.calypsan.listenup.client.data.remote.BackupApiContract
import com.calypsan.listenup.client.data.remote.SearchApiContract
import com.calypsan.listenup.client.data.remote.model.AnalysisStatusResponse
import com.calypsan.listenup.client.data.remote.model.AnalyzeABSRequest
import com.calypsan.listenup.client.data.remote.model.AnalyzeABSResponse
import com.calypsan.listenup.client.data.remote.model.AsyncAnalyzeResponse
import com.calypsan.listenup.client.domain.repository.SyncRepository
import dev.mokkery.answering.returns
import dev.mokkery.answering.sequentiallyReturns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import com.calypsan.listenup.core.error.ErrorBus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Tests that totalBooks/totalUsers from AnalysisStatusResponse
 * are propagated to the ViewModel state during the ANALYZING step.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ABSImportViewModelAnalysisCountsTest :
    FunSpec({
        val testDispatcher = StandardTestDispatcher()

        fun completedAnalysisResponse() =
            AnalyzeABSResponse(
                backupPath = "/tmp/backup.audiobookshelf",
                analyzedAt = "2025-01-01T00:00:00Z",
                summary = "Test",
                totalUsers = 5,
                totalBooks = 1011,
                totalSessions = 200,
                usersMatched = 5,
                usersPending = 0,
                booksMatched = 900,
                booksPending = 111,
                sessionsReady = 150,
                sessionsPending = 50,
                progressReady = 100,
                progressPending = 100,
                userMatches = emptyList(),
                bookMatches = emptyList(),
            )

        // Fresh mocks per test, mirroring the original @BeforeTest lifecycle.
        class TestFixture {
            val backupApi: BackupApiContract = mock()
            val searchApi: SearchApiContract = mock()
            val absImportApi: ABSImportApiContract = mock()
            val syncRepository: SyncRepository = mock()

            fun createViewModel() =
                ABSImportViewModel(
                    backupApi = backupApi,
                    searchApi = searchApi,
                    absImportApi = absImportApi,
                    syncRepository = syncRepository,
                    errorBus = ErrorBus(),
                )
        }

        beforeTest { Dispatchers.setMain(testDispatcher) }
        afterTest { Dispatchers.resetMain() }

        test("analyzing state shows totalBooks and totalUsers when server provides counts") {
            runTest {
                val fixture = TestFixture()
                val analysisResult = completedAnalysisResponse()

                everySuspend { fixture.backupApi.analyzeABSBackupAsync(any()) } returns
                    AppResult.Success(AsyncAnalyzeResponse(analysisId = "a1"))

                everySuspend { fixture.backupApi.getAnalysisStatus("a1") } sequentiallyReturns
                    listOf(
                        AppResult.Success(
                            AnalysisStatusResponse(
                                status = "running",
                                phase = "matching_books",
                                current = 100,
                                total = 1011,
                                totalBooks = 1011,
                                totalUsers = 5,
                            ),
                        ),
                        AppResult.Success(
                            AnalysisStatusResponse(
                                status = "completed",
                                phase = "done",
                                result = analysisResult,
                            ),
                        ),
                    )

                val viewModel = fixture.createViewModel()
                viewModel.setFullRemotePath("/tmp/backup.audiobookshelf")

                // Advance past the first poll (launch + analyzeABSBackupAsync + first getAnalysisStatus)
                advanceTimeBy(100)

                val stateAfterFirstPoll = viewModel.state.value.shouldBeInstanceOf<ABSImportUiState.Ready>()
                stateAfterFirstPoll.step shouldBe ABSImportStep.ANALYZING
                stateAfterFirstPoll.totalBooks shouldBe 1011
                stateAfterFirstPoll.totalUsers shouldBe 5

                // Advance past the delay(1500) and second poll to complete analysis
                advanceUntilIdle()

                // After completion, counts should still be populated
                val finalState = viewModel.state.value.shouldBeInstanceOf<ABSImportUiState.Ready>()
                finalState.totalBooks shouldBe 1011
                finalState.totalUsers shouldBe 5
            }
        }

        test("analyzing state has zero counts when server does not provide them") {
            runTest {
                val fixture = TestFixture()
                val analysisResult = completedAnalysisResponse()

                everySuspend { fixture.backupApi.analyzeABSBackupAsync(any()) } returns
                    AppResult.Success(AsyncAnalyzeResponse(analysisId = "a2"))

                everySuspend { fixture.backupApi.getAnalysisStatus("a2") } sequentiallyReturns
                    listOf(
                        AppResult.Success(
                            AnalysisStatusResponse(
                                status = "running",
                                phase = "parsing",
                                current = 0,
                                total = 0,
                                totalBooks = 0,
                                totalUsers = 0,
                            ),
                        ),
                        AppResult.Success(
                            AnalysisStatusResponse(
                                status = "completed",
                                phase = "done",
                                result = analysisResult,
                            ),
                        ),
                    )

                val viewModel = fixture.createViewModel()
                viewModel.setFullRemotePath("/tmp/backup.audiobookshelf")

                // Advance past the first poll
                advanceTimeBy(100)

                val stateAfterFirstPoll = viewModel.state.value.shouldBeInstanceOf<ABSImportUiState.Ready>()
                stateAfterFirstPoll.step shouldBe ABSImportStep.ANALYZING
                stateAfterFirstPoll.totalBooks shouldBe 0
                stateAfterFirstPoll.totalUsers shouldBe 0
            }
        }

        test("counts use max value across polling responses") {
            runTest {
                val fixture = TestFixture()
                val analysisResult = completedAnalysisResponse()

                everySuspend { fixture.backupApi.analyzeABSBackupAsync(any()) } returns
                    AppResult.Success(AsyncAnalyzeResponse(analysisId = "a3"))

                everySuspend { fixture.backupApi.getAnalysisStatus("a3") } sequentiallyReturns
                    listOf(
                        // First poll: only users known
                        AppResult.Success(
                            AnalysisStatusResponse(
                                status = "running",
                                phase = "matching_users",
                                totalBooks = 0,
                                totalUsers = 5,
                            ),
                        ),
                        // Second poll: books now known too
                        AppResult.Success(
                            AnalysisStatusResponse(
                                status = "running",
                                phase = "matching_books",
                                current = 50,
                                total = 1011,
                                totalBooks = 1011,
                                totalUsers = 5,
                            ),
                        ),
                        AppResult.Success(
                            AnalysisStatusResponse(
                                status = "completed",
                                phase = "done",
                                result = analysisResult,
                            ),
                        ),
                    )

                val viewModel = fixture.createViewModel()
                viewModel.setFullRemotePath("/tmp/backup.audiobookshelf")

                // First poll sees users only
                advanceTimeBy(100)
                val afterFirst = viewModel.state.value.shouldBeInstanceOf<ABSImportUiState.Ready>()
                afterFirst.totalUsers shouldBe 5
                afterFirst.totalBooks shouldBe 0

                // Second poll sees both
                advanceTimeBy(1600)
                val afterSecond = viewModel.state.value.shouldBeInstanceOf<ABSImportUiState.Ready>()
                afterSecond.totalUsers shouldBe 5
                afterSecond.totalBooks shouldBe 1011
            }
        }
    })
