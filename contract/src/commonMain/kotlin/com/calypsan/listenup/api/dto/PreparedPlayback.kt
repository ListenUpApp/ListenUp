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
)
