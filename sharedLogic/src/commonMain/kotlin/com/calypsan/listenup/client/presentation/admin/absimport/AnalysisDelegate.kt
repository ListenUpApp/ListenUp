package com.calypsan.listenup.client.presentation.admin.absimport

import com.calypsan.listenup.api.error.ImportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.remote.BackupApiContract
import com.calypsan.listenup.client.data.remote.model.AnalyzeABSRequest
import com.calypsan.listenup.client.data.remote.model.AnalyzeABSResponse
import com.calypsan.listenup.client.presentation.admin.ABSImportStep
import com.calypsan.listenup.client.presentation.admin.ABSImportUiState
import com.calypsan.listenup.client.presentation.admin.SelectedBookDisplay
import com.calypsan.listenup.core.error.ErrorBus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

@Suppress("MagicNumber")
private const val ANALYSIS_POLL_INTERVAL_MS = 1_500L

internal class AnalysisDelegate(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<ABSImportUiState>,
    private val errorBus: ErrorBus,
    private val backupApi: BackupApiContract,
) {
    @Suppress("CyclomaticComplexMethod")
    fun analyze(path: String) {
        scope.launch {
            state.updateReady {
                it.copy(
                    step = ABSImportStep.ANALYZING,
                    isAnalyzing = true,
                    error = null,
                    analyzePhase = "",
                    analyzeCurrent = 0,
                    analyzeTotal = 0,
                )
            }

            // Start async analysis
            val asyncResult =
                backupApi.analyzeABSBackupAsync(
                    AnalyzeABSRequest(
                        backupPath = path,
                        matchByEmail = true,
                        matchByPath = true,
                        fuzzyMatchBooks = true,
                        fuzzyThreshold = 0.85,
                    ),
                )

            val asyncResponse =
                when (asyncResult) {
                    is AppResult.Success -> {
                        asyncResult.data
                    }

                    is AppResult.Failure -> {
                        errorBus.emit(asyncResult.error)
                        logger.error { "Failed to analyze ABS backup: ${asyncResult.error.message}" }
                        state.updateReady {
                            it.copy(
                                isAnalyzing = false,
                                step = ABSImportStep.SOURCE_SELECTION,
                                error = asyncResult.error,
                            )
                        }
                        return@launch
                    }
                }

            // Poll for status — helper unwraps AppResult<AnalysisStatusResponse> or
            // emits the error, updates state, and returns null to signal early exit.
            suspend fun pollStatus(analysisId: String) =
                backupApi.getAnalysisStatus(analysisId).let { result ->
                    when (result) {
                        is AppResult.Success -> {
                            result.data
                        }

                        is AppResult.Failure -> {
                            errorBus.emit(result.error)
                            logger.error { "Failed to poll analysis status: ${result.error.message}" }
                            state.updateReady {
                                it.copy(
                                    isAnalyzing = false,
                                    step = ABSImportStep.SOURCE_SELECTION,
                                    error = result.error,
                                )
                            }
                            null
                        }
                    }
                }

            val analysisId = asyncResponse.analysisId
            var statusResponse = pollStatus(analysisId) ?: return@launch

            while (statusResponse.status == "running") {
                state.updateReady {
                    it.copy(
                        analyzePhase = statusResponse.phase,
                        analyzeCurrent = statusResponse.current,
                        analyzeTotal = statusResponse.total,
                        totalBooks = maxOf(it.totalBooks, statusResponse.totalBooks),
                        totalUsers = maxOf(it.totalUsers, statusResponse.totalUsers),
                    )
                }
                delay(ANALYSIS_POLL_INTERVAL_MS)
                statusResponse = pollStatus(analysisId) ?: return@launch
            }

            if (statusResponse.status == "failed") {
                val failureDetail = statusResponse.error ?: "Analysis failed"
                logger.error { "Analysis reported failed status: $failureDetail" }
                state.updateReady {
                    it.copy(
                        isAnalyzing = false,
                        step = ABSImportStep.SOURCE_SELECTION,
                        error = ImportError.AnalysisFailed(debugInfo = failureDetail),
                    )
                }
                return@launch
            }

            val result = statusResponse.result!!

            // Build initial mappings from server-matched items
            // All items with listenupId are auto-matched; users can review and change
            val initialUserMappings =
                result.userMatches
                    .filter { it.listenupId != null }
                    .associate { it.absUserId to it.listenupId!! }

            val initialBookMappings =
                result.bookMatches
                    .filter { it.listenupId != null }
                    .associate { it.absItemId to it.listenupId!! }

            // Pre-populate display info for auto-matched books
            val initialBookDisplays = buildInitialBookDisplays(result)

            // Determine next step based on what needs mapping
            val nextStep =
                when {
                    result.usersPending > 0 -> ABSImportStep.USER_MAPPING
                    result.booksPending > 0 -> ABSImportStep.BOOK_MAPPING
                    else -> ABSImportStep.IMPORT_OPTIONS
                }

            state.updateReady {
                it.copy(
                    isAnalyzing = false,
                    analysisComplete = true,
                    step = nextStep,
                    summary = result.summary,
                    totalUsers = result.totalUsers,
                    totalBooks = result.totalBooks,
                    totalSessions = result.totalSessions,
                    usersMatched = result.usersMatched,
                    usersPending = result.usersPending,
                    booksMatched = result.booksMatched,
                    booksPending = result.booksPending,
                    sessionsReady = result.sessionsReady,
                    sessionsPending = result.sessionsPending,
                    progressReady = result.progressReady,
                    progressPending = result.progressPending,
                    userMatches = result.userMatches,
                    bookMatches = result.bookMatches,
                    analysisWarnings = result.warnings,
                    userMappings = initialUserMappings,
                    bookMappings = initialBookMappings,
                    selectedBookDisplays = initialBookDisplays,
                )
            }
        }
    }

    private fun buildInitialBookDisplays(result: AnalyzeABSResponse): Map<String, SelectedBookDisplay> =
        result.bookMatches
            .filter { it.listenupId != null }
            .associate { match ->
                val listenupId = match.listenupId!!
                // Try to find the matched book in suggestions for full details
                val matchedSuggestion = match.suggestions.firstOrNull { it.bookId == listenupId }
                // Fallback to first suggestion if matched book not in list
                val suggestion = matchedSuggestion ?: match.suggestions.firstOrNull()

                val display =
                    if (suggestion != null) {
                        SelectedBookDisplay(
                            bookId = listenupId,
                            title = suggestion.title,
                            author = suggestion.author,
                            durationMs = suggestion.durationMs,
                        )
                    } else {
                        // No suggestions available — use ABS metadata for display
                        SelectedBookDisplay(
                            bookId = listenupId,
                            title = match.absTitle,
                            author = match.absAuthor,
                            durationMs = null,
                        )
                    }
                match.absItemId to display
            }
}
