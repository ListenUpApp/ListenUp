package com.calypsan.listenup.client.presentation.admin

import com.calypsan.listenup.core.AppResult
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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import com.calypsan.listenup.core.error.ErrorBus

/**
 * Tests that totalBooks/totalUsers from AnalysisStatusResponse
 * are propagated to the ViewModel state during the ANALYZING step.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ABSImportViewModelAnalysisCountsTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var backupApi: BackupApiContract
    private lateinit var searchApi: SearchApiContract
    private lateinit var absImportApi: ABSImportApiContract
    private lateinit var syncRepository: SyncRepository

    private fun completedAnalysisResponse() =
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

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        backupApi = mock()
        searchApi = mock()
        absImportApi = mock()
        syncRepository = mock()
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel() =
        ABSImportViewModel(
            backupApi = backupApi,
            searchApi = searchApi,
            absImportApi = absImportApi,
            syncRepository = syncRepository,
            errorBus = ErrorBus(),
        )

    @Test
    fun `analyzing state shows totalBooks and totalUsers when server provides counts`() =
        runTest {
            val analysisResult = completedAnalysisResponse()

            everySuspend { backupApi.analyzeABSBackupAsync(any()) } returns
                AppResult.Success(AsyncAnalyzeResponse(analysisId = "a1"))

            everySuspend { backupApi.getAnalysisStatus("a1") } sequentiallyReturns
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

            val viewModel = createViewModel()
            viewModel.setFullRemotePath("/tmp/backup.audiobookshelf")

            // Advance past the first poll (launch + analyzeABSBackupAsync + first getAnalysisStatus)
            advanceTimeBy(100)

            val stateAfterFirstPoll = assertIs<ABSImportUiState.Ready>(viewModel.state.value)
            assertEquals(ABSImportStep.ANALYZING, stateAfterFirstPoll.step)
            assertEquals(1011, stateAfterFirstPoll.totalBooks)
            assertEquals(5, stateAfterFirstPoll.totalUsers)

            // Advance past the delay(1500) and second poll to complete analysis
            advanceUntilIdle()

            // After completion, counts should still be populated
            val finalState = assertIs<ABSImportUiState.Ready>(viewModel.state.value)
            assertEquals(1011, finalState.totalBooks)
            assertEquals(5, finalState.totalUsers)
        }

    @Test
    fun `analyzing state has zero counts when server does not provide them`() =
        runTest {
            val analysisResult = completedAnalysisResponse()

            everySuspend { backupApi.analyzeABSBackupAsync(any()) } returns
                AppResult.Success(AsyncAnalyzeResponse(analysisId = "a2"))

            everySuspend { backupApi.getAnalysisStatus("a2") } sequentiallyReturns
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

            val viewModel = createViewModel()
            viewModel.setFullRemotePath("/tmp/backup.audiobookshelf")

            // Advance past the first poll
            advanceTimeBy(100)

            val stateAfterFirstPoll = assertIs<ABSImportUiState.Ready>(viewModel.state.value)
            assertEquals(ABSImportStep.ANALYZING, stateAfterFirstPoll.step)
            assertEquals(0, stateAfterFirstPoll.totalBooks)
            assertEquals(0, stateAfterFirstPoll.totalUsers)
        }

    @Test
    fun `counts use max value across polling responses`() =
        runTest {
            val analysisResult = completedAnalysisResponse()

            everySuspend { backupApi.analyzeABSBackupAsync(any()) } returns
                AppResult.Success(AsyncAnalyzeResponse(analysisId = "a3"))

            everySuspend { backupApi.getAnalysisStatus("a3") } sequentiallyReturns
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

            val viewModel = createViewModel()
            viewModel.setFullRemotePath("/tmp/backup.audiobookshelf")

            // First poll sees users only
            advanceTimeBy(100)
            val afterFirst = assertIs<ABSImportUiState.Ready>(viewModel.state.value)
            assertEquals(5, afterFirst.totalUsers)
            assertEquals(0, afterFirst.totalBooks)

            // Second poll sees both
            advanceTimeBy(1600)
            val afterSecond = assertIs<ABSImportUiState.Ready>(viewModel.state.value)
            assertEquals(5, afterSecond.totalUsers)
            assertEquals(1011, afterSecond.totalBooks)
        }
}
