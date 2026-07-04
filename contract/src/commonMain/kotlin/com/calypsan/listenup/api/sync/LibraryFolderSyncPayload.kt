package com.calypsan.listenup.api.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire DTO for a library folder — one root filesystem path registered under
 * a parent [LibrarySyncPayload].
 *
 * A library may have N folders. Each folder carries the absolute server-side
 * path to its root directory (`rootPath`). The unique-partial-index on
 * `root_path WHERE deleted_at IS NULL` prevents the same path being registered
 * under two live libraries while allowing tombstoned rows to coexist for sync
 * history.
 *
 * Implements [Tombstoned] so the substrate's soft-delete routing applies
 * uniformly. `@SerialName` is the wire-stable discriminator; field renames
 * break wire compatibility, additions are forward-compatible.
 */
@Serializable
@SerialName("LibraryFolderSyncPayload")
data class LibraryFolderSyncPayload(
    override val id: String,
    val libraryId: String,
    val rootPath: String,
    override val revision: Long,
    val updatedAt: Long,
    val createdAt: Long,
    override val deletedAt: Long?,
) : SyncPayload
