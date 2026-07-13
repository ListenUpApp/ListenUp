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
     * Execute the confirmed delete action.
     */
    fun executeDelete() {
        val confirmation = internalState.value.deleteConfirmation ?: return
        viewModelScope.launch {
            internalState.update { it.copy(isDeleting = true, deleteConfirmation = null) }
            try {
                when (confirmation) {
                    is DeleteConfirmation.SingleBook -> {
                        logger.info { "Deleting download: ${confirmation.book.title}" }
                        downloadService.deleteDownload(BookId(confirmation.book.bookId))
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
