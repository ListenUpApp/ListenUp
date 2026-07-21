package com.calypsan.listenup.api.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Substrate-sync wire shape for a Genre. Crosses the wire on every sync event for a
 * genre's Created/Updated/Deleted state.
 *
 * Hierarchy is denormalized: [path] is the slash-separated slug path, [parentId] is the
 * direct parent (nullable for root genres), [depth] is precomputed.
 */
@Serializable
@SerialName("GenreSyncPayload")
data class GenreSyncPayload(
    @SerialName("id") override val id: String,
    @SerialName("name") val name: String,
    @SerialName("slug") val slug: String,
    @SerialName("path") val path: String,
    @SerialName("parentId") val parentId: String? = null,
    @SerialName("depth") val depth: Int = 0,
    @SerialName("sortOrder") val sortOrder: Int = 0,
    @SerialName("color") val color: String? = null,
    @SerialName("description") val description: String? = null,
    // Substrate bookkeeping
    @SerialName("revision") override val revision: Long = 0L,
    @SerialName("updatedAt") val updatedAt: Long = 0L,
    @SerialName("createdAt") val createdAt: Long = 0L,
    @SerialName("deletedAt") override val deletedAt: Long? = null,
) : SyncPayload

/**
 * Denormalized genre reference on [BookSyncPayload.genres].
 * Carries enough for read-time display without an additional lookup.
 */
@Serializable
@SerialName("BookGenrePayload")
data class BookGenrePayload(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String,
    @SerialName("slug") val slug: String,
    @SerialName("path") val path: String,
)
