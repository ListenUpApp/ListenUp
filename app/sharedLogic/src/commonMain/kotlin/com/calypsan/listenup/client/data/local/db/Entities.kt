package com.calypsan.listenup.client.data.local.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.calypsan.listenup.api.metadata.BookField
import com.calypsan.listenup.api.metadata.FieldProvenance
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ChapterId
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId

/**
 * Room entity representing a user in the local database.
 *
 * Maps to the User domain model from the server.
 * Timestamps are stored as Unix epoch milliseconds for cross-platform compatibility.
 */
@Entity(tableName = "users")
internal data class UserEntity(
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
     * User's profile tagline/bio (max 60 chars).
     */
    val tagline: String? = null,
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
internal data class BookEntity(
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
    // Client-local cover-presence marker: set when the cover file lands on disk
    // (ImageDownloader), cleared when it is invalidated (server cover-hash change or
    // local delete). Never on the wire — replaces the per-book filesystem stat that
    // list mapping used to pay on every emission.
    val coverDownloadedAt: Timestamp? = null,
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
    // Per-field provenance, mirrored from BookSyncPayload.fieldProvenance: the authority (scan/enrichment/
    // user) that wrote each metadata field's current value, keyed by BookField. Persisted locally so it
    // survives offline and round-trips through the sync engine; stored as a JSON object via
    // FieldProvenanceConverter.
    @ColumnInfo(defaultValue = "'{}'")
    val fieldProvenance: Map<BookField, FieldProvenance> = emptyMap(),
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
internal data class ChapterEntity(
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
internal data class SeriesEntity(
    @PrimaryKey val id: SeriesId,
    val name: String,
    val sortName: String? = null,
    val description: String?,
    val asin: String? = null,
    val coverPath: String? = null,
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
internal data class ContributorEntity(
    @PrimaryKey val id: ContributorId,
    val name: String,
    val sortName: String? = null,
    val asin: String? = null,
    val description: String?,
    val imagePath: String?,
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
internal data class PlaybackPositionEntity(
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
    // been written by the server via sync or catch-up). Originally
    // used as an outbox dirty-bit; the pending-operation queue (Playback-P1) is now
    // the outbox. syncedAt now serves solely as a "came-from-server" marker, carried
    // forward untouched by [PlaybackPositionMirrorApply] on every inbound sync merge.
    // The DAO methods getUnsyncedPositions/markSynced that consumed this column as an
    // outbox were removed in Playback-P1.
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
 * Local database entity for tags (Tags sync substrate — Room v22).
 *
 * Tags are global (cross-user) content descriptors applied to books by curators.
 * [slug] is the stable URL-safe identity derived from [name] at creation time;
 * renames change [name] but never [slug].
 *
 * Carries the sync substrate ([revision], [deletedAt], [updatedAt]) required by
 * [com.calypsan.listenup.client.data.sync.domains.tagsDomain] for catch-up and
 * firehose event application.
 */
@Entity(
    tableName = "tags",
    indices = [Index(value = ["slug"])],
)
internal data class TagEntity(
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
 * Sync-substrate entity for a global (cross-user) mood.
 *
 * Moods are the affective axis of a book ("Feel-Good", "Tense", "Scary"), applied by
 * curators — mirrors [TagEntity] in shape and sync discipline. Tombstones are
 * soft-deletes via [deletedAt]; observation queries exclude tombstoned rows.
 */
@Entity(
    tableName = "moods",
    indices = [Index(value = ["slug"])],
)
internal data class MoodEntity(
    @PrimaryKey val id: String,
    /** Human-readable display name, e.g. "Feel-Good". Mutable via rename. */
    val name: String,
    /** URL-safe slug derived from [name] at creation time, e.g. "feel-good". Immutable. */
    val slug: String,
    /** Monotonic server revision, advanced on every committed change. */
    val revision: Long = 0,
    /** Epoch ms tombstone; null when the mood is live. */
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
internal data class GenreEntity(
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
 * Local mirror of the `activities` sync domain — a pure row of the server's activity log.
 *
 * Carries only the RAW activity fields (no denormalized display projection): user identity and the
 * book card are enriched at READ time by joining the local `public_profiles` and book mirrors, so a
 * later rename is reflected everywhere instead of frozen at record time. Populated by the cursored
 * sync data channel (catch-up + live tail), never written by the client.
 *
 * [revision] is the monotonic server revision (0 until confirmed); [deletedAt] is the soft-delete
 * tombstone (null while live) — activities is append-only, so tombstones are rare.
 *
 * The `(deletedAt, revision, id)` composite is a COVERING index: on this append-forever table it lets
 * the digest scan (`ActivityDao.digestRows`) and the access-gate live-set read (`ActivityDao.liveIds`)
 * run as index-only scans instead of full scans of the wide row.
 */
@Entity(
    tableName = "activities",
    indices = [
        Index(value = ["userId"]),
        Index(value = ["occurredAt"]),
        Index(value = ["deletedAt", "revision", "id"]),
    ],
)
internal data class ActivityEntity(
    @PrimaryKey
    val id: String,
    /** User who performed the activity */
    val userId: String,
    /** Activity type: started_book, finished_book, streak_milestone, listening_milestone, shelf_created, listening_session */
    val type: String,
    /** When the activity occurred (epoch ms) */
    val occurredAt: Long,
    /** Book this activity is about, or null for non-book activities (e.g. user_joined). */
    val bookId: String?,
    // Activity-specific fields
    val isReread: Boolean,
    val durationMs: Long,
    val milestoneValue: Int,
    val milestoneUnit: String?,
    val shelfId: String?,
    val shelfName: String?,
    /** Monotonic server revision; 0 until the server has confirmed the row. */
    val revision: Long = 0,
    /** Epoch-ms tombstone; null while the activity is live. */
    val deletedAt: Long? = null,
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
internal data class UserStatsEntity(
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
 * Local offline-first cache of the user's synced playback preferences.
 *
 * One row per user (keyed by [id] = user ID), refreshed wholesale from
 * `UserPreferencesService.getMyPreferences`. Unlike the P2 sync domains this is not
 * revision-cursored — it is a small, single-row projection re-pulled in full whenever the
 * server nudges with `SyncControl.PreferencesChanged`, so it carries no `revision`/`deletedAt`
 * substrate. The UI observes this row (via the repository) so a change made on another device
 * lands live and survives offline.
 */
@Entity(tableName = "user_preferences")
internal data class UserPreferencesEntity(
    /** Primary key — equals the user's ID. */
    @PrimaryKey val id: String,
    val defaultPlaybackSpeed: Float,
    val defaultSkipForwardSec: Int,
    val defaultSkipBackwardSec: Int,
    /** Null disables the default sleep timer. */
    val defaultSleepTimerMin: Int?,
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
internal data class TentativeSpanEntity(
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
    /**
     * Identity of the app-process launch that opened this span (an in-memory UUID minted once
     * per process). It is how orphan recovery tells a span left behind by a PRIOR process
     * (crash / OS-kill) apart from the CURRENT process's live span: recovery finalizes only
     * spans whose id differs from the running process's, and a fresh play never overwrites an
     * unfinalized span from a different process without recovering it first. Empty string is the
     * legacy/unknown value and is always treated as a recoverable orphan (it never equals a live
     * process id).
     */
    @ColumnInfo(defaultValue = "")
    val processId: String = "",
)
