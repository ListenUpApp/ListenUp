package com.calypsan.listenup.client.presentation.bookedit.delegates

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.domain.repository.ImageStagingRepository
import com.calypsan.listenup.client.presentation.bookedit.BookEditUiState
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
class CoverUploadDelegate(
    private val state: MutableStateFlow<BookEditUiState>,
    private val imageStagingRepository: ImageStagingRepository,
    private val scope: CoroutineScope,
    private val errorBus: ErrorBus,
    private val onChangesMade: () -> Unit,
) {
    /**
     * Handle cover selection.
     * Saves the image to a staging location for preview.
     * Does NOT overwrite the main cover until saveChanges() is called.
     */
    fun uploadCover(
        imageData: ByteArray,
        filename: String,
    ) {
        val bookId = state.value.bookId
        if (bookId.isBlank()) {
            logger.error { "Cannot set cover: book ID is empty" }
            return
        }

        scope.launch {
            state.update { it.copy(isUploadingCover = true, error = null) }

            // Save to staging location for preview (doesn't overwrite original)
            when (val saveResult = imageStagingRepository.saveBookCoverStaging(BookId(bookId), imageData)) {
                is AppResult.Success -> {
                    val stagingPath = imageStagingRepository.getBookCoverStagingPath(BookId(bookId))
                    logger.info { "Cover saved to staging for preview: $stagingPath" }

                    // Store pending data for upload when Save Changes is clicked
                    state.update {
                        it.copy(
                            isUploadingCover = false,
                            stagingCoverPath = stagingPath,
                            pendingCoverData = imageData,
                            pendingCoverFilename = filename,
                        )
                    }
                    onChangesMade()
                }

                is AppResult.Failure -> {
                    errorBus.emit(saveResult.error)
                    logger.error { "Failed to save cover to staging: ${saveResult.message}" }
                    state.update {
                        it.copy(
                            isUploadingCover = false,
                            error = "Failed to save cover: ${saveResult.message}",
                        )
                    }
                }
            }
        }
    }

    /**
     * Clean up staging files when canceling edits.
     */
    fun cleanupStagingOnCancel() {
        val bookId = state.value.bookId
        if (bookId.isNotBlank() && state.value.stagingCoverPath != null) {
            imageStagingRepository.requestBookCoverStagingCleanup(BookId(bookId))
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
        val bookId = state.value.bookId
        if (bookId.isNotBlank() && state.value.stagingCoverPath != null) {
            imageStagingRepository.requestBookCoverStagingCleanup(BookId(bookId))
        }
    }
}
