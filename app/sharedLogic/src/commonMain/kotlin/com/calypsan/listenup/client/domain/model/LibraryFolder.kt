package com.calypsan.listenup.client.domain.model

/**
 * Domain model representing one root filesystem path registered under a parent [Library].
 *
 * A library may have N folders. Each folder carries the absolute server-side path to its
 * root directory. Folders are created and deleted through the `LibraryAdminService` RPC;
 * there is no client-side write path.
 *
 * @property id Unique folder identifier.
 * @property libraryId Parent library identifier.
 * @property rootPath Absolute server-side filesystem path to this folder's root directory.
 * @property createdAt Creation timestamp as Unix epoch milliseconds.
 * @property revision Monotonic server revision, advanced on every committed change.
 */
data class LibraryFolder(
    val id: String,
    val libraryId: String,
    val rootPath: String,
    val createdAt: Long,
    val revision: Long,
)
