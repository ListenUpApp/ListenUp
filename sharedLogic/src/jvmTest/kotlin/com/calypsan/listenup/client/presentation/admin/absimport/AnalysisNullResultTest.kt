package com.calypsan.listenup.client.presentation.admin.absimport

import com.calypsan.listenup.api.error.ImportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.remote.BackupApiContract
import com.calypsan.listenup.client.data.remote.SearchApiContract
import com.calypsan.listenup.client.data.remote.ABSImportApiContract
import com.calypsan.listenup.client.data.remote.model.AnalysisStatusResponse
import com.calypsan.listenup.client.data.remote.model.AsyncAnalyzeResponse
import com.calypsan.listenup.client.domain.repository.SyncRepository
import com.calypsan.listenup.client.presentation.admin.ABSImportStep
import com.calypsan.listenup.client.presentation.admin.ABSImportUiState
import com.calypsan.listenup.client.presentation.admin.ABSImportViewModel
import com.calypsan.listenup.core.error.ErrorBus
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Regression test for the genuine null-crash risk in [AnalysisDelegate]:
 * when the server returns a non-"failed" status with a null result, the
 * delegate must route to [ABSImportStep.SOURCE_SELECTION] with an
 * [ImportError.AnalysisFailed] error rather than throwing NPE.
 *
 * Before the fix, `statusResponse.result!!` threw on this path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AnalysisNullResultTest :
    FunSpec({
        val testDispatcher = StandardTestDispatcher()

        beforeTest { Dispatchers.setMain(testDispatcher) }
        afterTest { Dispatchers.resetMain() }

        fun createViewModel(
            backupApi: BackupApiContract,
        ): ABSImportViewModel {
            val searchApi: SearchApiContract = mock()
            val absImportApi: ABSImportApiContract = mock()
            val syncRepository: SyncRepository = mock()
            return ABSImportViewModel(
                backupApi = backupApi,
                searchApi = searchApi,
                absImportApi = absImportApi,
                syncRepository = syncRepository,
                errorBus = ErrorBus(),
            )
        }

        test("non-failed status with null result routes to AnalysisFailed, not NPE") {
            runTest {
                val backupApi: BackupApiContract = mock()

                everySuspend { backupApi.analyzeABSBackupAsync(any()) } returns
                    AppResult.Success(AsyncAnalyzeResponse(analysisId = "id1"))

                // Server says "completed" but provides no result — the edge case
                everySuspend { backupApi.getAnalysisStatus("id1") } returns
                    AppResult.Success(
                        AnalysisStatusResponse(
                            status = "completed",
                            phase = "done",
                            result = null,
                        ),
                    )

                val viewModel = createViewModel(backupApi)
                viewModel.setFullRemotePath("/tmp/backup.abs")

                advanceUntilIdle()

                val state = viewModel.state.value.shouldBeInstanceOf<ABSImportUiState.Ready>()
                state.step shouldBe ABSImportStep.SOURCE_SELECTION
                state.isAnalyzing shouldBe false
                state.error.shouldBeInstanceOf<ImportError.AnalysisFailed>()
            }
        }

        test("failed status with null result routes to AnalysisFailed via the explicit failed branch") {
            runTest {
                val backupApi: BackupApiContract = mock()

                everySuspend { backupApi.analyzeABSBackupAsync(any()) } returns
                    AppResult.Success(AsyncAnalyzeResponse(analysisId = "id2"))

                // Server says "failed" with no detail — the pre-existing path
                everySuspend { backupApi.getAnalysisStatus("id2") } returns
                    AppResult.Success(
                        AnalysisStatusResponse(
                            status = "failed",
                            phase = "done",
                            result = null,
                        ),
                    )

                val viewModel = createViewModel(backupApi)
                viewModel.setFullRemotePath("/tmp/backup.abs")

                advanceUntilIdle()

                val state = viewModel.state.value.shouldBeInstanceOf<ABSImportUiState.Ready>()
                state.step shouldBe ABSImportStep.SOURCE_SELECTION
                state.isAnalyzing shouldBe false
                state.error.shouldBeInstanceOf<ImportError.AnalysisFailed>()
            }
        }
    })
