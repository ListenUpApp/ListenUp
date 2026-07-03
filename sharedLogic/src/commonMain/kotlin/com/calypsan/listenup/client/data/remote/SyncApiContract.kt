package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.remote.model.AllProgressResponse
import com.calypsan.listenup.client.data.remote.model.ContinueListeningItemResponse
import com.calypsan.listenup.client.data.remote.model.ContributorResponse
import com.calypsan.listenup.client.data.remote.model.PlaybackProgressResponse
import com.calypsan.listenup.client.data.remote.model.SeriesResponse
import com.calypsan.listenup.client.data.remote.model.SyncBooksResponse
import com.calypsan.listenup.client.data.remote.model.SyncContributorsResponse
import com.calypsan.listenup.client.data.remote.model.SyncManifestResponse
import com.calypsan.listenup.client.data.remote.model.SyncSeriesResponse

/**
 * Contract interface for sync API operations.
 *
 * Extracted to enable mocking in tests. Production implementation
 * is [SyncApi], test implementation can be a mock or fake.
 */
interface SyncApiContract {
    /**
     * Fetch sync manifest with library overview.
     */
    suspend fun getManifest(): AppResult<SyncManifestResponse>

    /**
     * Fetch paginated books for syncing.
     *
     * @param limit Number of books per page
     * @param cursor Pagination cursor (null for first page)
     * @param updatedAfter ISO 8601 timestamp for delta sync
     */
    suspend fun getBooks(
        limit: Int = 100,
        cursor: String? = null,
        updatedAfter: String? = null,
    ): AppResult<SyncBooksResponse>

    /**
     * Fetch all books across all pages.
     */
    suspend fun getAllBooks(
        limit: Int = 100,
        updatedAfter: String? = null,
    ): AppResult<SyncBooksResponse>

    /**
     * Fetch paginated series for syncing.
     */
    suspend fun getSeries(
        limit: Int = 100,
        cursor: String? = null,
        updatedAfter: String? = null,
    ): AppResult<SyncSeriesResponse>

    /**
     * Fetch all series across all pages.
     */
    suspend fun getAllSeries(
        limit: Int = 100,
        updatedAfter: String? = null,
    ): AppResult<List<SeriesResponse>>

    /**
     * Fetch paginated contributors for syncing.
     */
    suspend fun getContributors(
        limit: Int = 100,
        cursor: String? = null,
        updatedAfter: String? = null,
    ): AppResult<SyncContributorsResponse>

    /**
     * Fetch all contributors across all pages.
     */
    suspend fun getAllContributors(
        limit: Int = 100,
        updatedAfter: String? = null,
    ): AppResult<List<ContributorResponse>>

    /**
     * Submit listening events to the server.
     */
    suspend fun submitListeningEvents(events: List<ListeningEventRequest>): AppResult<ListeningEventsResponse>

    /**
     * Get list of books with playback progress (Continue Listening).
     *
     * Returns display-ready items with embedded book details.
     * No client-side joins required - ready for immediate display.
     *
     * Endpoint: GET /api/v1/listening/continue
     * Auth: Required
     *
     * @param limit Maximum number of books to return (default 10)
     * @return Result containing list of ContinueListeningItemResponse
     */
    suspend fun getContinueListening(limit: Int = 10): AppResult<List<ContinueListeningItemResponse>>

    /**
     * Get all playback progress records for the authenticated user.
     *
     * Returns progress records filtered by `updated_after` if provided, otherwise all.
     * Used for bulk sync to ensure client has accurate finished state.
     *
     * Endpoint: GET /api/v1/listening/progress
     * Auth: Required
     *
     * @param updatedAfter Optional ISO-8601 timestamp; server returns only rows updated after this point (SP2).
     * @return Result containing AllProgressResponse with filtered (or all) progress items
     */
    suspend fun getAllProgress(updatedAfter: String? = null): AppResult<AllProgressResponse>

    /**
     * Get a single book by ID.
     *
     * Used to fetch book data on-demand when local data is incomplete
     * (e.g., the `audio_files` junction has no rows for this book during playback).
     *
     * Endpoint: GET /api/v1/books/{id}
     * Auth: Required
     *
     * @param bookId Book ID to fetch
     * @return Result containing the book's [com.calypsan.listenup.api.sync.BookSyncPayload], or error
     */
    suspend fun getBook(bookId: String): AppResult<com.calypsan.listenup.api.sync.BookSyncPayload>

