package com.calypsan.listenup.client.data.remote.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Response from GET /api/v1/sync/manifest endpoint.
 *
 * Provides library overview with checkpoint timestamp for sync tracking
 * and book IDs for determining which books need to be fetched.
 */
@Serializable
data class SyncManifestResponse(
    // ISO 8601 timestamp of last library change
    @SerialName("library_version")
    val libraryVersion: String,
    // ISO 8601 timestamp for this sync checkpoint
    @SerialName("checkpoint")
    val checkpoint: String,
    // All book IDs in library
    @SerialName("book_ids")
    val bookIds: List<String>,
    // Summary counts
    @SerialName("counts")
    val counts: LibraryCounts,
)

/**
 * Library entity counts from sync manifest.
 */
@Serializable
data class LibraryCounts(
    @SerialName("books")
    val books: Int,
    @SerialName("contributors")
    val contributors: Int,
    @SerialName("series")
    val series: Int,
)

/**
 * Response from GET /api/v1/sync/books endpoint.
 *
 * Paginated list of books with cursor for fetching next page.
 */
@Serializable
data class SyncBooksResponse(
    // Base64-encoded cursor, null if no more pages
    @SerialName("next_cursor")
    val nextCursor: String? = null,
    // Array of book objects
    @SerialName("books")
    val books: List<BookResponse>,
    // IDs of books deleted since requested timestamp
    @SerialName("deleted_book_ids")
    val deletedBookIds: List<String> = emptyList(),
    // True if more pages available
    @SerialName("has_more")
    val hasMore: Boolean,
)

/**
 * Book object from server.
 *
 * Matches backend Book model structure. Contains all book metadata
 * and embedded Syncable fields (id, created_at, updated_at, deleted_at).
 *
 * For initial implementation, we only map minimal fields to BookEntity.
 * Full model with audio files, chapters, contributors, etc. will be
 * added in future iterations.
 */
@Serializable
data class BookResponse(
    // Syncable fields
    @SerialName("id")
    val id: String,
    // ISO 8601 timestamp
    @SerialName("created_at")
    val createdAt: String,
    // ISO 8601 timestamp
    @SerialName("updated_at")
    val updatedAt: String,
    // ISO 8601 timestamp, null if not deleted
    @SerialName("deleted_at")
    val deletedAt: String? = null,
    // Core metadata (minimal for v1)
    @SerialName("title")
    val title: String,
    @SerialName("sort_title")
    val sortTitle: String? = null,
    @SerialName("subtitle")
    val subtitle: String? = null,
    @SerialName("description")
    val description: String? = null,
    @SerialName("publisher")
    val publisher: String? = null,
    // Server sends this as a string
    @SerialName("publish_year")
    val publishYear: String? = null,
    @SerialName("language")
    val language: String? = null,
    @SerialName("isbn")
    val isbn: String? = null,
    @SerialName("asin")
    val asin: String? = null,
    @SerialName("abridged")
    val abridged: Boolean = false,
    // List of strings
    @SerialName("genres")
    val genres: List<String>? = null,
    @SerialName("contributors")
    val contributors: List<BookContributorResponse> = emptyList(),
    @SerialName("cover_image")
    val coverImage: ImageFileInfoResponse? = null,
    // Milliseconds
    @SerialName("total_duration")
    val totalDuration: Long,
    // Multiple series (many-to-many with sequence per series)
    @SerialName("series_info")
    val seriesInfo: List<BookSeriesInfoResponse> = emptyList(),
    // Legacy single series fields (backward compat, will be deprecated)
    @SerialName("series_id")
    val seriesId: String? = null,
    @SerialName("series_name")
    val seriesName: String? = null,
    @SerialName("sequence")
    val sequence: String? = null,
    @SerialName("chapters")
    val chapters: List<ChapterResponse> = emptyList(),
    @SerialName("audio_files")
    val audioFiles: List<AudioFileResponse> = emptyList(),
    // Tags applied to this book (synced inline like contributors)
    @SerialName("tags")
    val tags: List<BookTagResponse> = emptyList(),
)

/**
 * Tag information for a book during sync.
 */
