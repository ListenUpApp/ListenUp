package com.calypsan.listenup.api.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire DTO for a library — a named, operator-configured collection of
 * zero-or-more [LibraryFolderSyncPayload] roots.
 *
 * Libraries are server-wide (cross-user) in the current single-user model.
 * `accessMode` and `createdByUserId` are forward-staged for multi-user
 * support; they are present on the wire today but not enforced.
 *
 * `metadataPrecedence` is the comma-separated source list that governs
 * which metadata source wins when multiple sources exist for the same field
 * (e.g. `"embedded,abs,sidecar"`).
 *
 * Implements [Tombstoned] so the substrate's soft-delete routing applies
 * uniformly. `@SerialName` is the wire-stable discriminator; field renames
 * break wire compatibility, additions are forward-compatible.
 */
@Serializable
@SerialName("LibrarySyncPayload")
data class LibrarySyncPayload(
    override val id: String,
    val name: String,
    val metadataPrecedence: String,
    val accessMode: String,
    val createdByUserId: String?,
    override val revision: Long,
    val updatedAt: Long,
    val createdAt: Long,
    override val deletedAt: Long?,
    /**
     * Epoch-ms when this library's first-ever scan completed; null until then. Server-authoritative
     * signal driving the client initial-population gate.
     */
    val initialScanCompletedAt: Long? = null,
) : SyncPayload