    /**
     * Get listening events for initial sync.
     *
     * Fetches all listening events for the current user, optionally filtered
     * by a since timestamp for delta sync.
     *
     * Endpoint: GET /api/v1/listening/events
     * Auth: Required
     *
     * @param sinceMs Only return events created after this timestamp (epoch ms), null for all events
     * @return Result containing list of listening events
     */
    suspend fun getListeningEvents(sinceMs: Long? = null): AppResult<ListeningEventsApiResponse>

    /**
     * End a playback session and record listening activity.
     *
     * Called by the client when playback is paused or stopped.
     * Records a listening_session activity in the activity feed.
     *
     * Endpoint: POST /api/v1/listening/session/end
     * Auth: Required
     *
     * @param bookId Book that was being played
     * @param durationMs Duration listened in this session (milliseconds)
     * @return Result containing success message or error
     */
    suspend fun endPlaybackSession(
        bookId: String,
        durationMs: Long,
    ): AppResult<Unit>

    /**
     * Get all active reading sessions for discovery page sync.
     *
     * Returns all currently active reading sessions across all users.
     * Used during initial sync to populate the "What Others Are Listening To" section.
     *
     * Endpoint: GET /api/v1/sync/active-sessions
     * Auth: Required
     *
     * @return Result containing list of active sessions
     */
    suspend fun getActiveSessions(): AppResult<SyncActiveSessionsResponse>

    /**
     * Get all reading sessions for offline-first book detail pages.
     *
     * Returns all book reader summaries across all books and users.
     * Used during initial sync to populate the Readers section.
     *
     * Endpoint: GET /api/v1/sync/reading-sessions
     * Auth: Required
     */
    suspend fun getReadingSessions(): AppResult<SyncReadingSessionsResponse>

    /**
     * Mark a book as complete.
     *
     * Updates the progress record to mark the book finished, creating
     * a "finished_book" activity and completing the current reading session.
     *
     * Endpoint: POST /api/v1/books/{bookId}/progress/complete
     * Auth: Required
     *
     * @param bookId Book to mark as complete
     * @param startedAt Optional ISO 8601 timestamp for when reading started
     * @param finishedAt Optional ISO 8601 timestamp for when the book was finished
     * @return Result containing updated PlaybackProgressResponse or error
     */
    suspend fun markComplete(
        bookId: String,
        startedAt: String? = null,
        finishedAt: String? = null,
    ): AppResult<PlaybackProgressResponse>
}

/**
 * Response from GET /listening/events endpoint.
 */
data class ListeningEventsApiResponse(
    val events: List<ListeningEventApiResponse>,
)

/**
 * A single listening event from the API.
 */
data class ListeningEventApiResponse(
    val id: String,
    val bookId: String,
    val startPositionMs: Long,
    val endPositionMs: Long,
    val startedAt: String,
    val endedAt: String,
    val playbackSpeed: Float,
    val deviceId: String,
)

/**
 * Response from GET /sync/active-sessions endpoint.
 *
 * Contains all currently active reading sessions for the discovery page.
 */
data class SyncActiveSessionsResponse(
    val sessions: List<ActiveSessionApiResponse>,
)

/**
 * A single active reading session from the API.
 * Includes user profile data for offline-first display.
 */
data class ActiveSessionApiResponse(
    val sessionId: String,
    val userId: String,
    val bookId: String,
    val startedAt: String,
    val displayName: String,
    val avatarType: String,
    val avatarValue: String?,
    val avatarColor: String,
)

/**
 * Response from GET /sync/reading-sessions endpoint.
 *
 * Contains all book reader summaries for offline-first Readers section.
 */
data class SyncReadingSessionsResponse(
    val readers: List<SyncReadingSessionReaderResponse>,
)

/**
 * A reader summary for a specific book from the sync endpoint.
 * Includes denormalized user profile data for offline display.
 */
data class SyncReadingSessionReaderResponse(
    val bookId: String,
    val userId: String,
    val displayName: String,
    val avatarType: String,
    val avatarValue: String?,
    val avatarColor: String,
    val isCurrentlyReading: Boolean,
    val currentProgress: Double,
    val startedAt: String,
    val finishedAt: String?,
    val lastActivityAt: String,
    val completionCount: Int,
)
