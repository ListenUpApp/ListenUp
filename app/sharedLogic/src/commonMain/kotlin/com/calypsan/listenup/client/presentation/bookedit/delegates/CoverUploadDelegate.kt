package com.calypsan.listenup.client.presentation.bookedit.delegates

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.domain.repository.ImageStagingRepository
import com.calypsan.listenup.client.presentation.bookedit.BookEditForm
import com.calypsan.listenup.client.presentation.bookedit.BookEditUiState
import com.calypsan.listenup.client.presentation.edit.EditPhase
import com.calypsan.listenup.core.error.ErrorBus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/**
 * Delegate handling cover upload operations.
 *
 * Responsibilities:
 * - Stage cover for preview (doesn't overwrite original)
 * - Track pending cover data for save
 * - Clean up staging files on cancel/clear
 *
 * @property state Shared state flow owned by ViewModel
 * @property imageStagingRepository Repository for staging cover image operations
 * @property scope CoroutineScope for launching operations
 * @property errorBus Global error bus for surfacing failures to the UI snackbar
 * @property onChangesMade Callback to notify ViewModel of changes
 */
internal class CoverUploadDelegate(
    private val state: MutableStateFlow<BookEditUiState>,
    private val imageStagingRepository: ImageStagingRepository,
    private val scope: CoroutineScope,
    private val errorBus: ErrorBus,
    private val onChangesMade: () -> Unit,
) {
    /** The form being edited, or null while loading or after a failed load. */
    private val current: BookEditForm? get() = (state.value as? BookEditUiState.Editing)?.form

    /**
     * Handle cover selection.
     * Saves the image to a staging location for preview.
     * Does NOT overwrite the main cover until saveChanges() is called.
     *
     * Refused unless the form is idle: a cover staged under a Save in flight would miss that Save.
     */
    fun uploadCover(
        imageData: ByteArray,
        filename: String,
    ) {
        val editing = state.value as? BookEditUiState.Editing ?: return
        if (editing.phase != EditPhase.Idle) return
        val bookId = editing.form.bookId
        state.value = editing.copy(phase = EditPhase.StagingImage, inlineError = null)

        scope.launch {
            // Save to staging location for preview (doesn't overwrite original)
            when (val saveResult = imageStagingRepository.saveBookCoverStaging(BookId(bookId), imageData)) {
                is AppResult.Success -> {
                    val stagingPath = imageStagingRepository.getBookCoverStagingPath(BookId(bookId))
                    logger.info { "Cover saved to staging for preview: $stagingPath" }

                    // Store pending data for upload when Save Changes is clicked
                    updateEditing { editState ->
                        editState.copy(
                            phase = EditPhase.Idle,
                            form =
                                editState.form.copy(
                                    stagingCoverPath = stagingPath,
                                    pendingCoverData = imageData,
                                    pendingCoverFilename = filename,
                                ),
                        )
                    }
                    onChangesMade()
                }

                is AppResult.Failure -> {
                    errorBus.emit(saveResult.error)
                    logger.error { "Failed to save cover to staging: ${saveResult.message}" }
                    updateEditing { it.copy(phase = EditPhase.Idle, inlineError = saveResult.error) }
                }
            }
        }
    }

    private inline fun updateEditing(transform: (BookEditUiState.Editing) -> BookEditUiState.Editing) {
        state.update { if (it is BookEditUiState.Editing) transform(it) else it }
    }

    /**
     * Clean up staging files when canceling edits.
     */
    fun cleanupStagingOnCancel() {
        val form = current ?: return
        if (form.bookId.isNotBlank() && form.stagingCoverPath != null) {
            imageStagingRepository.requestBookCoverStagingCleanup(BookId(form.bookId))
        }
    }

    /**
     * Clean up staging files.
     * Called when ViewModel is cleared and user navigated away without saving.
     *
     * Non-suspend fire-and-forget — [ImageStagingRepository] runs the work on an
     * application-scoped CoroutineScope, so `viewModelScope` cancellation doesn't
     * interrupt it.
     */
    fun cleanupStagingOnClear() {
        val form = current ?: return
        if (form.bookId.isNotBlank() && form.stagingCoverPath != null) {
            imageStagingRepository.requestBookCoverStagingCleanup(BookId(form.bookId))
        }
    }
}
