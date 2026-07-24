package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.api.sync.LibrarySyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.entity.LibraryEntity

/**
 * The `libraries` domain: cross-user rows written only via the LibraryAdminService
 * RPC — server-wins apply, soft-delete tombstones, full digest, online-only writes.
 */
internal fun librariesDomain(database: ListenUpDatabase): MirroredDomain<LibrarySyncPayload> {
    val apply = LibraryMirrorApply(database)
    return MirroredDomain(
        key = SyncDomains.LIBRARIES,
        apply = apply,
        conflict = ConflictPolicy.ServerWins(RevisionGuard { id -> database.libraryDao().revisionOf(id) }),
        deletes = DeleteSemantics.SoftDelete(apply::tombstoneById),
        digest = fullDigest(database.libraryDao()::digestRows),
        writes = WriteTier.OnlineOnly,
    )
}

/** Room mapping for [LibrarySyncPayload] payloads. */
internal class LibraryMirrorApply(
    private val database: ListenUpDatabase,
) : MirrorApply<LibrarySyncPayload> {
    override suspend fun upsert(payload: LibrarySyncPayload) {
        database.libraryDao().upsert(
            LibraryEntity(
                id = payload.id,
                name = payload.name,
                metadataPrecedence = payload.metadataPrecedence,
                accessMode = payload.accessMode,
                createdByUserId = payload.createdByUserId,
                createdAt = payload.createdAt,
                revision = payload.revision,
                deletedAt = payload.deletedAt,
                initialScanCompletedAt = payload.initialScanCompletedAt,
            ),
        )
    }

    suspend fun tombstoneById(
        id: String,
        deletedAt: Long,
        revision: Long,
    ) {
        database.libraryDao().softDelete(id = id, deletedAt = deletedAt, revision = revision)
    }

    override suspend fun tombstoneFromItem(item: LibrarySyncPayload) {
        tombstoneById(
            id = item.id,
            deletedAt = item.deletedAt ?: item.updatedAt,
            revision = item.revision,
        )
    }
}
