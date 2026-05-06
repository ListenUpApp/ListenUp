package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.client.data.remote.model.BookResponse
import com.calypsan.listenup.client.data.remote.model.SSEUserData
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * Polymorphic sealed hierarchy for SSE events dispatched by the server.
 *
 * Each subclass declares `@Serializable @SerialName("<wire type string>")` and carries
 * a `timestamp` field (envelope-level) plus a payload sub-object matching the server's
 * `data: {...}` shape. Deserialize via `json.decodeFromString<SSEEvent>(eventJson)` —
 * one call, compiler-checked exhaustiveness on the consumer `when`.
 *
 * Unknown discriminators decode to [Unknown] via the `polymorphicDefaultDeserializer`
 * registration on the shared `Json` instance; decode never throws on a well-formed
 * envelope with an unenumerated `type`.
 *
 * For consumer-side dispatch (wire events PLUS synthetic channel messages like
 * reconnect notifications) see [SSEChannelMessage].
 *
 * Transport note: this is the multi-domain sync firehose served at
 * `GET /api/v1/sync/events` over HTTP/1.1 EventStream. Per-service streaming
 * (e.g. scan progress) is delivered separately via kotlinx.rpc `Flow<T>`
 * over WebSocket — see `com.calypsan.listenup.api.ScannerService.observeProgress()`
 * for that pattern. SSE and RPC streaming are not redundant; each carries
 * a distinct slice of server-pushed data.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed interface SSEEvent {
    val timestamp: String

    // ===== Book events =====

    /** A new book was added to the library. */
    @Serializable
    @SerialName("book.created")
    data class BookCreated(
        override val timestamp: String,
        val data: BookPayload,
    ) : SSEEvent

    /** Metadata or fields on an existing book changed. */
    @Serializable
    @SerialName("book.updated")
    data class BookUpdated(
        override val timestamp: String,
        val data: BookPayload,
    ) : SSEEvent

    /** A book was removed from the library. */
    @Serializable
    @SerialName("book.deleted")
    data class BookDeleted(
        override val timestamp: String,
        val data: BookDeletedPayload,
    ) : SSEEvent

    // ===== Library scan events =====

    /** A library scan has begun. */
    @Serializable
    @SerialName("library.scan_started")
    data class ScanStarted(
        override val timestamp: String,
        val data: ScanStartedPayload,
    ) : SSEEvent

    /** A library scan has finished; payload carries the aggregate counts. */
    @Serializable
    @SerialName("library.scan_completed")
    data class ScanCompleted(
        override val timestamp: String,
        val data: ScanCompletedPayload,
    ) : SSEEvent

    /** Periodic progress tick during an in-flight library scan. */
    @Serializable
    @SerialName("library.scan_progress")
    data class ScanProgress(
        override val timestamp: String,
        val data: ScanProgressPayload,
    ) : SSEEvent

    /** A library's access mode (private/public) changed. */
    @Serializable
    @SerialName("library.access_mode_changed")
    data class LibraryAccessModeChanged(
        override val timestamp: String,
        val data: LibraryAccessModeChangedPayload,
    ) : SSEEvent

    // ===== Transcode events =====

    /** A transcode job finished successfully and the output is available. */
    @Serializable
    @SerialName("transcode.complete")
    data class TranscodeComplete(
        override val timestamp: String,
        val data: TranscodeCompletePayload,
    ) : SSEEvent

    /** Progress update for an in-flight transcode job (0-100). */
    @Serializable
    @SerialName("transcode.progress")
    data class TranscodeProgress(
        override val timestamp: String,
        val data: TranscodeProgressPayload,
    ) : SSEEvent

    /** A transcode job failed; payload carries the error reason. */
    @Serializable
    @SerialName("transcode.failed")
    data class TranscodeFailed(
        override val timestamp: String,
        val data: TranscodeFailedPayload,
    ) : SSEEvent

    // ===== Heartbeat (no payload) =====

    /** Server-side keep-alive emitted on an interval; clients use it to detect a stalled stream. */
    @Serializable
    @SerialName("heartbeat")
    data class Heartbeat(
        override val timestamp: String,
    ) : SSEEvent

    // ===== User events =====

    /** A new user signed up and is awaiting admin approval. */
    @Serializable
    @SerialName("user.pending")
    data class UserPending(
        override val timestamp: String,
        val data: UserPayload,
    ) : SSEEvent

    /** A pending user was approved by an admin. */
    @Serializable
    @SerialName("user.approved")
    data class UserApproved(
        override val timestamp: String,
        val data: UserPayload,
    ) : SSEEvent

    /** A user account was deleted; payload carries the user id and an optional reason. */
    @Serializable
    @SerialName("user.deleted")
    data class UserDeleted(
        override val timestamp: String,
        val data: UserDeletedPayload,
    ) : SSEEvent

    // ===== Collection events =====

    /** A new collection was created. */
    @Serializable
    @SerialName("collection.created")
    data class CollectionCreated(
        override val timestamp: String,
        val data: CollectionPayload,
    ) : SSEEvent

    /** A collection's name or membership count changed. */
    @Serializable
    @SerialName("collection.updated")
    data class CollectionUpdated(
        override val timestamp: String,
        val data: CollectionPayload,
    ) : SSEEvent

    /** A collection was deleted. */
    @Serializable
    @SerialName("collection.deleted")
    data class CollectionDeleted(
        override val timestamp: String,
        val data: CollectionDeletedPayload,
    ) : SSEEvent

    /** A book was added to a collection. */
    @Serializable
    @SerialName("collection.book_added")
    data class CollectionBookAdded(
        override val timestamp: String,
        val data: CollectionBookPayload,
    ) : SSEEvent

    /** A book was removed from a collection. */
    @Serializable
    @SerialName("collection.book_removed")
    data class CollectionBookRemoved(
        override val timestamp: String,
        val data: CollectionBookPayload,
    ) : SSEEvent

    // ===== Shelf events =====

    /** A user created a new shelf. */
    @Serializable
    @SerialName("shelf.created")
    data class ShelfCreated(
        override val timestamp: String,
        val data: ShelfPayload,
    ) : SSEEvent

    /** A shelf's name, description, or membership count changed. */
    @Serializable
    @SerialName("shelf.updated")
    data class ShelfUpdated(
        override val timestamp: String,
        val data: ShelfPayload,
    ) : SSEEvent

    /** A shelf was deleted. */
    @Serializable
    @SerialName("shelf.deleted")
    data class ShelfDeleted(
        override val timestamp: String,
        val data: ShelfDeletedPayload,
    ) : SSEEvent

    /** A book was added to a shelf. */
    @Serializable
    @SerialName("shelf.book_added")
    data class ShelfBookAdded(
        override val timestamp: String,
        val data: ShelfBookPayload,
    ) : SSEEvent

    /** A book was removed from a shelf. */
    @Serializable
    @SerialName("shelf.book_removed")
    data class ShelfBookRemoved(
        override val timestamp: String,
        val data: ShelfBookPayload,
    ) : SSEEvent

    // ===== Tag events =====

    /** A new tag was created. */
    @Serializable
    @SerialName("tag.created")
    data class TagCreated(
        override val timestamp: String,
        val data: TagPayload,
    ) : SSEEvent

    /** A tag was attached to a book. */
    @Serializable
    @SerialName("book.tag_added")
    data class BookTagAdded(
        override val timestamp: String,
        val data: BookTagPayload,
    ) : SSEEvent

    /** A tag was removed from a book. */
    @Serializable
    @SerialName("book.tag_removed")
    data class BookTagRemoved(
        override val timestamp: String,
        val data: BookTagPayload,
    ) : SSEEvent

    // ===== Inbox events =====

    /** A book entered the user's inbox (e.g. via ABS import or shared listen). */
    @Serializable
    @SerialName("inbox.book_added")
    data class InboxBookAdded(
        override val timestamp: String,
        val data: InboxBookAddedPayload,
    ) : SSEEvent

    /** A book was released (acknowledged/consumed) from the inbox. */
    @Serializable
    @SerialName("inbox.book_released")
    data class InboxBookReleased(
        override val timestamp: String,
        val data: InboxBookReleasedPayload,
    ) : SSEEvent

    // ===== Progress events =====
    //
    // NOTE: Wire type strings are `listening.progress_updated` / `listening.progress_deleted`
    // — verified against SSEManager.kt :598-623. The plan's "progress_updated" /
    // "progress_deleted" shorthand was a plan-writer oversight; corrected here.

    /** A user's listening progress on a book was updated (position, percent, finished flag). */
    @Serializable
    @SerialName("listening.progress_updated")
    data class ProgressUpdated(
        override val timestamp: String,
        val data: ProgressPayload,
    ) : SSEEvent

    /** A user's listening progress for a book was reset/deleted. */
    @Serializable
    @SerialName("listening.progress_deleted")
    data class ProgressDeleted(
        override val timestamp: String,
        val data: ProgressDeletedPayload,
    ) : SSEEvent

    // ===== Session events =====

    /** A new playback session started for a user/book. */
    @Serializable
    @SerialName("session.started")
    data class SessionStarted(
        override val timestamp: String,
        val data: SessionStartedPayload,
    ) : SSEEvent

    /** An open playback session ended. */
    @Serializable
    @SerialName("session.ended")
    data class SessionEnded(
        override val timestamp: String,
        val data: SessionEndedPayload,
    ) : SSEEvent

    /** Aggregated reading-session totals were updated for a user/book. */
    @Serializable
    @SerialName("reading_session.updated")
    data class ReadingSessionUpdated(
        override val timestamp: String,
        val data: ReadingSessionUpdatedPayload,
    ) : SSEEvent

    // ===== Listening events =====
    //
    // NOTE: Wire type string is `listening.event_created` — verified against
    // SSEManager.kt :640. Plan's "listening_event.created" was a plan-writer oversight.

    /** A new fine-grained listening event (a single play span) was recorded. */
    @Serializable
    @SerialName("listening.event_created")
    data class ListeningEventCreated(
        override val timestamp: String,
        val data: ListeningEventPayload,
    ) : SSEEvent

    // ===== Stats / profile =====

    /** A user's aggregate stats (total time, books listened, streak) changed. */
    @Serializable
    @SerialName("user_stats.updated")
    data class UserStatsUpdated(
        override val timestamp: String,
        val data: UserStatsPayload,
    ) : SSEEvent

    /** A user's profile (name, avatar, tagline) changed. */
    @Serializable
    @SerialName("profile.updated")
    data class ProfileUpdated(
        override val timestamp: String,
        val data: ProfilePayload,
    ) : SSEEvent

    // ===== Activity =====

    /** A new entry was added to the social activity feed (finished book, milestone, etc.). */
    @Serializable
    @SerialName("activity.created")
    data class ActivityCreated(
        override val timestamp: String,
        val data: ActivityPayload,
    ) : SSEEvent

    // ===== Sentinel (default deserializer for unknown discriminators) =====

    /**
     * Fallback variant decoded when the `type` discriminator doesn't match any
     * enumerated [SerialName]. Installed via
     * `SerializersModule { polymorphic(SSEEvent::class) { defaultDeserializer { Unknown.serializer() } } }`
     * on the shared `Json` instance.
     *
     * @property rawType The unrecognised `type` string from the wire payload;
     *   preserved for logging and future-debugging visibility.
     */
    @Serializable
    data class Unknown(
        override val timestamp: String,
        @SerialName("type") val rawType: String,
    ) : SSEEvent
}

