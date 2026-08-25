package com.calypsan.listenup.client.presentation.admin.upload

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.dto.uploads.UploadedBookStatus
import com.calypsan.listenup.client.domain.repository.UploadCandidate
import com.calypsan.listenup.client.domain.repository.UploadRepository
import com.calypsan.listenup.client.domain.repository.UploadStep
import com.calypsan.listenup.core.error.ErrorBus
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Upload books into the library from a folder or a set of files.
 *
 * The screen deliberately shows no "which book is this?" step. The client sends the structure the
 * user picked and nothing more — deciding how many books a pile of files represents is the server's
 * job, where the audio tags already are, and guessing at it here would only produce a second,
 * worse answer for the user to reconcile.
 */
class UploadBooksViewModel(
    private val uploadRepository: UploadRepository,
    private val errorBus: ErrorBus,
) : ViewModel() {
    /** Push-driven by [onFilesPicked], not derived from an upstream flow — so no `stateIn` wrapper. */
    val state: StateFlow<UploadBooksUiState>
        field = MutableStateFlow<UploadBooksUiState>(UploadBooksUiState.Idle)

    private var uploadJob: Job? = null

    /** Begins an upload, replacing any in flight — cancelling the old one abandons its session. */
    fun onFilesPicked(candidates: List<UploadCandidate>) {
        uploadJob?.cancel()
        uploadJob =
            viewModelScope.launch {
                uploadRepository.upload(candidates).collect { step -> apply(step) }
            }
    }

    /** Cancels an upload in flight. The repository abandons the server-side session on the way out. */
    fun cancel() {
        uploadJob?.cancel()
        uploadJob = null
        state.value = UploadBooksUiState.Idle
    }

    /** Dismisses a finished or failed run and allows another selection. */
    fun reset() {
        state.value = UploadBooksUiState.Idle
    }

    private fun apply(step: UploadStep) {
        state.value =
            when (step) {
                is UploadStep.Staging -> {
                    UploadBooksUiState.Uploading(
                        fileIndex = step.fileIndex,
                        fileCount = step.fileCount,
                        filename = step.filename,
                        // Unknown sizes mean an indeterminate bar, not a bar stuck at zero.
                        fraction =
                            if (step.totalBytes > 0L) {
                                (step.bytesSent.toDouble() / step.totalBytes).toFloat().coerceIn(0f, 1f)
                            } else {
                                null
                            },
                    )
                }

                UploadStep.Finalizing -> {
                    UploadBooksUiState.Finalizing
                }

                is UploadStep.Done -> {
                    UploadBooksUiState.Finished(
                        imported = step.result.books.filter { it.status == UploadedBookStatus.IMPORTED },
                        duplicates = step.result.books.filter { it.status == UploadedBookStatus.DUPLICATE },
                        failed = step.result.books.filter { it.status == UploadedBookStatus.FAILED },
                    )
                }

                is UploadStep.Failed -> {
                    errorBus.emit(step.error)
                    UploadBooksUiState.Error(step.error)
                }
            }
    }
}
