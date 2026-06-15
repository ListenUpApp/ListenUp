package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.shelf.DiscoveredShelf
import com.calypsan.listenup.api.dto.shelf.ShelfDetail as ShelfDetailDto
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.map
import com.calypsan.listenup.client.core.error.ErrorMapper
import com.calypsan.listenup.client.data.local.db.ShelfDao
import com.calypsan.listenup.client.data.local.db.ShelfWithBookCount
import com.calypsan.listenup.client.data.local.db.UserDao
import com.calypsan.listenup.client.data.remote.ShelfRpcFactory
import com.calypsan.listenup.client.domain.model.Shelf
import com.calypsan.listenup.client.domain.model.ShelfBook
import com.calypsan.listenup.client.domain.model.ShelfDetail
import com.calypsan.listenup.client.domain.repository.ShelfRepository
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ShelfId
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val logger = KotlinLogging.logger {}

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
class ShelfRepositoryImpl(
    private val dao: ShelfDao,
    private val userDao: UserDao,
    private val rpcFactory: ShelfRpcFactory,
) : ShelfRepository {
    // ── Own-shelf observation (Room) ──────────────────────────────────────────────

    override fun observeMyShelves(userId: String): Flow<List<Shelf>> =
        dao.observeMyShelvesWithBookCount().map { rows -> rows.map { it.toDomain() } }

    override fun observeShelvesContainingBook(bookId: String): Flow<List<Shelf>> =
        dao.observeShelvesContainingBookWithBookCount(bookId).map { rows -> rows.map { it.toDomain() } }

    override fun observeById(id: String): Flow<Shelf?> =
        dao.observeById(id).map { entity ->
            entity?.toDomainWithDerived(
                coverPaths = dao.coverHashesFor(id),
                totalDurationMs = dao.totalDurationMsFor(id),
                bookCountOverride = dao.bookCountFor(id),
            )
        }

    override suspend fun getById(id: String): Shelf? =
        dao.getById(id)?.toDomainWithDerived(
            coverPaths = dao.coverHashesFor(id),
            totalDurationMs = dao.totalDurationMsFor(id),
            bookCountOverride = dao.bookCountFor(id),
        )

    // ── Discovery (on-demand RPC) ─────────────────────────────────────────────────

    override suspend fun getUserShelves(userId: String): AppResult<List<Shelf>> =
        rpcCall { rpcFactory.get().getUserShelves(UserId(userId)) }
            .map { shelves -> shelves.map { it.toDomain() } }

    override suspend fun discoverShelves(): AppResult<List<Shelf>> =
        rpcCall { rpcFactory.get().discoverShelves() }
            .map { discovered -> discovered.map { it.toDomain() } }

    // ── Detail (on-demand RPC) ────────────────────────────────────────────────────

    override suspend fun getShelfDetail(shelfId: String): AppResult<ShelfDetail> =
        rpcCall { rpcFactory.get().getShelf(ShelfId(shelfId)) }
            .map { detail ->
                val coverHashByBook = dao.coverHashesByBookFor(shelfId).associate { it.bookId to it.coverHash }
                detail.toDomain(coverHashByBook)
            }

    // ── Mutation (RPC) ────────────────────────────────────────────────────────────

    override suspend fun createShelf(
        name: String,
        description: String?,
        isPrivate: Boolean,
    ): AppResult<Shelf> =
        rpcCall {
            rpcFactory.get().createShelf(
                name = name,
                description = description ?: "",
                isPrivate = isPrivate,
            )
        }.map { it.toDomain() }

    override suspend fun updateShelf(
        shelfId: String,
        name: String,
        description: String?,
        isPrivate: Boolean,
    ): AppResult<Shelf> =
        rpcCall {
            rpcFactory.get().updateShelf(
                shelfId = ShelfId(shelfId),
                name = name,
                description = description ?: "",
                isPrivate = isPrivate,
            )
        }.map { it.toDomain() }

    override suspend fun deleteShelf(shelfId: String): AppResult<Unit> =
        rpcCall { rpcFactory.get().deleteShelf(ShelfId(shelfId)) }

    override suspend fun addBooksToShelf(
        shelfId: String,
        bookIds: List<String>,
    ): AppResult<Unit> {
        // The RPC surface adds one book at a time (idempotent); dispatch each and
        // fail fast on the first error. SSE echoes update Room — no optimistic write.
        bookIds.forEach { bookId ->
            val result = rpcCall { rpcFactory.get().addBookToShelf(ShelfId(shelfId), BookId(bookId)) }
            if (result is AppResult.Failure) return result
        }
        return AppResult.Success(Unit)
    }

    override suspend fun removeBookFromShelf(
        shelfId: String,
        bookId: String,
    ): AppResult<Unit> = rpcCall { rpcFactory.get().removeBookFromShelf(ShelfId(shelfId), BookId(bookId)) }

    override suspend fun reorderBooks(
        shelfId: String,
        orderedBookIds: List<String>,
    ): AppResult<Unit> =
        rpcCall {
            rpcFactory.get().reorderShelfBooks(ShelfId(shelfId), orderedBookIds.map { BookId(it) })
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
            id = id,
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
     * Run an RPC call and surface the typed [AppResult] directly. Re-throws
     * [CancellationException]; any other throwable becomes [AppResult.Failure]
     * via [ErrorMapper].
     */
    private suspend fun <T> rpcCall(block: suspend () -> AppResult<T>): AppResult<T> =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.warn(e) { "Shelf RPC failed" }
            AppResult.Failure(ErrorMapper.map(e))
        }
}

private fun com.calypsan.listenup.api.dto.shelf.Shelf.toDomain(): Shelf =
    Shelf(
        id = id.value,
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
        id = shelf.id.value,
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
        id = id.value,
        name = name,
        description = description.ifEmpty { null },
        isPrivate = isPrivate,
        isOwner = isOwner,
        bookCount = bookCount,
        totalDurationSeconds = totalDurationMs / 1000,
        books =
            books.map { book ->
                ShelfBook(
                    id = book.bookId,
                    title = book.title,
                    authorNames = book.authors,
                    coverPath = null,
                    coverHash = coverHashByBook[book.bookId],
                )
            },
    )
