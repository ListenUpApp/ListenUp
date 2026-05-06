package com.calypsan.listenup.client.data.sync.push

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Payload for BOOK_UPDATE operations.
 *
 * All fields are nullable - only non-null fields are sent to server.
 * Mirrors [BookUpdateRequest] from the API layer.
 */
@Serializable
data class BookUpdatePayload(
    val title: String? = null,
    @SerialName("sort_title")
    val sortTitle: String? = null,
    val subtitle: String? = null,
    val description: String? = null,
    val publisher: String? = null,
    @SerialName("publish_year")
    val publishYear: String? = null,
    val language: String? = null,
    val isbn: String? = null,
    val asin: String? = null,
    val abridged: Boolean? = null,
    @SerialName("created_at")
    val createdAt: String? = null, // ISO8601 timestamp
)

/**
 * Payload for CONTRIBUTOR_UPDATE operations.
 */
@Serializable
data class ContributorUpdatePayload(
    val name: String? = null,
    val biography: String? = null,
    val website: String? = null,
    @SerialName("birth_date")
    val birthDate: String? = null,
    @SerialName("death_date")
    val deathDate: String? = null,
    val aliases: List<String>? = null,
)

/**
 * Payload for SERIES_UPDATE operations.
 */
@Serializable
data class SeriesUpdatePayload(
    val name: String? = null,
    val description: String? = null,
)

/**
 * Payload for SET_BOOK_CONTRIBUTORS operations.
 * Full replacement - all contributors for the book.
 */
@Serializable
data class SetBookContributorsPayload(
    val contributors: List<ContributorInput>,
)

/**
 * One contributor entry in a [SetBookContributorsPayload]: the [name] resolved or created
 * server-side, and the [roles] (e.g. AUTHOR, NARRATOR) the contributor holds for this book.
 */
@Serializable
data class ContributorInput(
    val name: String,
    val roles: List<String>,
)

/**
 * Payload for SET_BOOK_SERIES operations.
 * Full replacement - all series for the book.
 */
@Serializable
data class SetBookSeriesPayload(
    val series: List<SeriesInput>,
)

/**
 * One series entry in a [SetBookSeriesPayload]: the series [name] (resolved or created
 * server-side) and the optional [sequence] number for this book within the series.
 */
@Serializable
data class SeriesInput(
    val name: String,
    val sequence: String?,
)

/**
 * Payload for MERGE_CONTRIBUTOR operations.
 */
@Serializable
data class MergeContributorPayload(
    @SerialName("source_id")
    val sourceId: String,
    @SerialName("target_id")
    val targetId: String,
)

/**
 * Payload for UNMERGE_CONTRIBUTOR operations.
 */
@Serializable
data class UnmergeContributorPayload(
    @SerialName("contributor_id")
    val contributorId: String,
    @SerialName("alias_name")
    val aliasName: String,
)

/**
 * Payload for LISTENING_EVENT operations.
 * Stored individually, batched together during execution.
 */
@Serializable
data class ListeningEventPayload(
    val id: String,
    @SerialName("book_id")
    val bookId: String,
    @SerialName("start_position_ms")
    val startPositionMs: Long,
    @SerialName("end_position_ms")
    val endPositionMs: Long,
    @SerialName("started_at")
    val startedAt: Long,
    @SerialName("ended_at")
    val endedAt: Long,
    @SerialName("playback_speed")
    val playbackSpeed: Float,
    @SerialName("device_id")
    val deviceId: String,
)

/**
 * Payload for PLAYBACK_POSITION operations.
 */
@Serializable
data class PlaybackPositionPayload(
    @SerialName("book_id")
    val bookId: String,
    @SerialName("position_ms")
    val positionMs: Long,
    @SerialName("playback_speed")
    val playbackSpeed: Float,
    @SerialName("updated_at")
    val updatedAt: Long,
)

/**
 * Payload for USER_PREFERENCES operations.
 */
@Serializable
data class UserPreferencesPayload(
    @SerialName("default_playback_speed")
    val defaultPlaybackSpeed: Float? = null,
)

/**
 * Payload for PROFILE_UPDATE operations.
 *
 * Coalesces by user - only the final values matter.
 * Used for tagline, avatar type, name, and password updates.
 */
@Serializable
data class ProfileUpdatePayload(
    val tagline: String? = null,
    @SerialName("avatar_type")
    val avatarType: String? = null,
    @SerialName("first_name")
    val firstName: String? = null,
    @SerialName("last_name")
    val lastName: String? = null,
    @SerialName("new_password")
    val newPassword: String? = null,
)

/**
 * Payload for PROFILE_AVATAR operations.
 *
 * Contains Base64-encoded image data for offline storage.
 * Does not coalesce - each avatar upload replaces previous.
 */
@Serializable
data class ProfileAvatarPayload(
    @SerialName("image_data_base64")
    val imageDataBase64: String,
    @SerialName("content_type")
    val contentType: String,
)

/**
 * Payload for MARK_COMPLETE operations.
 * Coalesces by book - only the latest timestamps matter.
 */
@Serializable
data class MarkCompletePayload(
    @SerialName("book_id")
    val bookId: String,
    @SerialName("started_at")
    val startedAt: String? = null,
    @SerialName("finished_at")
    val finishedAt: String? = null,
)

/**
 * Payload for DISCARD_PROGRESS operations.
 * Coalesces by book - latest discard wins (idempotent on the server).
 */
@Serializable
data class DiscardProgressPayload(
    @SerialName("book_id")
    val bookId: String,
)

/**
 * Payload for RESTART_BOOK operations.
 * Coalesces by book - latest restart wins (idempotent on the server).
 */
@Serializable
data class RestartBookPayload(
    @SerialName("book_id")
    val bookId: String,
)

/**
 * Payload for END_PLAYBACK_SESSION operations.
 * Coalesces by book - latest duration wins for the same book.
 */
@Serializable
data class EndPlaybackSessionPayload(
    @SerialName("book_id")
    val bookId: String,
    @SerialName("duration_ms")
    val durationMs: Long,
)
