package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.core.BookId
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
import com.calypsan.listenup.client.domain.model.Instance
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Contract interface for search API operations.
 *
 * Extracted to enable mocking in tests. Production implementation
 * is [SearchApi], test implementation can be a mock or fake.
 */
interface SearchApiContract {
    /**
     * Search across books, contributors, and series.
     *
     * @param query Search query string
     * @param types Comma-separated types to search (book,contributor,series)
     * @param genres Comma-separated genre slugs to filter by
     * @param genrePath Genre path prefix for hierarchical filtering
     * @param minDuration Minimum duration in hours
     * @param maxDuration Maximum duration in hours
     * @param limit Max results to return
     * @param offset Pagination offset
     * @return SearchResponse with hits and facets
     * @throws SearchException on search failure
     */
    suspend fun search(
        query: String,
        types: String? = null,
        genres: String? = null,
        genrePath: String? = null,
        minDuration: Float? = null,
        maxDuration: Float? = null,
        limit: Int = 20,
        offset: Int = 0,
    ): SearchResponse
}

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

    /**
     * Discard all progress for a book.
     *
     * Removes the progress record entirely, optionally preserving
     * listening history (events and stats).
     *
     * Endpoint: DELETE /api/v1/books/{bookId}/progress/discard
     * Auth: Required
     *
     * @param bookId Book to discard progress for
     * @param keepHistory Whether to preserve listening history (default true)
     * @return Result containing Unit on success or error
     */
    suspend fun discardProgress(
        bookId: String,
        keepHistory: Boolean = true,
    ): AppResult<Unit>

    /**
     * Restart a book from the beginning.
     *
     * Resets progress to position 0, increments reread count,
     * creates a new reading session, and generates a "started_book" activity.
     *
     * Endpoint: POST /api/v1/books/{bookId}/progress/restart
     * Auth: Required
     *
     * @param bookId Book to restart
     * @return Result containing updated PlaybackProgressResponse or error
     */
    suspend fun restartBook(bookId: String): AppResult<PlaybackProgressResponse>
}

/**
 * Contract interface for image API operations.
 *
 * Extracted to enable mocking in tests. Production implementation
 * is [ImageApi], test implementation can be a mock or fake.
 */
interface ImageApiContract {
    /**
     * Download cover image for a book.
     *
     * @param bookId Unique identifier for the book
     * @return Result containing image bytes or error
     */
    suspend fun downloadCover(bookId: BookId): AppResult<ByteArray>

    /**
     * Download profile image for a contributor.
     *
     * @param contributorId Unique identifier for the contributor
     * @return Result containing image bytes or error
     */
    suspend fun downloadContributorImage(contributorId: String): AppResult<ByteArray>

    /**
     * Upload cover image for a book.
     *
     * @param bookId Unique identifier for the book
     * @param imageData Raw image bytes (JPEG, PNG, WebP, or GIF)
     * @param filename Original filename for the image
     * @return Result containing the image URL or error
     */
    suspend fun uploadBookCover(
        bookId: String,
        imageData: ByteArray,
        filename: String,
    ): AppResult<ImageUploadResponse>

    /**
     * Upload profile image for a contributor.
     *
     * @param contributorId Unique identifier for the contributor
     * @param imageData Raw image bytes (JPEG, PNG, WebP, or GIF)
     * @param filename Original filename for the image
     * @return Result containing the image URL or error
     */
    suspend fun uploadContributorImage(
        contributorId: String,
        imageData: ByteArray,
        filename: String,
    ): AppResult<ImageUploadResponse>

    /**
     * Download cover image for a series.
     *
     * @param seriesId Unique identifier for the series
     * @return Result containing image bytes or error
     */
    suspend fun downloadSeriesCover(seriesId: String): AppResult<ByteArray>

    /**
     * Upload cover image for a series.
     *
     * @param seriesId Unique identifier for the series
     * @param imageData Raw image bytes (JPEG, PNG, WebP, or GIF)
     * @param filename Original filename for the image
     * @return Result containing the image URL or error
     */
    suspend fun uploadSeriesCover(
        seriesId: String,
        imageData: ByteArray,
        filename: String,
    ): AppResult<ImageUploadResponse>

    /**
     * Delete cover image for a series.
     *
     * @param seriesId Unique identifier for the series
     * @return Result with Unit on success or error
     */
    suspend fun deleteSeriesCover(seriesId: String): AppResult<Unit>

    /**
     * Download multiple contributor images in a single request.
     *
     * Server returns a TAR stream containing all requested images.
     * Missing images are silently skipped by the server.
     *
     * Endpoint: GET /api/v1/contributors/images/batch?ids=contrib_1,contrib_2
     * Auth: Required (Bearer token)
     * Response: application/x-tar (TAR archive)
     *
     * @param contributorIds List of contributor IDs to download images for (max 100)
     * @return Result containing map of contributorId to image bytes for successfully downloaded images
     */
    suspend fun downloadContributorImageBatch(contributorIds: List<String>): AppResult<Map<String, ByteArray>>

