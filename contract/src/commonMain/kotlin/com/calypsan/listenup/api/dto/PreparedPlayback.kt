package com.calypsan.listenup.api.dto

import com.calypsan.listenup.api.sync.PlaybackPositionSyncPayload
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One prepared, signed audio file in a book's playback list. */
@Serializable
@SerialName("PreparedAudioFile")
data class PreparedAudioFile(
    val fileId: String,
    val index: Int,
    val url: String,
    val format: String,
    val durationMs: Long,
    val sizeBytes: Long,
)

/**
 * Everything the client player needs to start a book — one RPC call.
 * Returned by [com.calypsan.listenup.api.PlaybackService.prepare].
 */
@Serializable
@SerialName("PreparedPlayback")
data class PreparedPlayback(
    val bookId: String,
    val audioFiles: List<PreparedAudioFile>,
    val resumePosition: PlaybackPositionSyncPayload?,
    /** Server-relative, signature-authed cover URL a Cast receiver can fetch (no header). Null when not minted. */
    val coverUrl: String? = null,
)

/**
 * A client→server playback-position write. The owning user is NOT carried —
 * the server takes it from the authenticated principal. `lastPlayedAt` is the
 * conflict key (server keeps the greatest).
 */
@Serializable
@SerialName("RecordPositionRequest")
data class RecordPositionRequest(
    val bookId: String,
    val positionMs: Long,
    val lastPlayedAt: Long,
    val finished: Boolean,
    val playbackSpeed: Float,
    val currentChapterId: String?,
    // The furthest position ever heard in this book, carried from the local row's current
    // high-water mark — the same protective idiom as `finished` above. The server folds it
    // into an order-independent max-merge (see PlaybackPositionRepository.recordPosition),
    // outside the lastPlayedAt staleness gate, so a stale-but-higher write still raises the
    // stored max. Default 0 keeps old servers/clients wire-compatible: `max(0, x) = x`.
    @SerialName("maxPositionMs") val maxPositionMs: Long = 0,
)

/**
 * A client→server listening-event write. The owning user is NOT carried — the
 * server takes it from the authenticated principal. `id` is client-assigned
 * (a `Uuid.random().toString()` matching the local tentative-span row's id);
 * re-recording the same id is a no-op on the substrate's `upsert` — safe for
 * the pending-operation queue to re-fire.
 */
@Serializable
@SerialName("RecordListeningEventRequest")
data class RecordListeningEventRequest(
    val id: String,
    val bookId: String,
    val startPositionMs: Long,
    val endPositionMs: Long,
    val startedAt: Long,
    val endedAt: Long,
    val playbackSpeed: Float,
    val tz: String,
    val deviceLabel: String?,
)
