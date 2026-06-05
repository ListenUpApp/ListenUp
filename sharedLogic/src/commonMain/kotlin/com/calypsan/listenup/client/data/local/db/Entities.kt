package com.calypsan.listenup.client.data.local.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ChapterId
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.core.currentEpochMilliseconds
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId

/**
 * Room entity representing a user in the local database.
 *
 * Maps to the User domain model from the server.
 * Timestamps are stored as Unix epoch milliseconds for cross-platform compatibility.
 */
@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey
    val id: UserId,
    val email: String,
    val displayName: String,
    /**
     * User's first name.
     */
    val firstName: String? = null,
    /**
     * User's last name.
     */
    val lastName: String? = null,
    val isRoot: Boolean,
    /**
     * Creation timestamp in Unix epoch milliseconds.
     * Use kotlin.time.Instant for domain model conversion.
     */
    val createdAt: Timestamp,
    /**
     * Last update timestamp in Unix epoch milliseconds.
     * Use kotlin.time.Instant for domain model conversion.
     */
    val updatedAt: Timestamp,
    /**
     * Avatar type: "auto" for generated avatar, "image" for uploaded image.
     */
    val avatarType: String = "auto",
    /**
     * Avatar image path on server (only used when avatarType is "image").
     */
    val avatarValue: String? = null,
    /**
     * Generated avatar background color (hex format like "#6B7280").
     */
    val avatarColor: String = "#6B7280",
    /**
     * User's profile tagline/bio (max 60 chars).
     */
    val tagline: String? = null,
)

/**
 * Cached profile data for any user (not just the current user).
 *
 * Used to display user information in:
 * - Activity feed
 * - "What others are listening to" section
 * - Reader lists on book details
 *
 * Updated via SSE profile.updated events and API responses.
 * Enables fully offline display of user avatars and names.
 */
@Entity(tableName = "user_profiles")
data class UserProfileEntity(
    @PrimaryKey
    val id: String,
    val displayName: String,
    /**
     * Avatar type: "auto" for generated avatar, "image" for uploaded image.
     */
    val avatarType: String = "auto",
    /**
     * Avatar image path on server (only used when avatarType is "image").
     */
    val avatarValue: String? = null,
    /**
     * Generated avatar background color (hex format like "#6B7280").
     */
    val avatarColor: String = "#6B7280",
    /**
     * Last update timestamp in Unix epoch milliseconds.
     */
    val updatedAt: Long,
)

/**
 * Local database entity for audiobooks.
 *
 * Carries the Books-A sync substrate ([revision], [deletedAt]) that the
 * catch-up and digest sync routes depend on. Server commits arrive as wire
 * events and are applied into this projection; the UI reads Room exclusively.
 *
 * Uses type-safe value classes (BookId, Timestamp) for compile-time safety
 * with zero runtime overhead via inline classes.
 */
@Entity(
    tableName = "books",
    indices = [
        Index(value = ["libraryId"]),
        Index(value = ["folderId"]),
    ],
)
data class BookEntity(
    @PrimaryKey val id: BookId,
    /** The library this book belongs to. */
    val libraryId: LibraryId,
    /** The folder within the library where this book's audio files were scanned from. */
    val folderId: FolderId,
    // Core book metadata
    val title: String,
    val sortTitle: String? = null, // Title used for sorting (e.g., "Lord of the Rings, The")
    val subtitle: String? = null, // Book subtitle
    val coverHash: String? = null, // Content hash of the cover image, supplied by the sync wire event
    val coverBlurHash: String? = null, // BlurHash for cover placeholder
    // Cached palette colors extracted from cover (ARGB ints for instant gradient rendering)
    val dominantColor: Int? = null,
    val darkMutedColor: Int? = null,
    val vibrantColor: Int? = null,
    val totalDuration: Long, // Total audiobook duration in milliseconds
    val description: String? = null,
    // Series is now managed via book_series junction table (many-to-many)
    val publishYear: Int? = null,
    val publisher: String? = null, // Publisher name
    val language: String? = null, // ISO 639-1 language code (e.g., "en", "es")
    val isbn: String? = null, // ISBN for metadata lookup
    val asin: String? = null, // Amazon ASIN for metadata lookup
    val abridged: Boolean = false, // Whether this is an abridged version
    // Books-A sync substrate
    val revision: Long = 0, // Monotonic server revision, advanced on every committed change
    val deletedAt: Long? = null, // Epoch ms tombstone; null when the book is live
    val hasScanWarning: Boolean = false, // Server-raised advisory that the scan found something worth review
    // Timestamps from the server
    val createdAt: Timestamp,
    val updatedAt: Timestamp,
)

