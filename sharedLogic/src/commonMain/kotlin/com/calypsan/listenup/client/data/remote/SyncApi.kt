@file:Suppress("StringLiteralDuplication")

package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.core.Failure
import com.calypsan.listenup.core.Success
import com.calypsan.listenup.core.flatMap
import com.calypsan.listenup.core.map
import com.calypsan.listenup.core.suspendRunCatching
import com.calypsan.listenup.client.data.remote.model.AllProgressResponse
import com.calypsan.listenup.client.data.remote.model.ApiActiveSessions
import com.calypsan.listenup.client.data.remote.model.ApiReadingSessions
import com.calypsan.listenup.client.data.remote.model.ApiResponse
import com.calypsan.listenup.client.data.remote.model.ContinueListeningItemResponse
import com.calypsan.listenup.client.data.remote.model.ContinueListeningResponse
import com.calypsan.listenup.client.data.remote.model.PlaybackProgressResponse
import com.calypsan.listenup.client.data.remote.model.SyncBooksResponse
import com.calypsan.listenup.client.data.remote.model.SyncContributorsResponse
import com.calypsan.listenup.client.data.remote.model.SyncListeningEventsResponse
import com.calypsan.listenup.client.data.remote.model.SyncManifestResponse
import com.calypsan.listenup.client.data.remote.model.SyncSeriesResponse
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * API client for sync endpoints.
 *
 * Handles communication with server sync infrastructure:
 * - Manifest fetching (library overview)
 * - Paginated book syncing
 * - Realtime connection (separate from HTTP calls)
 *
 * Uses ApiClientFactory to obtain authenticated HttpClient at call time,
 * avoiding runBlocking during dependency injection initialization.
 *
 * Implements [SyncApiContract] for testability - tests can mock the interface
 * without needing to mock HTTP client internals.
 *
 * @property clientFactory Factory for creating authenticated HttpClient
 */
