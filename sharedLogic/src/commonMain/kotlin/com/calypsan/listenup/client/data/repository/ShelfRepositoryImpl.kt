package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.ShelfService
import com.calypsan.listenup.api.dto.shelf.DiscoveredShelf
import com.calypsan.listenup.api.dto.shelf.ShelfDetail as ShelfDetailDto
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.map
import com.calypsan.listenup.api.dto.ShelfBookMutation
import com.calypsan.listenup.api.dto.ShelfMutation
import com.calypsan.listenup.api.error.ShelfError
import com.calypsan.listenup.client.data.local.db.ShelfBookDao
import com.calypsan.listenup.client.data.local.db.ShelfBookEntity
import com.calypsan.listenup.client.data.local.db.ShelfDao
import com.calypsan.listenup.client.data.local.db.ShelfEntity
import com.calypsan.listenup.client.data.local.db.ShelfWithBookCount
import com.calypsan.listenup.client.data.local.db.UserDao
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.sync.OfflineEditor
import com.calypsan.listenup.client.data.sync.domains.OpKind
import com.calypsan.listenup.client.data.sync.domains.OutboxChannels
import com.calypsan.listenup.client.domain.model.Shelf
import com.calypsan.listenup.client.domain.model.ShelfBook
import com.calypsan.listenup.client.domain.model.ShelfDetail
import com.calypsan.listenup.client.domain.repository.ShelfRepository
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ShelfId
import com.calypsan.listenup.core.currentEpochMilliseconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Shelf repository — substrate-Room-backed reads, [com.calypsan.listenup.api.ShelfService]
 * RPC-dispatched mutations (Shelves — Room v26).
 *
 * Own-shelf reads (`observeMyShelves`, `observeById`, `getById`) come from the local Room
 * mirror, which the sync engine populates via the substrate SSE stream and the shelf sync
 * handlers. `bookCount` is JOIN-derived; `coverPaths` and `totalDurationSeconds` are derived
 * from the shelf's member books present in the local `books` mirror; owner fields are filled
 * from the current user (the local mirror holds only the caller's own shelves).
 *
 * Discovery ([discoverShelves]) is **not** own-data — other users' shelves never enter the
 * substrate. It is served on demand by [com.calypsan.listenup.api.ShelfService.discoverShelves].
 *
 * **Mutation:** offline-first where it can be mirrored, online where it can't.
 * - `updateShelf`, `deleteShelf`, `addBooksToShelf`, `removeBookFromShelf` write Room optimistically
 *   and enqueue a durable op (via [OfflineEditor.edit]) — lifecycle edits on the `shelves` channel
 *   keyed by shelf id, junction edits on the `shelf_books` channel keyed by the `"$shelfId:$bookId"`
 *   envelope id — so an edit made offline persists and replays on reconnect rather than failing with
 *   a [com.calypsan.listenup.api.error.ServerConnectError]. The entity-level in-flight shield defers
 *   each row's own echo until its op drains.
 * - `createShelf` stays online (the server mints the shelf's id); `reorderBooks`, `getUserShelves`,
 *   `discoverShelves`, and `getShelfDetail` stay online (whole-shelf reconcile / on-demand reads).
 *
 * @property dao Substrate shelf DAO (own-shelf reads + derived cover/duration queries).
 * @property shelfBookDao Substrate junction DAO (optimistic membership writes + cascade tombstone).
 * @property userDao Current-user lookup for owner fields on own shelves.
 * @property channel Dispatches [com.calypsan.listenup.api.ShelfService] RPCs through the seam.
 * @property offlineEditor Composes the optimistic Room merge and the durable outbox enqueue into a
 *   single transaction for the offline-first surfaces.
 */
internal class ShelfRepositoryImpl(
    private val dao: ShelfDao,
    private val shelfBookDao: ShelfBookDao,
    private val userDao: UserDao,
    private val channel: RpcChannel<ShelfService>,
    private val offlineEditor: OfflineEditor,
) : ShelfRepository {
    // ── Own-shelf observation (Room) ──────────────────────────────────────────────

    override fun observeMyShelves(userId: String): Flow<List<Shelf>> =
        dao.observeMyShelvesWithBookCount().map { rows -> rows.map { it.toDomain() } }

    override fun observeShelvesContainingBook(bookId: BookId): Flow<List<Shelf>> =
        dao.observeShelvesContainingBookWithBookCount(bookId.value).map { rows -> rows.map { it.toDomain() } }

    override fun observeById(id: ShelfId): Flow<Shelf?> =
        dao.observeById(id.value).map { entity ->
            entity?.toDomainWithDerived(
                coverPaths = dao.coverHashesFor(id.value),
                totalDurationMs = dao.totalDurationMsFor(id.value),
                bookCountOverride = dao.bookCountFor(id.value),
            )
        }

    override suspend fun getById(id: ShelfId): Shelf? =
        dao.getById(id.value)?.toDomainWithDerived(
            coverPaths = dao.coverHashesFor(id.value),
            totalDurationMs = dao.totalDurationMsFor(id.value),
            bookCountOverride = dao.bookCountFor(id.value),
        )

    // ── Discovery (on-demand RPC) ─────────────────────────────────────────────────

    override suspend fun getUserShelves(userId: String): AppResult<List<Shelf>> =
        channel
            .call(idempotent = true) { it.getUserShelves(UserId(userId)) }
            .map { shelves -> shelves.map { it.toDomain() } }

    override suspend fun discoverShelves(): AppResult<List<Shelf>> =
        channel
            .call(idempotent = true) { it.discoverShelves() }
            .map { discovered -> discovered.map { it.toDomain() } }

    // ── Detail (on-demand RPC) ────────────────────────────────────────────────────

    override suspend fun getShelfDetail(shelfId: ShelfId): AppResult<ShelfDetail> =
        channel
            .call(idempotent = true) { it.getShelf(shelfId) }
            .map { detail ->
                val coverHashByBook = dao.coverHashesByBookFor(shelfId.value).associate { it.bookId to it.coverHash }
                detail.toDomain(coverHashByBook)
            }

    // ── Mutation (RPC) ────────────────────────────────────────────────────────────

    override suspend fun createShelf(
        name: String,
        description: String?,
        isPrivate: Boolean,
    ): AppResult<Shelf> =
        channel
            .call {
                it.createShelf(
                    name = name,
                    description = description ?: "",
                    isPrivate = isPrivate,
                )
            }.also { if (it is AppResult.Success) mirrorCreatedShelf(it.data) }
            .map { it.toDomain() }

    /**
     * Offline-first: apply the new name, description, and privacy flag to Room and enqueue a durable
     * op on the `shelves` channel keyed by the shelf id. Returns the optimistic aggregate (derived
     * cover/duration/count are unaffected by this edit); [ShelfError.NotFound] when the shelf isn't
     * in Room. The shelf's own echo (deferred by the in-flight shield) is the final word.
     */
    override suspend fun updateShelf(
        shelfId: ShelfId,
        name: String,
        description: String?,
        isPrivate: Boolean,
    ): AppResult<Shelf> {
        val existing = dao.getById(shelfId.value) ?: return AppResult.Failure(ShelfError.NotFound())
        val updated =
            existing.copy(
                name = name,
                description = description ?: "",
                isPrivate = isPrivate,
                updatedAt = currentEpochMilliseconds(),
            )
        val domain =
            updated.toDomainWithDerived(
                coverPaths = dao.coverHashesFor(shelfId.value),
                totalDurationMs = dao.totalDurationMsFor(shelfId.value),
                bookCountOverride = dao.bookCountFor(shelfId.value),
            )
        return offlineEditor
            .edit(OutboxChannels.Shelves, shelfId.value, ShelfMutation.Update(name, description ?: "", isPrivate)) {
                dao.upsert(updated)
            }.map { domain }
    }

    /**
     * Offline-first: soft-delete the shelf and cascade-tombstone its `shelf_books` junctions (mirroring
     * the server's `deleteShelf` cascade), then enqueue a durable op on the `shelves` channel keyed by
     * the shelf id. The shelf's revision is preserved so its own echo (deferred by the in-flight shield)
     * re-applies the authoritative tombstone on drain; the junction echoes flow through their own domain.
     */
    override suspend fun deleteShelf(shelfId: ShelfId): AppResult<Unit> {
        val now = currentEpochMilliseconds()
        return offlineEditor.edit(OutboxChannels.Shelves, shelfId.value, ShelfMutation.Delete, op = OpKind.Delete) {
            dao
                .getById(
                    shelfId.value,
                )?.let { dao.softDelete(id = shelfId.value, deletedAt = now, revision = it.revision) }
            shelfBookDao.tombstoneAllForShelf(shelfId = shelfId.value, deletedAt = now)
        }
    }

    /**
     * Offline-first: enqueue ONE add op per book on the `shelf_books` channel, each keyed by its own
     * `"$shelfId:$bookId"` envelope id so each junction inherits the per-junction in-flight shield, and
     * upsert each junction optimistically (revision-0 stub, appended after the current max sort order).
     * Adds a book mints no server id (the book already exists). Fails fast on the first enqueue failure.
     */
    override suspend fun addBooksToShelf(
        shelfId: ShelfId,
        bookIds: List<BookId>,
    ): AppResult<Unit> {
        bookIds.forEach { bookId ->
            val id = "${shelfId.value}:${bookId.value}"
            val result =
                offlineEditor.edit(
                    OutboxChannels.ShelfBooks,
                    id,
                    ShelfBookMutation.Add(shelfId = shelfId.value, bookId = bookId.value),
                    op = OpKind.Create,
                ) {
                    val now = currentEpochMilliseconds()
                    val nextSort = (shelfBookDao.maxSortOrderForShelf(shelfId.value) ?: -1) + 1
                    shelfBookDao.upsert(
                        ShelfBookEntity(
                            id = id,
                            shelfId = shelfId.value,
                            bookId = bookId.value,
                            sortOrder = nextSort,
                            revision = 0,
                            deletedAt = null,
                            updatedAt = now,
                            createdAt = now,
                        ),
                    )
                }
            if (result is AppResult.Failure) return result
        }
        return AppResult.Success(Unit)
    }

    /**
     * Offline-first: tombstone the junction optimistically (preserving its revision) and enqueue a
     * durable op on the `shelf_books` channel, keyed by the same `"$shelfId:$bookId"` envelope id the
     * junction's mirror row uses so the in-flight shield and reconcile-on-drain align. Idempotent server-side.
     */
    override suspend fun removeBookFromShelf(
        shelfId: ShelfId,
        bookId: BookId,
    ): AppResult<Unit> {
        val id = "${shelfId.value}:${bookId.value}"
        return offlineEditor.edit(
            OutboxChannels.ShelfBooks,
            id,
            ShelfBookMutation.Remove(shelfId = shelfId.value, bookId = bookId.value),
            op = OpKind.Delete,
        ) {
            shelfBookDao.findById(id)?.let {
                shelfBookDao.softDelete(id = id, deletedAt = currentEpochMilliseconds(), revision = it.revision)
            }
        }
    }

    override suspend fun reorderBooks(
        shelfId: ShelfId,
        orderedBookIds: List<BookId>,
    ): AppResult<Unit> =
        // TODO(offline-first): reorderBooks deferred — needs a shelf-scoped sortOrder-reconciling op
        // (whole-shelf permutation) that doesn't fit the single-junction shield model.
        channel.call {
            it.reorderShelfBooks(shelfId, orderedBookIds)
        }

    // ── Mapping ───────────────────────────────────────────────────────────────────

    /**
     * Map a JOIN-projected shelf row to the domain model, deriving covers and duration
     * from the shelf's member books in the local mirror. Owner fields come from the current
     * user — the local mirror holds only the caller's own shelves.
     */
    private suspend fun ShelfWithBookCount.toDomain(): Shelf =
        shelf.toDomainWithDerived(
            coverPaths = dao.coverHashesFor(shelf.id),
            totalDurationMs = dao.totalDurationMsFor(shelf.id),
            bookCountOverride = bookCount,
        )

    private suspend fun com.calypsan.listenup.client.data.local.db.ShelfEntity.toDomainWithDerived(
        coverPaths: List<String>,
        totalDurationMs: Long,
        bookCountOverride: Int,
    ): Shelf {
        val currentUser = userDao.getCurrentUser()
        return Shelf(
            id = ShelfId(id),
            name = name,
            description = description.ifEmpty { null },
            isPrivate = isPrivate,
            ownerId = currentUser?.id?.value ?: "",
            ownerDisplayName = currentUser?.displayName ?: "",
            bookCount = bookCountOverride,
            totalDurationSeconds = totalDurationMs / 1000,
            createdAtMs = createdAt,
            updatedAtMs = updatedAt,
            coverPaths = coverPaths,
        )
    }

    /**
     * Optimistically mirror a just-created shelf into Room so it appears immediately, without waiting
     * for the SSE echo (offline-first / never-stranded). Insert-if-absent at revision 0: the
     * authoritative echo (revision >= 1) always wins via the domain's ServerWins/RevisionGuard, and a
     * digest reconcile self-heals if the echo landed first — so this never overwrites an echoed row.
     */
    private suspend fun mirrorCreatedShelf(shelf: com.calypsan.listenup.api.dto.shelf.Shelf) {
        if (dao.revisionOf(shelf.id.value) != null) return
        dao.upsert(
            ShelfEntity(
                id = shelf.id.value,
                name = shelf.name,
                description = shelf.description,
                isPrivate = shelf.isPrivate,
                revision = 0,
                deletedAt = null,
                updatedAt = shelf.updatedAt,
                createdAt = shelf.updatedAt,
            ),
        )
    }
}

private fun com.calypsan.listenup.api.dto.shelf.Shelf.toDomain(): Shelf =
    Shelf(
        id = id,
        name = name,
        description = description.ifEmpty { null },
        isPrivate = isPrivate,
        ownerId = "",
        ownerDisplayName = "",
        bookCount = bookCount,
        totalDurationSeconds = 0,
        createdAtMs = updatedAt,
        updatedAtMs = updatedAt,
    )

private fun DiscoveredShelf.toDomain(): Shelf =
    Shelf(
        id = shelf.id,
        name = shelf.name,
        description = shelf.description.ifEmpty { null },
        isPrivate = shelf.isPrivate,
        ownerId = ownerId,
        ownerDisplayName = ownerDisplayName,
        bookCount = shelf.bookCount,
        totalDurationSeconds = 0,
        createdAtMs = shelf.updatedAt,
        updatedAtMs = shelf.updatedAt,
    )

private fun ShelfDetailDto.toDomain(coverHashByBook: Map<String, String?>): ShelfDetail =
    ShelfDetail(
        id = id,
        name = name,
        description = description.ifEmpty { null },
        isPrivate = isPrivate,
        isOwner = isOwner,
        bookCount = bookCount,
        totalDurationSeconds = totalDurationMs / 1000,
        books =
            books.map { book ->
                ShelfBook(
                    id = BookId(book.bookId),
                    title = book.title,
                    authorNames = book.authors,
                    coverPath = null,
                    coverHash = coverHashByBook[book.bookId],
                )
            },
    )