/**
 * Local database entity for book chapters.
 *
 * Chapters do not sync independently — they ride along with their parent
 * [BookEntity] and inherit its revision lifecycle.
 */
@Entity(
    tableName = "chapters",
    indices = [
        Index(value = ["bookId"]),
    ],
)
data class ChapterEntity(
    @PrimaryKey val id: ChapterId,
    val bookId: BookId,
    val title: String,
    val duration: Long, // Milliseconds
    val startTime: Long, // Milliseconds from start of book
)

/**
 * Local database entity for series.
 *
 * Series sync as a first-class domain (Books-B1); `revision`/`deletedAt` carry
 * the substrate bookkeeping. B2a adds [sortName] (previously on the wire payload
 * only, now persisted locally so sort-by-series works offline).
 */
@Entity(tableName = "series")
data class SeriesEntity(
    @PrimaryKey val id: SeriesId,
    val name: String,
    val sortName: String? = null,
    val description: String?,
    val asin: String? = null,
    val coverPath: String? = null,
    val coverBlurHash: String? = null,
    val revision: Long = 0,
    val deletedAt: Long? = null,
    val createdAt: Timestamp,
    val updatedAt: Timestamp,
)

/**
 * Local database entity for contributors (authors, narrators, etc.).
 *
 * Carries the Books-B1 sync substrate ([revision], [deletedAt]) — contributors
 * are a first-class syncable domain from Books-B1 onward.
 *
 * Aliases: pen names that have been merged into this contributor, stored in the
 * `contributor_aliases` junction (see [ContributorAliasCrossRef]) — NOT on this
 * entity. When "Richard Bachman" is merged into "Stephen King":
 * - All books by Richard Bachman get re-linked to Stephen King
 * - The Richard Bachman contributor row is deleted
 * - "Richard Bachman" lands in `contributor_aliases` as a row under Stephen King
 *
 * Alias-resolution on sync (linking an incoming book to a canonical contributor
 * via the junction) is planned for a later phase.
 */
@Entity(tableName = "contributors")
data class ContributorEntity(
    @PrimaryKey val id: ContributorId,
    val name: String,
    val sortName: String? = null,
    val asin: String? = null,
    val description: String?,
    val imagePath: String?,
    val imageBlurHash: String? = null, // BlurHash placeholder for image
    val website: String? = null,
    val birthDate: String? = null, // ISO 8601 date (e.g., "1947-09-21")
    val deathDate: String? = null, // ISO 8601 date (e.g., "2024-01-15")
    val revision: Long = 0,
    val deletedAt: Long? = null,
    val createdAt: Timestamp,
    val updatedAt: Timestamp,
)

/**
 * Local playback position persistence.
 *
 * Stores the user's current position in each book for instant resume.
 * This is local-first - saves immediately on every pause/seek, syncs to server eventually.
 *
 * Position is sacred: never lose the user's place.
 */
@Entity(tableName = "playback_positions")
data class PlaybackPositionEntity(
    @PrimaryKey val bookId: BookId,
    // Current position in book (book-relative)
    val positionMs: Long,
    // Last used speed for this book
    val playbackSpeed: Float,
    // Whether user explicitly set a custom speed for this book (vs using universal default)
    val hasCustomSpeed: Boolean = false,
    // Local timestamp when entity was modified (epoch ms)
    val updatedAt: Long,
    // Epoch-ms timestamp from the last server-applied write (null if the row has never
    // been written by the server via CrossDeviceSync or an SSE catch-up). Originally
    // used as an outbox dirty-bit; the pending-operation queue (Playback-P1) is now
    // the outbox. syncedAt now serves solely as a "came-from-server" marker in the
    // CrossDeviceSync handler. The DAO methods getUnsyncedPositions/markSynced that
    // consumed this column as an outbox were removed in Playback-P1.
    val syncedAt: Long? = null,
    // When user actually last played this book (epoch ms)
    // Used for "Continue Listening" ordering and social features ("last read")
    // Falls back to updatedAt if null (legacy data before migration)
    val lastPlayedAt: Long? = null,
    // Whether the book is finished (authoritative from server, not derived from position)
    // Used to filter Continue Listening - a book marked finished in ABS should stay finished
    // even if position < 99%
    val isFinished: Boolean = false,
    // When the book was marked finished (epoch ms, null if not finished)
    val finishedAt: Long? = null,
    // When the user started this book (epoch ms, null for legacy data)
    val startedAt: Long? = null,
    // Monotonic server revision — used by the sync engine to detect stale updates
    val revision: Long = 0,
    // Epoch-ms tombstone set by the server when the position is soft-deleted
    val deletedAt: Long? = null,
)

