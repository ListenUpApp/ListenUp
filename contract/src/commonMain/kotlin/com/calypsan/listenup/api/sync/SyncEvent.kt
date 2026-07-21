package com.calypsan.listenup.api.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Envelope for firehose events on the cross-domain sync surface.
 *
 * Three variants:
 *  - [Created] / [Updated] carry the full domain payload (fat events — saves a round trip).
 *  - [Deleted] carries no payload; the row's tombstone is its own signal.
 *
 * `clientOpId` is the originating client's operation id, propagated server-side from the
 * write API, so firehose echoes match pending operations on the client without re-applying.
 * Server-originated writes (e.g. scanner-driven during a library scan) leave it null.
 *
 * Stable `@SerialName` discriminators — renames are wire breaks.
 */
@Serializable
sealed interface SyncEvent<out T> {
    /** Stable entity identifier. */
    val id: String

    /** Global revision at write time. Also the stream resume cursor ([SyncFrame.revision]). */
    val revision: Long

    /** Wall-clock millis (server-side) when the event was emitted. */
    val occurredAt: Long

    /** Originating client op id for echo matching, null for server-originated writes. */
    val clientOpId: String?

    /** Successful create. Carries the full domain payload. */
    @Serializable
    @SerialName("SyncEvent.Created")
    data class Created<T>(
        override val id: String,
        override val revision: Long,
        override val occurredAt: Long,
        override val clientOpId: String? = null,
        val payload: T,
    ) : SyncEvent<T>

    /** Successful update. Carries the full domain payload. */
    @Serializable
    @SerialName("SyncEvent.Updated")
    data class Updated<T>(
        override val id: String,
        override val revision: Long,
        override val occurredAt: Long,
        override val clientOpId: String? = null,
        val payload: T,
    ) : SyncEvent<T>

    /** Soft-delete tombstone. No payload — clients apply via id. */
    @Serializable
    @SerialName("SyncEvent.Deleted")
    data class Deleted(
        override val id: String,
        override val revision: Long,
        override val occurredAt: Long,
        override val clientOpId: String? = null,
    ) : SyncEvent<Nothing>
}