// ===== Payload sub-types =====

/** Wire payload for [SSEEvent.BookCreated] / [SSEEvent.BookUpdated]. */
@Serializable
data class BookPayload(
    @SerialName("book")
    val book: BookResponse,
)

/** Wire payload for [SSEEvent.BookDeleted]. */
@Serializable
data class BookDeletedPayload(
    @SerialName("book_id") val bookId: String,
    @SerialName("deleted_at") val deletedAt: String,
)

/** Wire payload for [SSEEvent.ScanStarted]. */
@Serializable
data class ScanStartedPayload(
    @SerialName("library_id") val libraryId: String,
    @SerialName("started_at") val startedAt: String,
)

/** Wire payload for [SSEEvent.ScanCompleted]. Carries the aggregate add/update/remove counts. */
@Serializable
data class ScanCompletedPayload(
    @SerialName("library_id") val libraryId: String,
    @SerialName("books_added") val booksAdded: Int,
    @SerialName("books_updated") val booksUpdated: Int,
    @SerialName("books_removed") val booksRemoved: Int,
)

/** Wire payload for [SSEEvent.ScanProgress]. `phase` is the scanner's current pipeline stage. */
@Serializable
data class ScanProgressPayload(
    @SerialName("library_id") val libraryId: String,
    val phase: String,
    val current: Int,
    val total: Int,
    val added: Int,
    val updated: Int,
    val removed: Int,
)