/**
 * Local database entity for ListenUp servers.
 *
 * Client-only data - stores discovered servers and their authentication state.
 * Each server maintains its own auth tokens, enabling instant context switching
 * between servers without re-authentication.
 *
 * Discovery flow:
 * 1. mDNS discovers server on local network → creates/updates ServerEntity
 * 2. User selects server → sets isActive = true
 * 3. User authenticates → stores tokens in this entity
 * 4. Server goes offline → lastSeenAt becomes stale, but entity persists
 * 5. Server comes back → localUrl updated via discovery
 */
@Entity(
    tableName = "servers",
    indices = [Index(value = ["isActive"])],
)
data class ServerEntity(
    /** Server's unique ID from mDNS TXT record (id=srv_xxx) */
    @PrimaryKey val id: String,
    /** Human-readable server name from mDNS TXT record */
    val name: String,
    /** API version from mDNS TXT record (e.g., "v1") */
    val apiVersion: String,
    /** Server version from mDNS TXT record (e.g., "1.0.0") */
    val serverVersion: String,
    /** Local network URL discovered via mDNS (e.g., "http://192.168.1.50:8080") */
    val localUrl: String? = null,
    /** Remote/public URL from mDNS TXT record or manual entry */
    val remoteUrl: String? = null,
    /** PASETO access token for this server */
    val accessToken: String? = null,
    /** Refresh token for this server */
    val refreshToken: String? = null,
    /** Session ID for this server */
    val sessionId: String? = null,
    /** Authenticated user ID on this server */
    val userId: String? = null,
    /** Whether this is the currently active server */
    val isActive: Boolean = false,
    /** Last time server was seen on local network (epoch ms), 0 if never discovered */
    val lastSeenAt: Long = 0,
    /** Last successful connection (epoch ms), null if never connected */
    val lastConnectedAt: Long? = null,
) {
    /** Check if server has valid authentication tokens */
    fun isAuthenticated(): Boolean = accessToken != null && refreshToken != null && userId != null

    /** Check if local URL is fresh (seen within threshold) */
    fun isLocalUrlFresh(staleThresholdMs: Long = STALE_THRESHOLD_MS): Boolean =
        localUrl != null && currentEpochMilliseconds() - lastSeenAt < staleThresholdMs

    /** Get best available URL (prefers fresh local, falls back to remote) */
    fun getBestUrl(staleThresholdMs: Long = STALE_THRESHOLD_MS): String? =
        when {
            isLocalUrlFresh(staleThresholdMs) -> localUrl

            remoteUrl != null -> remoteUrl

            localUrl != null -> localUrl

            // Stale local better than nothing
            else -> null
        }

    companion object {
        /** Threshold for considering local URL stale (5 minutes) */
        const val STALE_THRESHOLD_MS = 5 * 60 * 1000L
    }
}

/**
 * Local database entity for tags (Tags sync substrate — Room v22).
 *
 * Tags are global (cross-user) content descriptors applied to books by curators.
 * [slug] is the stable URL-safe identity derived from [name] at creation time;
 * renames change [name] but never [slug].
 *
 * Carries the sync substrate ([revision], [deletedAt], [updatedAt]) required by
 * [com.calypsan.listenup.client.data.sync.TagSyncDomainHandler] for catch-up and
 * SSE event application.
 */
@Entity(
    tableName = "tags",
    indices = [Index(value = ["slug"])],
)
data class TagEntity(
    @PrimaryKey val id: String,
    /** Human-readable display name, e.g. "Sci-Fi". Mutable via rename. */
    val name: String,
    /** URL-safe slug derived from [name] at creation time, e.g. "sci-fi". Immutable. */
    val slug: String,
    /** Monotonic server revision, advanced on every committed change. */
    val revision: Long = 0,
    /** Epoch ms tombstone; null when the tag is live. */
    val deletedAt: Long? = null,
    /** Last server update timestamp in epoch milliseconds. */
    val updatedAt: Long,
)

/**
 * Genre entity for offline-first genre display.
 *
 * Genres are system-defined hierarchical categories (e.g., Fiction > Fantasy > Epic).
 * Synced during initial sync via GenrePuller.
 * Book-genre relationships stored in book_genres junction table.
 */
