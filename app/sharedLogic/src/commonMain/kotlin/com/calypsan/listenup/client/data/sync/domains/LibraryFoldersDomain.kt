package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.api.sync.LibraryFolderSyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.entity.LibraryFolderEntity

/**
 * The `library_folders` domain: rows created/deleted only by the LibraryAdminService
 * RPC — server-wins apply, soft-delete tombstones, full digest, online-only writes.
 * Folder rows FK to `libraries`; the server guarantees the parent library's event
 * precedes folder events during catch-up, so the constraint holds without client
 * ordering logic.
 */
internal fun libraryFoldersDomain(database: ListenUpDatabase): MirroredDomain<LibraryFolderSyncPayload> {
    val apply = LibraryFolderMirrorApply(database)
    return MirroredDomain(
        key = SyncDomains.LIBRARY_FOLDERS,
        apply = apply,
        conflict = ConflictPolicy.ServerWins(RevisionGuard { id -> database.libraryFolderDao().revisionOf(id) }),
        deletes = DeleteSemantics.SoftDelete(apply::tombstoneById),
        digest = fullDigest(database.libraryFolderDao()::digestRows),
        writes = WriteTier.OnlineOnly,
    )
}

/** Room mapping for [LibraryFolderSyncPayload] payloads. */
internal class LibraryFolderMirrorApply(
    private val database: ListenUpDatabase,
) : MirrorApply<LibraryFolderSyncPayload> {
    override suspend fun upsert(payload: LibraryFolderSyncPayload) {
        database.libraryFolderDao().upsert(
            LibraryFolderEntity(
                id = payload.id,
                libraryId = payload.libraryId,
                rootPath = payload.rootPath,
                createdAt = payload.createdAt,
                revision = payload.revision,
                deletedAt = payload.deletedAt,
            ),
        )
    }

    suspend fun tombstoneById(
        id: String,
        deletedAt: Long,
        revision: Long,
    ) {
        database.libraryFolderDao().softDelete(id = id, deletedAt = deletedAt, revision = revision)
    }

    override suspend fun tombstoneFromItem(item: LibraryFolderSyncPayload) {
        tombstoneById(
            id = item.id,
            deletedAt = item.deletedAt ?: item.updatedAt,
            revision = item.revision,
        )
    }
}
