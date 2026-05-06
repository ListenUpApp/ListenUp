package com.calypsan.listenup.client.domain.usecase.library

import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.core.Success
import com.calypsan.listenup.client.domain.model.SyncState
import com.calypsan.listenup.client.domain.repository.SyncRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.StateFlow

private val logger = KotlinLogging.logger {}

/**
 * Result of a library refresh operation.
 *
 * Contains the sync state after the operation completes.
 */
data class RefreshLibraryResult(
    val state: SyncState,
    val message: String,
)

/**
 * Use case for refreshing the library from the server.
 *
 * Triggers a sync via [SyncRepository] and surfaces the resulting [SyncState] to the
 * caller. On failure, the unified [com.calypsan.listenup.api.error.AppError] from the
 * repository propagates as-is — translation to user-facing copy is the consumer's
 * responsibility (presentation layer), not the domain's.
 *
 * ```kotlin
 * when (val result = refreshLibraryUseCase()) {
 *     is Success -> showSuccess(result.data.message)
 *     is Failure -> showError(userMessageFor(result.error)) // presentation translator
 * }
 * ```
 */
open class RefreshLibraryUseCase(
    private val syncRepository: SyncRepository,
) {
    /**
     * Observable sync state for progress monitoring.
     *
     * ViewModels can observe this to show sync progress in the UI.
     */
    val syncState: StateFlow<SyncState>
        get() = syncRepository.syncState

    /**
     * Execute library refresh.
     *
     * Triggers a full sync with the server. Progress can be observed
     * via [syncState].
     *
     * @return Result containing RefreshLibraryResult on success, or an error on failure
     */
    open suspend operator fun invoke(): AppResult<RefreshLibraryResult> {
        logger.info { "Starting library refresh" }
        return when (val syncResult = syncRepository.sync()) {
            is Success -> {
                logger.info { "Library refresh completed successfully" }
                Success(
                    RefreshLibraryResult(
                        state = syncRepository.syncState.value,
                        message = "Library refreshed successfully",
                    ),
                )
            }

            is Failure -> {
                logger.warn { "Library refresh failed: ${syncResult.error.code}" }
                syncResult
            }
        }
    }

    /**
     * Handle library mismatch by resetting local data for new library.
     *
     * Called when the server's library has changed (e.g., server reinstalled).
     * User should confirm before calling this as it will clear all local data.
     *
     * @param newLibraryId The new library ID to sync with
     * @return Result containing RefreshLibraryResult on success, or an error on failure
     */
    open suspend fun resetForNewLibrary(newLibraryId: String): AppResult<RefreshLibraryResult> {
        logger.info { "Resetting for new library: $newLibraryId" }
        return when (val syncResult = syncRepository.resetForNewLibrary(newLibraryId)) {
            is Success -> {
                logger.info { "Library reset completed successfully" }
                Success(
                    RefreshLibraryResult(
                        state = syncRepository.syncState.value,
                        message = "Library synced with new server",
                    ),
                )
            }

            is Failure -> {
                logger.warn { "Library reset failed: ${syncResult.error.code}" }
                syncResult
            }
        }
    }
}
