package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.dto.CollectionBookMutation
import com.calypsan.listenup.api.dto.CollectionMutation
import com.calypsan.listenup.api.dto.CollectionShareDto
import com.calypsan.listenup.api.dto.CollectionSummary
import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.api.error.CollectionError
import com.calypsan.listenup.client.data.local.db.CollectionBookDao
import com.calypsan.listenup.client.data.local.db.CollectionBookEntity
import com.calypsan.listenup.client.data.local.db.CollectionEntity
import com.calypsan.listenup.client.data.local.db.CollectionShareDao
import com.calypsan.listenup.client.data.local.db.CollectionShareEntity
import com.calypsan.listenup.client.data.local.db.CollectionWithBookCount
import com.calypsan.listenup.api.CollectionService
import com.calypsan.listenup.client.data.local.db.CollectionDao
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.sync.OfflineEditor
import com.calypsan.listenup.client.data.sync.domains.OpKind
import com.calypsan.listenup.client.data.sync.domains.OutboxChannels
import com.calypsan.listenup.client.domain.model.Collection
import com.calypsan.listenup.client.domain.model.CollectionShare
import com.calypsan.listenup.client.domain.repository.CollectionRepository
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.CollectionId
import com.calypsan.listenup.core.currentEpochMilliseconds
import com.calypsan.listenup.api.result.map
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Collection repository — Room-backed reads, RPC-dispatched mutations.
 *
 * Reads (`observeCollections`, `observeCollectionBooks`, `observeShares`) come from
 * the local Room mirror, which the sync engine populates via the substrate's firehose
 * stream and the collection sync handlers. `bookCount` on the returned [Collection]
 * is computed at read time via JOIN on `collection_books` — there is no denormalized
 * column.
 *
 * **Mutation:** offline-first where it can be mirrored, online where it can't.
 * - `rename`, `delete`, `addBook`, `removeBook` write Room optimistically and enqueue a durable op
 *   (via [OfflineEditor.edit]) — lifecycle edits on the `collections` channel keyed by collection id,
 *   junction edits on the `collection_books` channel keyed by the `"$collectionId:$bookId"` envelope
 *   id — so an edit made offline persists and replays on reconnect. The entity-level in-flight shield
 *   defers each row's own echo until its op drains.
 * - `create` stays online (the server mints the collection's id); `share`/`revokeShare`
 *   stay online (ACL changes are genuinely server-required).
 *
 * @property offlineEditor Composes the optimistic Room merge and the durable outbox enqueue into a
 *   single transaction for the offline-first surfaces.
 */
internal class CollectionRepositoryImpl(
    private val collectionDao: CollectionDao,
    private val collectionBookDao: CollectionBookDao,
    private val collectionShareDao: CollectionShareDao,
    private val channel: RpcChannel<CollectionService>,
    private val offlineEditor: OfflineEditor,
) : CollectionRepository {
    // ── Observation ───────────────────────────────────────────────────────────

    override fun observeCollections(): Flow<List<Collection>> =
        collectionDao.observeAllWithBookCount().map { rows -> rows.map { it.toDomain() } }

    override fun observeCollectionBooks(collectionId: String): Flow<List<String>> =
        collectionBookDao.observeBookIds(collectionId)

    override fun observeBookCollectionIds(bookId: String): Flow<List<String>> =
        collectionBookDao.observeCollectionIdsForBook(bookId)

    override fun observeShares(collectionId: String): Flow<List<CollectionShare>> =
        collectionShareDao.observeForCollection(collectionId).map { rows -> rows.map { it.toDomain() } }

    // ── Mutation (RPC) ──────────────────────────────────────────────────────────

    override suspend fun create(
        libraryId: String,
        name: String,
    ): AppResult<Collection> =
        channel
            .call { it.createCollection(libraryId, name) }
            .also { if (it is AppResult.Success) mirrorCreatedCollection(libraryId, it.data) }
            .map { it.toDomain() }

    /**
     * Offline-first: apply the new name to Room and enqueue a durable op on the `collections` channel
     * keyed by the collection id. Returns the optimistic aggregate; [CollectionError.NotFound] when the
     * collection isn't in Room. The collection's own echo (deferred by the in-flight shield) is final.
     */
    override suspend fun rename(
        id: String,
        name: String,
    ): AppResult<Collection> {
        val existing = collectionDao.getById(id) ?: return AppResult.Failure(CollectionError.NotFound())
        val renamed = existing.copy(name = name)
        val domain = renamed.toDomain(bookCount = collectionBookDao.liveBookCountFor(id))
        return offlineEditor
            .edit(OutboxChannels.Collections, id, CollectionMutation.Rename(name)) {
                collectionDao.upsert(renamed)
            }.map { domain }
    }

    /**
     * Offline-first: soft-delete the collection and cascade-tombstone its `collection_books` junctions
     * (mirroring the server's `deleteCollection` cascade), then enqueue a durable op on the `collections`
     * channel keyed by the collection id. The collection's revision is preserved so its own echo
     * (deferred by the in-flight shield) re-applies the authoritative tombstone on drain.
     */
    override suspend fun delete(id: String): AppResult<Unit> {
        val now = currentEpochMilliseconds()
        return offlineEditor.edit(OutboxChannels.Collections, id, CollectionMutation.Delete, op = OpKind.Delete) {
            collectionDao
                .getById(
                    id,
                )?.let { collectionDao.softDelete(id = id, deletedAt = now, revision = it.revision) }
            collectionBookDao.tombstoneAllForCollection(collectionId = id, deletedAt = now)
        }
    }

    /**
     * Offline-first: upsert the junction optimistically (revision-0 stub, clearing any tombstone) and
     * enqueue a durable op on the `collection_books` channel keyed by the `"$collectionId:$bookId"`
     * envelope id. Idempotent server-side; the book already exists so no server id is minted.
     */
    override suspend fun addBook(
        collectionId: String,
        bookId: String,
    ): AppResult<Unit> =
        offlineEditor.edit(
            OutboxChannels.CollectionBooks,
            "$collectionId:$bookId",
            CollectionBookMutation.Add(collectionId = collectionId, bookId = bookId),
            op = OpKind.Create,
        ) {
            collectionBookDao.upsert(
                CollectionBookEntity(
                    collectionId = collectionId,
                    bookId = bookId,
                    syncId = Uuid.random().toString(),
                    createdAt = currentEpochMilliseconds(),
                    revision = 0,
                    deletedAt = null,
                ),
            )
        }

    /**
     * Offline-first: tombstone the junction optimistically and enqueue a durable op on the
     * `collection_books` channel keyed by the same `"$collectionId:$bookId"` envelope id the junction's
     * mirror row uses so the in-flight shield and reconcile-on-drain align. Idempotent server-side.
     */
    override suspend fun removeBook(
        collectionId: String,
        bookId: String,
    ): AppResult<Unit> =
        offlineEditor.edit(
            OutboxChannels.CollectionBooks,
            "$collectionId:$bookId",
            CollectionBookMutation.Remove(collectionId = collectionId, bookId = bookId),
            op = OpKind.Delete,
        ) {
            collectionBookDao.tombstone(
                collectionId = collectionId,
                bookId = bookId,
                deletedAt = currentEpochMilliseconds(),
                revision = 0,
            )
        }

    override suspend fun share(
        collectionId: String,
        sharedWithUserId: String,
        permission: SharePermission,
    ): AppResult<CollectionShare> =
        channel
            .call {
                it.shareCollection(CollectionId(collectionId), sharedWithUserId, permission)
            }.map { it.toDomain() }

    override suspend fun revokeShare(
        collectionId: String,
        sharedWithUserId: String,
    ): AppResult<Unit> = channel.call { it.revokeShare(CollectionId(collectionId), sharedWithUserId) }

    // ── Plumbing ────────────────────────────────────────────────────────────────

    /**
     * Optimistically mirror a just-created collection into Room so it appears immediately, without
     * waiting for the firehose echo (offline-first / never-stranded). Insert-if-absent at revision 0: the
     * authoritative echo (revision >= 1) always wins via the domain's ServerWins/RevisionGuard, and a
     * digest reconcile self-heals if the echo landed first — so this never overwrites an echoed row.
     */
    private suspend fun mirrorCreatedCollection(
        libraryId: String,
        summary: CollectionSummary,
    ) {
        if (collectionDao.revisionOf(summary.id.value) != null) return
        collectionDao.upsert(
            CollectionEntity(
                id = summary.id.value,
                libraryId = libraryId,
                ownerId = summary.ownerId.value,
                name = summary.name,
                isInbox = summary.isInbox,
                isSystem = summary.isSystem,
                revision = 0,
                deletedAt = null,
                updatedAt = currentEpochMilliseconds(),
            ),
        )
    }
}

private fun CollectionWithBookCount.toDomain(): Collection =
    Collection(
        id = collection.id,
        name = collection.name,
        ownerId = collection.ownerId,
        isInbox = collection.isInbox,
        isSystem = collection.isSystem,
        bookCount = bookCount,
        // Room mirror does not carry the caller's effective permission; ownership is
        // derived by the caller (admin context). Default to Write — admins manage
        // every collection they can see (the 2a admin-only product model).
        callerPermission = SharePermission.Write,
        isOwner = true,
    )

/**
 * Map a substrate [CollectionEntity] plus its live [bookCount] to the domain model — the offline-first
 * rename's optimistic return. Mirrors [CollectionWithBookCount.toDomain]'s admin-context defaults
 * (Write permission, owner) since the local mirror carries no per-caller permission.
 */
private fun CollectionEntity.toDomain(bookCount: Int): Collection =
    Collection(
        id = id,
        name = name,
        ownerId = ownerId,
        isInbox = isInbox,
        isSystem = isSystem,
        bookCount = bookCount,
        callerPermission = SharePermission.Write,
        isOwner = true,
    )

private fun CollectionSummary.toDomain(): Collection =
    Collection(
        id = id.value,
        name = name,
        ownerId = ownerId.value,
        isInbox = isInbox,
        isSystem = isSystem,
        bookCount = bookCount.toInt(),
        callerPermission = callerPermission,
        isOwner = isOwner,
    )

private fun CollectionShareDto.toDomain(): CollectionShare =
    CollectionShare(
        id = id,
        collectionId = collectionId.value,
        sharedWithUserId = sharedWithUserId.value,
        permission = permission,
    )

private fun CollectionShareEntity.toDomain(): CollectionShare =
    CollectionShare(
        id = id,
        collectionId = collectionId,
        sharedWithUserId = sharedWithUserId,
        permission = if (permission == "write") SharePermission.Write else SharePermission.Read,
    )