    /**
     * Download avatar image for a user.
     *
     * @param userId Unique identifier for the user
     * @return Result containing image bytes or error
     */
    suspend fun downloadUserAvatar(userId: String): AppResult<ByteArray>
}

/**
 * Response from image upload operations.
 */
data class ImageUploadResponse(
    val imageUrl: String,
)

// =============================================================================
// Segregated API Interfaces (ISP - Interface Segregation Principle)
// =============================================================================

/**
 * Contract interface for instance-level API operations.
 *
 * Handles server instance information.
 */
internal interface InstanceApiContract {
    /**
     * Fetch the server instance information.
     *
     * This is a public endpoint - no authentication required.
     *
     * @return Result containing the Instance on success, or an error on failure
     */
    suspend fun getInstance(): AppResult<Instance>
}

/**
 * Contract interface for book editing API operations.
 *
 * Handles book metadata updates and relationship management.
 */
internal interface BookApiContract {
    /**
     * Update book metadata (PATCH semantics).
     *
     * Only fields present in the request are updated:
     * - null field = don't change
     * - empty string = clear the field
     *
     * @param bookId Book to update
     * @param update Fields to update
     * @return Result containing the updated book
     */
    suspend fun updateBook(
        bookId: String,
        update: BookUpdateRequest,
    ): AppResult<BookEditResponse>

    /**
     * Set book contributors (replaces all existing contributors).
     *
     * Contributors are matched by name:
     * - Existing contributor with same name → linked
     * - New name → contributor created automatically
     *
     * Orphaned contributors (no books) are automatically cleaned up.
     *
     * @param bookId Book to update
     * @param contributors New list of contributors with roles
     * @return Result containing the updated book
     */
    suspend fun setBookContributors(
        bookId: String,
        contributors: List<ContributorInput>,
    ): AppResult<BookEditResponse>

    /**
     * Set book series (replaces all existing series relationships).
     *
     * Series are matched by name:
     * - Existing series with same name → linked
     * - New name → series created automatically
     *
     * Orphaned series (no books) are automatically cleaned up.
     *
     * @param bookId Book to update
     * @param series New list of series with sequence numbers
     * @return Result containing the updated book
     */
    suspend fun setBookSeries(
        bookId: String,
        series: List<SeriesInput>,
    ): AppResult<BookEditResponse>
}

/**
 * Contract interface for contributor API operations.
 *
 * Handles contributor search, updates, and merge/unmerge operations.
 */
internal interface ContributorApiContract {
    /**
     * Search contributors for autocomplete during book editing.
     *
     * Uses server-side Bleve search for O(log n) performance with:
     * - Prefix matching ("bran" → "Brandon Sanderson")
     * - Word matching ("sanderson" in "Brandon Sanderson")
     * - Fuzzy matching for typo tolerance
     *
     * @param query Search query (min 2 characters recommended)
     * @param limit Maximum results to return (default 10, max 50)
     * @return Result containing list of matching contributors
     */
    suspend fun searchContributors(
        query: String,
        limit: Int = 10,
    ): AppResult<List<ContributorSearchResult>>

    /**
     * Update a contributor's metadata.
     *
     * PUT /api/v1/contributors/{contributorId}
     *
     * Updates name, biography, website, birth_date, death_date, and aliases.
     *
     * @param contributorId The contributor to update
     * @param request The update request containing new field values
     * @return Result containing the updated contributor
     */
    suspend fun updateContributor(
        contributorId: String,
        request: UpdateContributorRequest,
    ): AppResult<UpdateContributorResponse>

    /**
     * Delete a contributor.
     *
     * DELETE /api/v1/contributors/{contributorId}
     *
     * Soft-deletes the contributor. Books associated with this contributor
     * will have their contributor links removed.
     *
     * @param contributorId The contributor to delete
     * @return Result indicating success or failure
     */
    suspend fun deleteContributor(contributorId: String): AppResult<Unit>
}

/**
 * Contract interface for series API operations.
 *
 * Handles series search and updates.
 */
internal interface SeriesApiContract {
    /**
     * Search series for autocomplete during book editing.
     *
     * Uses server-side Bleve search for O(log n) performance with:
     * - Prefix matching ("mist" → "Mistborn")
     * - Word matching
     * - Fuzzy matching for typo tolerance
     *
     * @param query Search query (min 2 characters recommended)
     * @param limit Maximum results to return (default 10, max 50)
     * @return Result containing list of matching series
     */
    suspend fun searchSeries(
        query: String,
        limit: Int = 10,
    ): AppResult<List<SeriesSearchResult>>

