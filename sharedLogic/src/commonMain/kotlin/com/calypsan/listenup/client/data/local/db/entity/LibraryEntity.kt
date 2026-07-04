package com.calypsan.listenup.client.data.local.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity representing a library — a named, operator-configured collection of
 * zero-or-more [LibraryFolderEntity] roots.
 *
 * Carries the sync substrate ([revision], [deletedAt]) that the catch-up and
 * SSE sync routes depend on. Server commits arrive as [LibrarySyncPayload] events
 * and are applied into this projection; the UI reads Room exclusively.
 *
 * `accessMode` is stored as a raw string and mapped to the [com.calypsan.listenup.client.domain.model.AccessMode]
 * enum at the repository boundary. This avoids a Room TypeConverter dependency on a domain type.
 *
 * `createdByUserId` and `accessMode` are forward-staged for the multi-user enforcement phase;
 * they are present today but not enforced client-side.
 */
@Entity(tableName = "libraries")
internal data class LibraryEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** Comma-separated source list governing metadata precedence (e.g. `"embedded,abs,sidecar"`). */
    val metadataPrecedence: String,
    /** Raw enum string; mapped to [com.calypsan.listenup.client.domain.model.AccessMode] in the repository. */
    val accessMode: String,
    /** User ID of the library creator — null until the multi-user phase enforces it. */
    val createdByUserId: String?,
    /** Creation timestamp as Unix epoch milliseconds. */
    val createdAt: Long,
    /** Monotonic server revision, advanced on every committed change. */
    val revision: Long,
    /** Epoch ms tombstone; null when the library is live. */
    val deletedAt: Long?,
    /**
     * Epoch-ms when this library's first-ever scan completed; null until then. Server-authoritative
     * signal driving the client initial-population ("Building your library") gate.
     */
    val initialScanCompletedAt: Long?,
)
