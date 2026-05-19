package com.calypsan.listenup.client.domain.model

import com.calypsan.listenup.core.Timestamp

/**
 * Phases of the sync operation for progress reporting.
 *
 * Domain-level representation of sync phases, independent of
 * data layer implementation details.
 */
enum class SyncPhase {
    FETCHING_METADATA,
    SYNCING_BOOKS,
    SYNCING_SERIES,
    SYNCING_CONTRIBUTORS,
    SYNCING_TAGS,
    SYNCING_GENRES,
    SYNCING_LISTENING_EVENTS,
    FINALIZING,
}

/**
 * Domain-level synchronization state.
 *
 * Represents the current state of library synchronization.
 * Use cases and ViewModels observe this via StateFlow to
 * show sync progress, handle errors, and display completion status.
 *
 * This is the domain layer representation - implementations in the
 * data layer may use different internal representations.
 */
sealed interface SyncState {
    /**
     * Idle state - no sync in progress.
     */
    data object Idle : SyncState

    /**
     * Sync operation starting.
     */
    data object Syncing : SyncState

    /**
     * Sync in progress with detailed progress information.
     *
     * @property phase Current sync phase
     * @property current Current progress within phase
     * @property total Total items in phase (-1 if unknown)
     * @property message Human-readable progress message
     */
    data class Progress(
        val phase: SyncPhase,
        val current: Int,
        val total: Int,
        val message: String,
    ) : SyncState

    /**
     * Retrying after a transient failure.
     *
     * @property attempt Current retry attempt number (1-based)
     * @property maxAttempts Maximum retry attempts
     */
    data class Retrying(
        val attempt: Int,
        val maxAttempts: Int,
    ) : SyncState

    /**
     * Sync completed successfully.
     *
     * @property timestamp Timestamp of completion
     */
    data class Success(
        val timestamp: Timestamp,
    ) : SyncState

    /**
     * Sync failed with error.
     *
     * @property message User-friendly error message
     * @property exception The underlying exception (for logging)
     */
    data class Error(
        val message: String,
        val exception: Exception? = null,
    ) : SyncState

    /**
     * Library identity mismatch detected.
     *
     * The server's library ID doesn't match what this client was synced with.
     * This happens when:
     * - Server was reinstalled/reset (database wiped)
     * - Server restored from a different backup
     * - Client connected to a different server instance
     *
     * UI should prompt the user to resync, optionally warning about
     * unsaved local changes.
     *
     * @property expectedLibraryId The library ID the client was synced with
     * @property actualLibraryId The library ID the server now has
     * @property hasPendingChanges Whether there are unsync'd local changes that would be lost
     */
    data class LibraryMismatch(
        val expectedLibraryId: String,
        val actualLibraryId: String,
        val hasPendingChanges: Boolean,
    ) : SyncState
}