class SyncApi(
    private val clientFactory: ApiClientFactory,
) : SyncApiContract {
    /**
     * Fetch sync manifest with library overview.
     *
     * Provides checkpoint timestamp and book IDs for determining
     * which books need to be fetched during sync.
     *
     * Endpoint: GET /api/v1/sync/manifest
     * Auth: Not required (public access)
     *
     * @return Result containing SyncManifestResponse or error
     */
    override suspend fun getManifest(): AppResult<SyncManifestResponse> =
        apiCall(errorMessage = "Failed to fetch sync manifest") {
            clientFactory.getClient().get("/api/v1/sync/manifest").body()
        }

    /**
     * Fetch paginated books for syncing.
     *
     * Returns books with optional cursor-based pagination.
     * Use nextCursor from response to fetch subsequent pages.
     *
     * Endpoint: GET /api/v1/sync/books
     * Auth: Optional (filters by user access if authenticated)
     *
     * @param limit Number of books per page (default 100, max 1000)
     * @param cursor Base64-encoded pagination cursor (null for first page)
     * @param updatedAfter ISO 8601 timestamp to filter books updated after this time (for delta sync)
     * @return Result containing SyncBooksResponse or error
     */
    override suspend fun getBooks(
        limit: Int,
        cursor: String?,
        updatedAfter: String?,
    ): AppResult<SyncBooksResponse> =
        apiCall(errorMessage = "Failed to fetch books for sync") {
            clientFactory
                .getClient()
                .get("/api/v1/sync/books") {
                    parameter("limit", limit)
                    cursor?.let { parameter("cursor", it) }
                    updatedAfter?.let { parameter("updated_after", it) }
                }.body()
        }

    /**
     * Fetch all books/changes across all pages.
     *
     * Automatically handles pagination by following nextCursor until
     * all pages have been fetched. Combines books and deleted IDs from all pages.
     *
     * @param limit Number of books per page
     * @param updatedAfter ISO 8601 timestamp for delta sync (optional)
     * @return Result containing combined SyncBooksResponse with all changes
     */
    override suspend fun getAllBooks(
        limit: Int,
        updatedAfter: String?,
    ): AppResult<SyncBooksResponse> {
        var cursor: String? = null
        val allDeletedIds = mutableListOf<String>()

        val allBooks =
            buildList {
                do {
                    when (val result = getBooks(limit, cursor, updatedAfter)) {
                        is Success -> {
                            addAll(result.data.books)
                            allDeletedIds.addAll(result.data.deletedBookIds)
                            cursor = result.data.nextCursor
                        }

                        is Failure -> {
                            return result
                        }
                    }
                } while (cursor != null)
            }

        return Success(
            SyncBooksResponse(
                books = allBooks,
                deletedBookIds = allDeletedIds,
                hasMore = false,
            ),
        )
    }

    /**
     * Fetch paginated series for syncing.
     */
    override suspend fun getSeries(
        limit: Int,
        cursor: String?,
        updatedAfter: String?,
    ): AppResult<SyncSeriesResponse> =
        apiCall(errorMessage = "Failed to fetch series for sync") {
            clientFactory
                .getClient()
                .get("/api/v1/sync/series") {
                    parameter("limit", limit)
                    cursor?.let { parameter("cursor", it) }
                    updatedAfter?.let { parameter("updated_after", it) }
                }.body()
        }

    override suspend fun getAllSeries(
        limit: Int,
        updatedAfter: String?,
    ): AppResult<List<com.calypsan.listenup.client.data.remote.model.SeriesResponse>> {
        var cursor: String? = null

        val allSeries =
            buildList {
                do {
                    when (val result = getSeries(limit, cursor, updatedAfter)) {
                        is Success -> {
                            addAll(result.data.series)
                            cursor = result.data.nextCursor
                        }

                        is Failure -> {
                            return result
                        }
                    }
                } while (cursor != null)
            }

        return Success(allSeries)
    }

    /**
     * Fetch paginated contributors for syncing.
     */
    override suspend fun getContributors(
        limit: Int,
        cursor: String?,
        updatedAfter: String?,
    ): AppResult<SyncContributorsResponse> =
        apiCall(errorMessage = "Failed to fetch contributors for sync") {
            clientFactory
                .getClient()
                .get("/api/v1/sync/contributors") {
                    parameter("limit", limit)
                    cursor?.let { parameter("cursor", it) }
                    updatedAfter?.let { parameter("updated_after", it) }
                }.body()
        }

    override suspend fun getAllContributors(
        limit: Int,
        updatedAfter: String?,
    ): AppResult<List<com.calypsan.listenup.client.data.remote.model.ContributorResponse>> {
        var cursor: String? = null

        val allContributors =
            buildList {
                do {
                    when (val result = getContributors(limit, cursor, updatedAfter)) {
                        is Success -> {
                            addAll(result.data.contributors)
                            cursor = result.data.nextCursor
                        }

                        is Failure -> {
                            return result
                        }
                    }
                } while (cursor != null)
            }

        return Success(allContributors)
    }

    /**
     * Submit listening events to the server.
     *
     * Events are batched and sent together. Server acknowledges each
     * successfully processed event ID in the response.
     *
     * Endpoint: POST /api/v1/listening/events
     * Auth: Required
     *
     * @param events List of listening events to submit
     * @return Result containing acknowledged event IDs
     */
    override suspend fun submitListeningEvents(
        events: List<ListeningEventRequest>,
    ): AppResult<ListeningEventsResponse> =
        apiCall(errorMessage = "Failed to submit listening events") {
            clientFactory
                .getClient()
                .post("/api/v1/listening/events") {
                    contentType(ContentType.Application.Json)
                    setBody(ListeningEventsRequest(events = events))
                }.body()
        }

    /**
     * Get list of books with playback progress (Continue Listening).
     *
     * Returns display-ready items with embedded book details,
     * eliminating the need for client-side joins.
     *
     * Endpoint: GET /api/v1/listening/continue
     * Auth: Required
     *
     * @param limit Maximum number of books to return
     * @return Result containing list of ContinueListeningItemResponse
     */
    override suspend fun getContinueListening(limit: Int): AppResult<List<ContinueListeningItemResponse>> =
        apiCall<ContinueListeningResponse>(errorMessage = "Failed to fetch continue listening") {
            clientFactory
                .getClient()
                .get("/api/v1/listening/continue") {
                    parameter("limit", limit)
                }.body()
        }.map { it.items }

    override suspend fun getAllProgress(updatedAfter: String?): AppResult<AllProgressResponse> =
        apiCall(errorMessage = "Failed to fetch playback progress") {
            clientFactory
                .getClient()
                .get("/api/v1/listening/progress") {
                    updatedAfter?.let { parameter("updated_after", it) }
                }.body()
        }

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
     * @return Result containing BookResponse (converted from SingleBookResponse) or error
     */
    override suspend fun getBook(
        bookId: String,
    ): AppResult<com.calypsan.listenup.client.data.remote.model.BookResponse> =
        apiCall<com.calypsan.listenup.client.data.remote.model.SingleBookResponse>(
            errorMessage = "Failed to fetch book $bookId",
        ) {
            clientFactory.getClient().get("/api/v1/books/$bookId").body()
        }.map { it.toBookResponse() }

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
    override suspend fun getListeningEvents(sinceMs: Long?): AppResult<ListeningEventsApiResponse> =
        apiCall<SyncListeningEventsResponse>(errorMessage = "Failed to fetch listening events") {
            clientFactory
                .getClient()
                .get("/api/v1/listening/events") {
                    sinceMs?.let { parameter("since", it) }
                }.body()
        }.map { syncResponse ->
            ListeningEventsApiResponse(
                events =
                    syncResponse.events.map { event ->
                        ListeningEventApiResponse(
                            id = event.id,
                            bookId = event.bookId,
                            startPositionMs = event.startPositionMs,
                            endPositionMs = event.endPositionMs,
                            startedAt = event.startedAt,
                            endedAt = event.endedAt,
                            playbackSpeed = event.playbackSpeed,
                            deviceId = event.deviceId,
                        )
                    },
            )
        }

    /**
     * End a playback session and record listening activity.
     *
     * Called when the user pauses or stops playback to create a
     * listening_session activity in the activity feed.
     *
     * Endpoint: POST /api/v1/listening/session/end
     * Auth: Required
     *
     * @param bookId Book that was being played
     * @param durationMs Duration listened in this session (milliseconds)
     * @return Result containing Unit on success or error
     */
    override suspend fun endPlaybackSession(
        bookId: String,
        durationMs: Long,
    ): AppResult<Unit> =
        suspendRunCatching {
            clientFactory.getClient().post("/api/v1/listening/session/end") {
                contentType(ContentType.Application.Json)
                setBody(EndPlaybackSessionRequest(bookId = bookId, durationMs = durationMs))
            }
        }

    /**
     * Get all active reading sessions for discovery page sync.
     *
     * Endpoint: GET /api/v1/sync/active-sessions
     * Auth: Required
     */
    override suspend fun getActiveSessions(): AppResult<SyncActiveSessionsResponse> =
        apiCall<ApiActiveSessions>(errorMessage = "Failed to fetch active sessions") {
            clientFactory.getClient().get("/api/v1/sync/active-sessions").body()
        }.map { apiSessions ->
            SyncActiveSessionsResponse(
                sessions =
                    apiSessions.sessions.map { session ->
                        ActiveSessionApiResponse(
                            sessionId = session.sessionId,
                            userId = session.userId,
                            bookId = session.bookId,
                            startedAt = session.startedAt,
                            displayName = session.displayName,
                            avatarType = session.avatarType,
                            avatarValue = session.avatarValue,
                            avatarColor = session.avatarColor,
                        )
                    },
            )
        }

    /**
     * Get all reading sessions for offline-first book detail pages.
     * Returns active, completed, and abandoned sessions for the Readers section.
     *
     * Endpoint: GET /api/v1/sync/reading-sessions
     * Auth: Required
     */
    override suspend fun getReadingSessions(): AppResult<SyncReadingSessionsResponse> =
        apiCall<ApiReadingSessions>(errorMessage = "Failed to fetch reading sessions") {
            clientFactory.getClient().get("/api/v1/sync/reading-sessions").body()
        }.map { apiSessions ->
            SyncReadingSessionsResponse(
                readers =
                    apiSessions.readers.map { reader ->
                        SyncReadingSessionReaderResponse(
                            bookId = reader.bookId,
                            userId = reader.userId,
                            displayName = reader.displayName,
                            avatarType = reader.avatarType,
                            avatarValue = reader.avatarValue,
                            avatarColor = reader.avatarColor,
                            isCurrentlyReading = reader.isCurrentlyReading,
                            currentProgress = reader.currentProgress,
                            startedAt = reader.startedAt,
                            finishedAt = reader.finishedAt,
                            lastActivityAt = reader.lastActivityAt,
                            completionCount = reader.completionCount,
                        )
                    },
            )
        }

    /**
     * Mark a book as complete.
     *
     * Endpoint: POST /api/v1/books/{bookId}/progress/complete
     * Auth: Required
     */
    override suspend fun markComplete(
        bookId: String,
        startedAt: String?,
        finishedAt: String?,
    ): AppResult<PlaybackProgressResponse> =
        apiCall(errorMessage = "Failed to mark book $bookId as complete") {
            clientFactory
                .getClient()
                .post("/api/v1/books/$bookId/progress/complete") {
                    contentType(ContentType.Application.Json)
                    if (startedAt != null || finishedAt != null) {
                        setBody(MarkCompleteRequest(startedAt = startedAt, finishedAt = finishedAt))
                    }
                }.body()
        }

    /**
     * Discard all progress for a book.
     *
     * Endpoint: DELETE /api/v1/books/{bookId}/progress/discard
     * Auth: Required
     */
    override suspend fun discardProgress(
        bookId: String,
        keepHistory: Boolean,
    ): AppResult<Unit> =
        suspendRunCatching {
            clientFactory.getClient().delete("/api/v1/books/$bookId/progress/discard") {
                parameter("keep_history", keepHistory)
            }
        }

    /**
     * Restart a book from the beginning.
     *
     * Endpoint: POST /api/v1/books/{bookId}/progress/restart
     * Auth: Required
     */
    override suspend fun restartBook(bookId: String): AppResult<PlaybackProgressResponse> =
        apiCall(errorMessage = "Failed to restart book $bookId") {
            clientFactory.getClient().post("/api/v1/books/$bookId/progress/restart").body()
        }
}

/**
 * Request body for ending a playback session.
 */
@Serializable
data class EndPlaybackSessionRequest(
    @SerialName("book_id") val bookId: String,
    @SerialName("duration_ms") val durationMs: Long,
)

/**
 * Request body for marking a book complete.
 */
@Serializable
data class MarkCompleteRequest(
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("finished_at") val finishedAt: String? = null,
)

/**
 * Request body for submitting listening events.
 */
@Serializable
data class ListeningEventsRequest(
    @SerialName("events")
    val events: List<ListeningEventRequest>,
)

/**
 * Single listening event to submit.
 */
@Serializable
data class ListeningEventRequest(
    @SerialName("id")
    val id: String,
    val book_id: String,
    val start_position_ms: Long,
    val end_position_ms: Long,
    val started_at: Long,
    val ended_at: Long,
    val playback_speed: Float,
    val device_id: String,
)

/**
 * Response from listening events submission.
 */
@Serializable
data class ListeningEventsResponse(
    @SerialName("acknowledged")
    val acknowledged: List<String> = emptyList(),
    val failed: List<String> = emptyList(),
)