/** Wire payload for [SSEEvent.LibraryAccessModeChanged]. `accessMode` is `"private"` or `"public"`. */
@Serializable
data class LibraryAccessModeChangedPayload(
    @SerialName("library_id") val libraryId: String,
    @SerialName("access_mode") val accessMode: String,
)

/** Wire payload for [SSEEvent.TranscodeComplete]. */
@Serializable
data class TranscodeCompletePayload(
    @SerialName("job_id") val jobId: String,
    @SerialName("book_id") val bookId: String,
    @SerialName("audio_file_id") val audioFileId: String,
)

/** Wire payload for [SSEEvent.TranscodeProgress]. `progress` is a percentage 0-100. */
@Serializable
data class TranscodeProgressPayload(
    @SerialName("job_id") val jobId: String,
    @SerialName("book_id") val bookId: String,
    @SerialName("audio_file_id") val audioFileId: String,
    val progress: Int,
)

/** Wire payload for [SSEEvent.TranscodeFailed]. `error` is a server-supplied human-readable reason. */
@Serializable
data class TranscodeFailedPayload(
    @SerialName("job_id") val jobId: String,
    @SerialName("book_id") val bookId: String,
    @SerialName("audio_file_id") val audioFileId: String,
    val error: String,
)

/** Wire payload for [SSEEvent.UserPending] / [SSEEvent.UserApproved]. */
@Serializable
data class UserPayload(
    @SerialName("user")
    val user: SSEUserData,
)