@Serializable
data class BookTagResponse(
    val id: String,
    val slug: String,
    @SerialName("book_count")
    val bookCount: Int = 0,
)

/** Contributor link on a synced book — the contributor's id, name, role list, and optional pen-name credit. */
@Serializable
data class BookContributorResponse(
    @SerialName("contributor_id")
    val contributorId: String,
    @SerialName("name")
    val name: String,
    @SerialName("roles")
    val roles: List<String>,
    // Original attribution name (e.g., "Richard Bachman" when contributor is Stephen King)
    @SerialName("credited_as")
    val creditedAs: String? = null,
)

/**
 * Series relationship for a book (many-to-many).
 * A book can belong to multiple series with different sequences.
 */
@Serializable
data class BookSeriesInfoResponse(
    @SerialName("series_id")
    val seriesId: String,
    @SerialName("name")
    val name: String,
    @SerialName("sequence")
    val sequence: String? = null,
)

/** One chapter span within a book's audio. Times are millisecond offsets from the start of the audio. */
@Serializable
data class ChapterResponse(
    @SerialName("title")
    val title: String,
    @SerialName("start_time")
    val startTime: Long,
    @SerialName("end_time")
    val endTime: Long,
)

/** One audio file backing a book. A book may have multiple files (multi-disc / multi-part audiobooks). */
@Serializable
data class AudioFileResponse(
    @SerialName("id")
    val id: String,
    @SerialName("filename")
    val filename: String,
    @SerialName("format")
    val format: String,
    @SerialName("codec")
    val codec: String = "", // May be empty for older server versions
    @SerialName("duration")
    val duration: Long,
    @SerialName("size")
    val size: Long,
)

/**
 * Image file metadata from server.
 */
@Serializable
data class ImageFileInfoResponse(
    // File path on server
    @SerialName("path")
    val path: String,
    // e.g., "cover.jpg"
    @SerialName("filename")
    val filename: String,
    // e.g., "jpeg", "png"
    @SerialName("format")
    val format: String,
    // Bytes
    @SerialName("size")
    val size: Long,
    // File inode for change detection
    @SerialName("inode")
    val inode: Long,
    // Unix timestamp in milliseconds
    @SerialName("mod_time")
    val modTime: Long,
)

/** Paginated response from `GET /api/v1/sync/series`. Carries one page plus tombstones for deleted series. */
@Serializable
data class SyncSeriesResponse(
    @SerialName("next_cursor")
    val nextCursor: String? = null,
    @SerialName("series")
    val series: List<SeriesResponse>,
    @SerialName("deleted_series_ids")
    val deletedSeriesIds: List<String> = emptyList(),
    @SerialName("has_more")
    val hasMore: Boolean,
)

/** One series record from sync. */
@Serializable
data class SeriesResponse(
    @SerialName("id")
    val id: String,
    @SerialName("name")
    val name: String,
    @SerialName("description")
    val description: String? = null,
    @SerialName("cover_image")
    val coverImage: ImageFileInfoResponse? = null,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("updated_at")
    val updatedAt: String,
    @SerialName("asin")
    val asin: String? = null,
)

/** Paginated response from `GET /api/v1/sync/contributors`. Carries one page plus tombstones for deleted contributors. */
@Serializable
data class SyncContributorsResponse(
    @SerialName("next_cursor")
    val nextCursor: String? = null,
    @SerialName("contributors")
    val contributors: List<ContributorResponse>,
    @SerialName("deleted_contributor_ids")
    val deletedContributorIds: List<String> = emptyList(),
    @SerialName("has_more")
    val hasMore: Boolean,
)

/** One contributor record from sync (author/narrator/etc.). */
@Serializable
data class ContributorResponse(
    @SerialName("id")
    val id: String,
    @SerialName("name")
    val name: String,
    @SerialName("sort_name")
    val sortName: String? = null,
    @SerialName("asin")
    val asin: String? = null,
    @SerialName("biography")
    val biography: String? = null,
    @SerialName("image_url")
    val imageUrl: String? = null,
    @SerialName("aliases")
    val aliases: List<String>? = null,
    @SerialName("website")
    val website: String? = null,
    @SerialName("birth_date")
    val birthDate: String? = null,
    @SerialName("death_date")
    val deathDate: String? = null,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("updated_at")
    val updatedAt: String,
)

