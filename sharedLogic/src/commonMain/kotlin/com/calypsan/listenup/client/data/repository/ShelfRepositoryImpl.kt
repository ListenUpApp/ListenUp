package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.shelf.DiscoveredShelf
import com.calypsan.listenup.api.dto.shelf.ShelfDetail as ShelfDetailDto
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.map
import com.calypsan.listenup.client.data.local.db.ShelfDao
import com.calypsan.listenup.client.data.local.db.ShelfEntity
import com.calypsan.listenup.client.data.local.db.ShelfWithBookCount
import com.calypsan.listenup.client.data.local.db.UserDao
import com.calypsan.listenup.client.data.remote.ShelfRpcFactory
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
 * Mutations call [com.calypsan.listenup.api.ShelfService] over RPC and return the typed
 * [AppResult] directly. No optimistic Room writes — the SSE echo from the server is the single
 * write path back into Room (the Collections pattern).
 *
 * @property dao Substrate shelf DAO (own-shelf reads + derived cover/duration queries).
 * @property userDao Current-user lookup for owner fields on own shelves.
 * @property rpcFactory Supplies the [com.calypsan.listenup.api.ShelfService] RPC proxy.
 */
internal class ShelfRepositoryImpl(
    private val dao: ShelfDao,
    private val userDao: UserDao,
    private val rpcFactory: ShelfRpcFactory,
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
        rpcFactory
            .callResult { it.getUserShelves(UserId(userId)) }
            .map { shelves -> shelves.map { it.toDomain() } }

    override suspend fun discoverShelves(): AppResult<List<Shelf>> =
        rpcFactory
            .callResult { it.discoverShelves() }
            .map { discovered -> discovered.map { it.toDomain() } }

    // ── Detail (on-demand RPC) ────────────────────────────────────────────────────

    override suspend fun getShelfDetail(shelfId: ShelfId): AppResult<ShelfDetail> =
        rpcFactory
            .callResult { it.getShelf(shelfId) }
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
        rpcFactory
            .callResult {
                it.createShelf(
                    name = name,
                    description = description ?: "",
                    isPrivate = isPrivate,
                )
            }.also { if (it is AppResult.Success) mirrorCreatedShelf(it.data) }
            .map { it.toDomain() }

    override suspend fun updateShelf(
        shelfId: ShelfId,
        name: String,
        description: String?,
        isPrivate: Boolean,
    ): AppResult<Shelf> =
        rpcFactory
            .callResult {
                it.updateShelf(
                    shelfId = shelfId,
                    name = name,
                    description = description ?: "",
                    isPrivate = isPrivate,
                )
            }.map { it.toDomain() }

    override suspend fun deleteShelf(shelfId: ShelfId): AppResult<Unit> =
        rpcFactory.callResult { it.deleteShelf(shelfId) }

    override suspend fun addBooksToShelf(
        shelfId: ShelfId,
        bookIds: List<BookId>,
    ): AppResult<Unit> {
        // The RPC surface adds one book at a time (idempotent); dispatch each and
        // fail fast on the first error. SSE echoes update Room — no optimistic write.
        bookIds.forEach { bookId ->
            val result = rpcFactory.callResult { it.addBookToShelf(shelfId, bookId) }
            if (result is AppResult.Failure) return result
        }
        return AppResult.Success(Unit)
    }

    override suspend fun removeBookFromShelf(
        shelfId: ShelfId,
        bookId: BookId,
    ): AppResult<Unit> = rpcFactory.callResult { it.removeBookFromShelf(shelfId, bookId) }

    override suspend fun reorderBooks(
        shelfId: ShelfId,
        orderedBookIds: List<BookId>,
    ): AppResult<Unit> =
        rpcFactory.callResult {
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