/** Wire payload for [SSEEvent.UserDeleted]. `reason` is server-supplied (e.g. admin action vs self-delete). */
@Serializable
data class UserDeletedPayload(
    @SerialName("user_id") val userId: String,
    val reason: String? = null,
)

/** Wire payload for [SSEEvent.CollectionCreated] / [SSEEvent.CollectionUpdated]. */
@Serializable
data class CollectionPayload(
    val id: String,
    val name: String,
    @SerialName("book_count") val bookCount: Int,
)

/** Wire payload for [SSEEvent.CollectionDeleted]. */
@Serializable
data class CollectionDeletedPayload(
    @SerialName("id")
    val id: String,
    val name: String,
)

/** Wire payload for [SSEEvent.CollectionBookAdded] / [SSEEvent.CollectionBookRemoved]. */
@Serializable
data class CollectionBookPayload(
    @SerialName("collection_id") val collectionId: String,
    @SerialName("collection_name") val collectionName: String,
    @SerialName("book_id") val bookId: String,
)

/** Wire payload for [SSEEvent.ShelfCreated] / [SSEEvent.ShelfUpdated]. Carries denormalised owner display fields. */
@Serializable
data class ShelfPayload(
    val id: String,
    @SerialName("owner_id") val ownerId: String,
    val name: String,
    val description: String? = null,
    @SerialName("book_count") val bookCount: Int,
    @SerialName("owner_display_name") val ownerDisplayName: String,
    @SerialName("owner_avatar_color") val ownerAvatarColor: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

/** Wire payload for [SSEEvent.ShelfDeleted]. */
@Serializable
data class ShelfDeletedPayload(
    val id: String,
    @SerialName("owner_id") val ownerId: String,
)

/** Wire payload for [SSEEvent.ShelfBookAdded] / [SSEEvent.ShelfBookRemoved]. */
@Serializable
data class ShelfBookPayload(
    @SerialName("shelf_id") val shelfId: String,
    @SerialName("owner_id") val ownerId: String,
    @SerialName("book_id") val bookId: String,
    @SerialName("book_count") val bookCount: Int,
)

/** Wire payload for [SSEEvent.TagCreated]. */
@Serializable
data class TagPayload(
    val id: String,
    val slug: String,
    @SerialName("book_count") val bookCount: Int,
)

/**
 * Payload for `book.tag_added` / `book.tag_removed`.
 *
 * Wire shape is nested `{"book_id": ..., "tag": {"id", "slug", "book_count", ...}}` —
 * verified against `SSEBookTagAddedEvent` in `SyncModels.kt :648` (the plan's flat
 * field list was a plan-writer oversight; corrected here).
 */
@Serializable
data class BookTagPayload(
    @SerialName("book_id") val bookId: String,
    val tag: BookTagInnerPayload,
)

/** Inner `tag` object nested inside [BookTagPayload]. */
@Serializable
data class BookTagInnerPayload(
    val id: String,
    val slug: String,
    @SerialName("book_count") val bookCount: Int,
)

/**
 * Payload for `inbox.book_added`.
 *
 * Wire shape is `{"book": {"id", "title", "author", "cover_url", "duration"}}` —
 * verified against `SSEInboxBookAddedEvent` / `SSEInboxBookData` in
 * `SyncModels.kt :676-697`. The plan's flat `{book_id, title}` shape was a
 * plan-writer oversight; corrected here.
 */
@Serializable
data class InboxBookAddedPayload(
    @SerialName("book")
    val book: InboxBookData,
)

/** Inner `book` object nested inside [InboxBookAddedPayload]. */
@Serializable
data class InboxBookData(
    val id: String,
    val title: String,
    val author: String? = null,
    @SerialName("cover_url") val coverUrl: String? = null,
    val duration: Long = 0,
)

/** Wire payload for [SSEEvent.InboxBookReleased]. */
@Serializable
data class InboxBookReleasedPayload(
    @SerialName("book_id") val bookId: String,
)

/** Wire payload for [SSEEvent.ProgressUpdated]. `progress` is a 0.0-1.0 fraction. */
@Serializable
data class ProgressPayload(
    @SerialName("book_id") val bookId: String,
    @SerialName("current_position_ms") val currentPositionMs: Long,
    val progress: Double,
    @SerialName("total_listen_time_ms") val totalListenTimeMs: Long,
    @SerialName("is_finished") val isFinished: Boolean,
    @SerialName("last_played_at") val lastPlayedAt: String,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("finished_at") val finishedAt: String? = null,
)

/** Wire payload for [SSEEvent.ProgressDeleted]. */
@Serializable
data class ProgressDeletedPayload(
    @SerialName("book_id") val bookId: String,
)

/** Wire payload for [SSEEvent.SessionStarted]. */
@Serializable
data class SessionStartedPayload(
    @SerialName("session_id") val sessionId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("book_id") val bookId: String,
    @SerialName("started_at") val startedAt: String,
)

/** Wire payload for [SSEEvent.SessionEnded]. */
@Serializable
data class SessionEndedPayload(
    @SerialName("session_id") val sessionId: String,
)

/** Wire payload for [SSEEvent.ReadingSessionUpdated]. */
@Serializable
data class ReadingSessionUpdatedPayload(
    @SerialName("session_id") val sessionId: String,
    @SerialName("book_id") val bookId: String,
    @SerialName("is_completed") val isCompleted: Boolean,
    @SerialName("listen_time_ms") val listenTimeMs: Long,
    @SerialName("finished_at") val finishedAt: String? = null,
)

/** Wire payload for [SSEEvent.ListeningEventCreated] — a single bounded play span. */
@Serializable
data class ListeningEventPayload(
    val id: String,
    @SerialName("book_id") val bookId: String,
    @SerialName("start_position_ms") val startPositionMs: Long,
    @SerialName("end_position_ms") val endPositionMs: Long,
    @SerialName("started_at") val startedAt: String,
    @SerialName("ended_at") val endedAt: String,
    @SerialName("playback_speed") val playbackSpeed: Float,
    @SerialName("device_id") val deviceId: String,
    @SerialName("created_at") val createdAt: String,
)

/** Wire payload for [SSEEvent.UserStatsUpdated]. */
@Serializable
data class UserStatsPayload(
    @SerialName("user_id") val userId: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("avatar_type") val avatarType: String,
    @SerialName("avatar_value") val avatarValue: String? = null,
    @SerialName("avatar_color") val avatarColor: String,
    @SerialName("total_time_ms") val totalTimeMs: Long,
    @SerialName("total_books") val totalBooks: Int,
    @SerialName("current_streak") val currentStreak: Int,
)

/** Wire payload for [SSEEvent.ProfileUpdated]. [displayName] is a derived convenience field. */
@Serializable
data class ProfilePayload(
    @SerialName("user_id") val userId: String,
    @SerialName("first_name") val firstName: String,
    @SerialName("last_name") val lastName: String,
    @SerialName("avatar_type") val avatarType: String,
    @SerialName("avatar_value") val avatarValue: String? = null,
    @SerialName("avatar_color") val avatarColor: String,
    val tagline: String? = null,
) {
    val displayName: String get() = "$firstName $lastName".trim()
}

/** Wire payload for [SSEEvent.ActivityCreated]. Most fields are nullable since activity types vary (book finished, milestone reached, shelf shared, etc.). */
@Serializable
data class ActivityPayload(
    val id: String,
    @SerialName("user_id") val userId: String,
    val type: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("user_display_name") val userDisplayName: String,
    @SerialName("user_avatar_color") val userAvatarColor: String,
    @SerialName("user_avatar_type") val userAvatarType: String = "auto",
    @SerialName("user_avatar_value") val userAvatarValue: String? = null,
    @SerialName("book_id") val bookId: String? = null,
    @SerialName("book_title") val bookTitle: String? = null,
    @SerialName("book_author_name") val bookAuthorName: String? = null,
    @SerialName("book_cover_path") val bookCoverPath: String? = null,
    @SerialName("is_reread") val isReread: Boolean = false,
    @SerialName("duration_ms") val durationMs: Long = 0,
    @SerialName("milestone_value") val milestoneValue: Int = 0,
    @SerialName("milestone_unit") val milestoneUnit: String? = null,
    @SerialName("shelf_id") val shelfId: String? = null,
    @SerialName("shelf_name") val shelfName: String? = null,
)
