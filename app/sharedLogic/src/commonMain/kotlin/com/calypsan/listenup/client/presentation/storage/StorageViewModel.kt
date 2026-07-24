package com.calypsan.listenup.client.presentation.storage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.error.ErrorBus
import com.calypsan.listenup.client.core.error.ErrorMapper
import com.calypsan.listenup.client.domain.model.DownloadedBookSummary
import com.calypsan.listenup.client.domain.repository.DownloadRepository
import com.calypsan.listenup.client.download.DownloadService
import com.calypsan.listenup.client.download.StorageSpaceProvider
import com.calypsan.listenup.client.playback.PlaybackStateProvider
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/**
 * UI state for the Storage screen.
 */
data class StorageUiState(
    val isLoading: Boolean = true,
    val totalStorageUsed: Long = 0,
    val availableStorage: Long = 0,
    val downloadedBooks: List<DownloadedBookSummary> = emptyList(),
    val deleteConfirmation: DeleteConfirmation? = null,
    val isDeleting: Boolean = false,
    /**
     * Set to a book's title when a delete was refused because that book is currently playing
     * (B9). The UI surfaces a "stop playback first" message; [dismissDeleteBlocked] clears it.
     */
    val blockedDeletionTitle: String? = null,
)

/**
 * State for delete confirmation dialogs.
 */
sealed interface DeleteConfirmation {
    /** Confirm deletion of a single downloaded [book]. */
    data class SingleBook(
        val book: DownloadedBookSummary,
    ) : DeleteConfirmation

    data object AllDownloads : DeleteConfirmation
}

/**
 * ViewModel for the Storage management screen.
 *
 * Displays downloaded books and their storage usage.
 * Allows deleting individual downloads or clearing all.
 */
class StorageViewModel(
    private val downloadRepository: DownloadRepository,
    private val downloadService: DownloadService,
    private val storageSpaceProvider: StorageSpaceProvider,
    private val errorBus: ErrorBus,
    private val playbackStateProvider: PlaybackStateProvider,
) : ViewModel() {
    private val internalState = MutableStateFlow(StorageUiState())

    val state: StateFlow<StorageUiState> =
        combine(
            internalState,
            downloadRepository.observeDownloadedBooks(),
        ) { internal, books ->
            val totalUsed = storageSpaceProvider.calculateStorageUsed()
            val available = storageSpaceProvider.getAvailableSpace()
            internal.copy(
                isLoading = false,
                totalStorageUsed = totalUsed,
                availableStorage = available,
                downloadedBooks = books,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = StorageUiState(),
        )

    /**
     * Show confirmation dialog for deleting a single book.
     */
    fun confirmDeleteBook(book: DownloadedBookSummary) {
        internalState.update { it.copy(deleteConfirmation = DeleteConfirmation.SingleBook(book)) }
    }

    /**
     * Show confirmation dialog for clearing all downloads.
     */
    fun confirmClearAll() {
        internalState.update { it.copy(deleteConfirmation = DeleteConfirmation.AllDownloads) }
    }

    /**
     * Cancel the delete confirmation dialog.
     */
    fun cancelDelete() {
        internalState.update { it.copy(deleteConfirmation = null) }
    }

    /**
     * Dismiss the "can't delete the currently-playing book" notice (B9).
     */
    fun dismissDeleteBlocked() {
        internalState.update { it.copy(blockedDeletionTitle = null) }
    }

    /**
     * Execute the confirmed delete action.
     */
    fun executeDelete() {
        val confirmation = internalState.value.deleteConfirmation ?: return
        viewModelScope.launch {
            internalState.update { it.copy(isDeleting = true, deleteConfirmation = null) }
            try {
                when (confirmation) {
                    is DeleteConfirmation.SingleBook -> {
                        val targetBookId = BookId(confirmation.book.bookId)
                        // Never-stranded guard (B9): deleting the currently-playing book would unlink
                        // the file:// sources under an active session (platform behaviour on unlinked
                        // files varies). Refuse and surface a "stop playback first" notice instead.
                        if (playbackStateProvider.currentBookId.value == targetBookId) {
                            logger.warn { "Refusing to delete the currently-playing book: ${confirmation.book.title}" }
                            internalState.update { it.copy(blockedDeletionTitle = confirmation.book.title) }
                            return@launch
                        }
                        logger.info { "Deleting download: ${confirmation.book.title}" }
                        downloadService.deleteDownload(targetBookId)
                    }

                    is DeleteConfirmation.AllDownloads -> {
                        logger.info { "Clearing all downloads" }
                        // Wipe files + rows in one sweep so orphaned downloads (whose book is no
                        // longer in the library, hence absent from downloadedBooks) are reclaimed too.
                        downloadService.deleteAllDownloads()
                    }
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                errorBus.emit(ErrorMapper.map(e))
                logger.error(e) { "Failed to delete download(s)" }
            } finally {
                internalState.update { it.copy(isDeleting = false) }
            }
        }
    }
}