@Entity(
    tableName = "genres",
    indices = [
        Index(value = ["slug"], unique = true),
        Index(value = ["path"]),
    ],
)
data class GenreEntity(
    @PrimaryKey val id: String,
    /** Display name: "Epic Fantasy" */
    val name: String,
    /** URL-safe key: "epic-fantasy" */
    val slug: String,
    /** Materialized path: "/fiction/fantasy/epic-fantasy" */
    val path: String,
    /** Parent genre ID for hierarchy traversal */
    val parentId: String? = null,
    /** Depth in hierarchy (0 = root) */
    val depth: Int = 0,
    /** Sort order within parent */
    val sortOrder: Int = 0,
    /** Substrate revision — bumped by [com.calypsan.listenup.api.sync.GenreSyncPayload] applies. */
    val revision: Long = 0,
    /** Soft-delete timestamp; null when the genre is live. */
    val deletedAt: Long? = null,
    /** Creation timestamp (server clock). */
    val createdAt: Timestamp = Timestamp(0L),
    /** Last-update timestamp (server clock). */
    val updatedAt: Timestamp = Timestamp(0L),
) {
    /**
     * Returns the parent path for display context.
     * "/fiction/fantasy/epic-fantasy" -> "Fiction > Fantasy"
     */
    fun parentPath(): String? {
        val segments = path.trim('/').split('/')
        if (segments.size <= 1) return null
        return segments
            .dropLast(1)
            .joinToString(" > ") { it.replaceFirstChar { c -> c.uppercase() } }
    }
}

/**
 * Stores activity feed items locally for offline-first display.
 *
 * Populated via SSE activity.created events and initial sync.
 * All data is denormalized from server for immediate display without joins.
 *
 * Activities are retained for 30 days, then pruned automatically.
 */
@Entity(
    tableName = "activities",
    indices = [
        Index(value = ["userId"]),
        Index(value = ["createdAt"]),
    ],
)
data class ActivityEntity(
    @PrimaryKey
    val id: String,
    /** User who performed the activity */
    val userId: String,
    /** Activity type: started_book, finished_book, streak_milestone, listening_milestone, shelf_created, listening_session */
    val type: String,
    /** When the activity was created (epoch ms) */
    val createdAt: Long,
    // Denormalized user info for offline display
    val userDisplayName: String,
    val userAvatarColor: String,
    val userAvatarType: String,
    val userAvatarValue: String?,
    // Book info (nullable - not all activities have a book)
    val bookId: String?,
    val bookTitle: String?,
    val bookAuthorName: String?,
    val bookCoverPath: String?,
    // Activity-specific fields
    val isReread: Boolean,
    val durationMs: Long,
    val milestoneValue: Int,
    val milestoneUnit: String?,
    val shelfId: String?,
    val shelfName: String?,
)

/**
 * Materialized per-user listening stats, maintained server-side and synced to
 * the client via the P2 stats sync domain.
 *
 * `id` equals the owning user ID (1:1 with the user). `lastEventDate` is
 * `"YYYY-MM-DD"` in the user's IANA timezone — drives streak math; null until
 * the user's first event.
 *
 * Carries the P2 sync substrate ([revision], [deletedAt]) for reconciliation;
 * tombstones are not emitted in P2 but the substrate shape is required.
 */
@Entity(tableName = "user_stats")
data class UserStatsEntity(
    /** Primary key — equals the user's ID. */
    @PrimaryKey val id: String,
    val totalSecondsAllTime: Long,
    val totalSecondsLast7Days: Long,
    val totalSecondsLast30Days: Long,
    val booksStarted: Int,
    val booksFinished: Int,
    val currentStreakDays: Int,
    val longestStreakDays: Int,
    /** `"YYYY-MM-DD"` in the user's timezone; null until the first event. */
    val lastEventDate: String?,
    /** Monotonic server revision; 0 until the server has confirmed the row. */
    val revision: Long = 0,
    /** Epoch-ms tombstone; null while the row is live. */
    val deletedAt: Long? = null,
)

/**
 * Local-only crash-recovery state for the current playback span. **Not synced.**
 *
 * Single-row table (a user can only listen to one thing at a time). The row
 * exists only while a span is open; it is finalized into a [ListeningEventEntity]
 * and queued for sync on pause / book-end / speed-change / seek, or on app restart
 * when an orphan span is detected.
 */
@Entity(tableName = "tentative_span")
data class TentativeSpanEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val bookId: String,
    val startPositionMs: Long,
    val currentPositionMs: Long,
    /** When listening started (epoch ms). */
    val startedAt: Long,
    /** Last heartbeat timestamp (epoch ms) — updated periodically while playing. */
    val lastHeartbeatAt: Long,
    val playbackSpeed: Float,
    /** IANA timezone name recorded at span open time. */
    val tz: String,
    /** Human-readable device label (null on older clients). */
    val deviceLabel: String?,
)