/**
 * User data embedded in realtime user event payloads.
 */
@Serializable
internal data class SSEUserData(
    @SerialName("id")
    val id: String,
    @SerialName("email")
    val email: String,
    @SerialName("display_name")
    val displayName: String? = null,
    @SerialName("first_name")
    val firstName: String? = null,
    @SerialName("last_name")
    val lastName: String? = null,
    @SerialName("is_root")
    val isRoot: Boolean = false,
    @SerialName("role")
    val role: String = "member",
    @SerialName("status")
    val status: String = "active",
    @SerialName("created_at")
    val createdAt: String = "",
    @SerialName("updated_at")
    val updatedAt: String? = null,
)

// =============================================================================
// Listening Events Sync Response
// =============================================================================

/**
 * Response from GET /api/v1/listening/events endpoint.
 * Used for initial sync of listening events from other devices.
 */
@Serializable
internal data class SyncListeningEventsResponse(
    @SerialName("events")
    val events: List<SyncListeningEventItem>,
)

/**
 * A single listening event from the sync endpoint.
 */
@Serializable
internal data class SyncListeningEventItem(
    @SerialName("id")
    val id: String,
    @SerialName("book_id")
    val bookId: String,
    @SerialName("start_position_ms")
    val startPositionMs: Long,
    @SerialName("end_position_ms")
    val endPositionMs: Long,
    @SerialName("started_at")
    val startedAt: String,
    @SerialName("ended_at")
    val endedAt: String,
    @SerialName("playback_speed")
    val playbackSpeed: Float,
    @SerialName("device_id")
    val deviceId: String,
)

// =============================================================================
// Reading Sessions Sync Response (GET /api/v1/sync/reading-sessions)
// =============================================================================

/**
 * Response from GET /api/v1/sync/reading-sessions endpoint.
 * Returns all book reader summaries for offline-first Readers section.
 */
@Serializable
internal data class ApiReadingSessions(
    @SerialName("readers")
    val readers: List<ApiReadingSessionReaderResponse>,
)

/**
 * A reader summary for a specific book from the sync endpoint.
 * Includes denormalized user profile data for offline display.
 */
@Serializable
internal data class ApiReadingSessionReaderResponse(
    @SerialName("book_id")
    val bookId: String,
    @SerialName("user_id")
    val userId: String,
    @SerialName("display_name")
    val displayName: String,
    @SerialName("avatar_type")
    val avatarType: String,
    @SerialName("avatar_value")
    val avatarValue: String? = null,
    @SerialName("avatar_color")
    val avatarColor: String,
    @SerialName("is_currently_reading")
    val isCurrentlyReading: Boolean,
    @SerialName("current_progress")
    val currentProgress: Double = 0.0,
    @SerialName("started_at")
    val startedAt: String,
    @SerialName("finished_at")
    val finishedAt: String? = null,
    @SerialName("last_activity_at")
    val lastActivityAt: String,
    @SerialName("completion_count")
    val completionCount: Int,
)

// =============================================================================
// Active Sessions Sync Response (GET /api/v1/sync/active-sessions)
// =============================================================================

/**
 * Response from GET /api/v1/sync/active-sessions endpoint.
 * Returns all currently active reading sessions for initial discovery page sync.
 */
@Serializable
internal data class ApiActiveSessions(
    @SerialName("sessions")
    val sessions: List<ApiActiveSessionResponse>,
)

/**
 * A single active reading session from the sync endpoint.
 * Includes user profile data for offline-first display.
 */
@Serializable
internal data class ApiActiveSessionResponse(
    @SerialName("session_id")
    val sessionId: String,
    @SerialName("user_id")
    val userId: String,
    @SerialName("book_id")
    val bookId: String,
    @SerialName("started_at")
    val startedAt: String,
    @SerialName("display_name")
    val displayName: String,
    @SerialName("avatar_type")
    val avatarType: String,
    @SerialName("avatar_value")
    val avatarValue: String? = null,
    @SerialName("avatar_color")
    val avatarColor: String,
)