    /**
     * Update series metadata (PATCH semantics).
     *
     * Only fields present in the request are updated:
     * - null field = don't change
     * - empty string = clear the field
     *
     * @param seriesId Series to update
     * @param request Fields to update
     * @return Result containing the updated series
     */
    suspend fun updateSeries(
        seriesId: String,
        request: SeriesUpdateRequest,
    ): AppResult<SeriesEditResponse>
}

/**
 * Contributor search result for autocomplete.
 *
 * Lightweight representation returned by contributor search endpoint.
 * Used when editing book contributors to find existing contributors to link.
 */
internal data class ContributorSearchResult(
    val id: String,
    val name: String,
    val bookCount: Int,
)

/**
 * Series search result for autocomplete.
 *
 * Lightweight representation returned by series search endpoint.
 * Used when editing book series to find existing series to link.
 */
internal data class SeriesSearchResult(
    val id: String,
    val name: String,
    val bookCount: Int,
)

/**
 * Request for updating book metadata (PATCH semantics).
 *
 * Only non-null fields are sent to the server:
 * - null = don't change this field
 * - empty string = clear this field
 */
@Serializable
internal data class BookUpdateRequest(
    val title: String? = null,
    val subtitle: String? = null,
    val description: String? = null,
    val publisher: String? = null,
    @SerialName("publish_year")
    val publishYear: String? = null,
    val language: String? = null,
    val isbn: String? = null,
    val asin: String? = null,
    val abridged: Boolean? = null,
    @SerialName("series_id")
    val seriesId: String? = null,
    val sequence: String? = null,
    @SerialName("created_at")
    val createdAt: String? = null, // ISO8601 timestamp for when book was added to library
)

/**
 * Contributor with roles for setting book contributors.
 */
internal data class ContributorInput(
    val name: String,
    val roles: List<String>,
)

/**
 * Series with sequence for setting book series.
 */
internal data class SeriesInput(
    val name: String,
    val sequence: String?,
)

/**
 * Book response for edit operations.
 *
 * Contains fields needed after editing. Separate from SyncModels.BookResponse
 * which has additional sync-specific fields (chapters, audio files, etc.).
 */
internal data class BookEditResponse(
    val id: String,
    val title: String,
    val subtitle: String?,
    val description: String?,
    val publisher: String?,
    val publishYear: String?,
    val language: String?,
    val isbn: String?,
    val asin: String?,
    val abridged: Boolean,
    val seriesId: String?,
    val seriesName: String?,
    val sequence: String?,
    val updatedAt: String,
)

/**
 * Request to update a contributor's metadata.
 */
internal data class UpdateContributorRequest(
    val name: String,
    val biography: String?,
    val website: String?,
    val birthDate: String?,
    val deathDate: String?,
    val aliases: List<String>,
)

/**
 * Response from updating a contributor.
 */
internal data class UpdateContributorResponse(
    val id: String,
    val name: String,
    val biography: String?,
    val imageUrl: String?,
    val website: String?,
    val birthDate: String?,
    val deathDate: String?,
    val aliases: List<String>,
    val updatedAt: String,
)

/**
 * Request to update a series' metadata (PATCH semantics).
 */
internal data class SeriesUpdateRequest(
    val name: String? = null,
    val description: String? = null,
)

/**
 * Response from series edit operations.
 */
internal data class SeriesEditResponse(
    val id: String,
    val name: String,
    val description: String? = null,
    val updatedAt: String,
)

/**
 * Contract interface for user-profile API operations.
 *
 * Retains only the `getCurrentUser` endpoint. The `getBookReaders` and
 * `getUserReadingHistory` endpoints were removed in P3: the Readers section
 * now sources its data from the [com.calypsan.listenup.api.SocialService] RPC
 * (ACL-filtered server-side, refreshed on presence pings), with no REST fallback.
 */
internal interface SessionApiContract {
    /**
     * Get the current authenticated user's profile.
     *
     * Used to fetch user data if missing from local database
     * (e.g., after database was cleared but tokens remain).
     *
     * Endpoint: GET /api/v1/users/me
     * Auth: Required
     *
     * @return Result containing CurrentUserResponse or error
     */
    suspend fun getCurrentUser(): AppResult<CurrentUserResponse>
}

/**
 * Response from GET /api/v1/users/me endpoint.
 */
internal data class CurrentUserResponse(
    val id: String,
    val email: String,
    val displayName: String,
    val firstName: String?,
    val lastName: String?,
    val isRoot: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val avatarType: String = "auto",
    val avatarValue: String? = null,
    val avatarColor: String = "#6B7280",
)

// =============================================================================
// Session API Response Types
// =============================================================================

// =============================================================================
// Listening Events API Response Types
// =============================================================================

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

// =============================================================================
// Active Sessions API Response Types
// =============================================================================

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

// =============================================================================
// Reading Sessions Sync Response Types
// =============================================================================

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
