package com.calypsan.listenup.api.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A reading order (#962) as synced: library-wide, so every authenticated user receives every row, like
 * series. [createdBy] is the maker's user id — clients show "By <name>" and decide who may edit.
 */
@Serializable
@SerialName("ReadingOrderSyncPayload")
data class ReadingOrderSyncPayload(
    /** Client-minted identifier (UUID). */
    @SerialName("id") override val id: String,
    /** The series this order belongs to, at any level of the hierarchy. */
    @SerialName("seriesId") val seriesId: String,
    /** Display name, unique per series (case- and whitespace-insensitive). */
    @SerialName("name") val name: String,
    /** The maker's user id. */
    @SerialName("createdBy") val createdBy: String,
    /** Sync revision counter — bumped on every write. */
    @SerialName("revision") override val revision: Long,
    /** Epoch millis of the last server-side write. */
    @SerialName("updatedAt") val updatedAt: Long,
    /** Epoch millis when the order was made. */
    @SerialName("createdAt") val createdAt: Long,
    @SerialName("deletedAt") override val deletedAt: Long? = null,
) : SyncPayload
