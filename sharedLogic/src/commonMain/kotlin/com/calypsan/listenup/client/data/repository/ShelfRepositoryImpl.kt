package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.dto.shelf.DiscoveredShelf
import com.calypsan.listenup.api.dto.shelf.ShelfDetail as ShelfDetailDto
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.AppResult as WireAppResult
import com.calypsan.listenup.api.result.map
import com.calypsan.listenup.client.core.error.ErrorMapper
import com.calypsan.listenup.client.data.local.db.ShelfDao
import com.calypsan.listenup.client.data.local.db.ShelfWithBookCount
import com.calypsan.listenup.client.data.local.db.UserDao
import com.calypsan.listenup.client.data.remote.ShelfRpcFactory
import com.calypsan.listenup.client.domain.model.Shelf
import com.calypsan.listenup.client.domain.model.ShelfBook
import com.calypsan.listenup.client.domain.model.ShelfDetail
import com.calypsan.listenup.client.domain.model.ShelfOwner
import com.calypsan.listenup.client.domain.repository.ShelfRepository
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ShelfId
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

private const val DEFAULT_AVATAR_COLOR = "#6B7280"

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
 * Discovery (`observeDiscoverShelves`, `countDiscoverShelves`, `fetchAndCacheDiscoverShelves`)
 * is **not** own-data — other users' shelves never enter the substrate. It is served on demand
 * by [com.calypsan.listenup.api.ShelfService.discoverShelves] and held in an in-memory cache so
 * the existing observe/count interface keeps working.
 *
 * Mutations call [com.calypsan.listenup.api.ShelfService] over RPC. No optimistic Room writes —
 * the SSE echo from the server is the single write path back into Room (the Collections pattern).
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
    /** In-memory cache of discovered (other-user) shelves, refreshed via the discover RPC. */
    private val discoverShelves = MutableStateFlow<List<Shelf>>(emptyList())

    // ── Own-shelf observation (Room) ──────────────────────────────────────────────

    override fun observeMyShelves(userId: String): Flow<List<Shelf>> =
        dao.observeMyShelvesWithBookCount().map { rows -> rows.map { it.toDomain() } }

    override fun observeById(id: String): Flow<Shelf?> =
        dao.observeById(id).map { entity ->
            entity?.toDomainWithDerived(
                coverPaths = dao.coverHashesFor(id),
                totalDurationMs = dao.totalDurationMsFor(id),
            )
        }

    override suspend fun getById(id: String): Shelf? =
        dao.getById(id)?.toDomainWithDerived(
            coverPaths = dao.coverHashesFor(id),
            totalDurationMs = dao.totalDurationMsFor(id),
        )

    // ── Discovery (on-demand RPC + in-memory cache) ───────────────────────────────

    override fun observeDiscoverShelves(currentUserId: String): Flow<List<Shelf>> = discoverShelves

    override suspend fun countDiscoverShelves(currentUserId: String): Int = discoverShelves.value.size

    override suspend fun fetchAndCacheDiscoverShelves(): AppResult<Int> =
        rpcCall { rpcFactory.get().discoverShelves() }
            .map { discovered ->
                val shelves = discovered.map { it.toDomain() }
                discoverShelves.value = shelves
                shelves.size
            }

    /** No-op for the substrate path: own shelves hydrate via the sync engine, not a manual pull. */
    override suspend fun fetchAndCacheMyShelves(): AppResult<Unit> = AppResult.Success(Unit)

    // ── Detail (on-demand RPC) ────────────────────────────────────────────────────

    override suspend fun getShelfDetail(shelfId: String): AppResult<ShelfDetail> =
        rpcCall { rpcFactory.get().getShelf(ShelfId(shelfId)) }.map { it.toDomain() }

    // ── Mutation (RPC) ────────────────────────────────────────────────────────────

    override suspend fun createShelf(
        name: String,
        description: String?,
    ): Shelf =
        rpcCall { rpcFactory.get().createShelf(name = name, description = description ?: "") }
            .map { it.toDomain() }
            .getOrThrow()

    override suspend fun updateShelf(
        shelfId: String,
        name: String,
        description: String?,
    ): Shelf {
        val existing = dao.getById(shelfId)
        return rpcCall {
            rpcFactory.get().updateShelf(
                shelfId = ShelfId(shelfId),
                name = name,
                description = description ?: "",
                isPrivate = existing?.isPrivate ?: false,
            )
        }.map { it.toDomain() }.getOrThrow()
    }

    override suspend fun deleteShelf(shelfId: String) {
        rpcCall { rpcFactory.get().deleteShelf(ShelfId(shelfId)) }.getOrThrow()
    }

    override suspend fun addBooksToShelf(
        shelfId: String,
        bookIds: List<String>,
    ) {
        bookIds.forEach { bookId ->
            rpcCall { rpcFactory.get().addBookToShelf(ShelfId(shelfId), BookId(bookId)) }.getOrThrow()
        }
    }

    override suspend fun removeBookFromShelf(
        shelfId: String,
        bookId: String,
    ) {
        rpcCall { rpcFactory.get().removeBookFromShelf(ShelfId(shelfId), BookId(bookId)) }.getOrThrow()
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
        bookCountOverride: Int? = null,
    ): Shelf {
        val currentUser = userDao.getCurrentUser()
        return Shelf(
            id = id,
            name = name,
            description = description.ifEmpty { null },
            ownerId = currentUser?.id?.value ?: "",
            ownerDisplayName = currentUser?.displayName ?: "",
            ownerAvatarColor = currentUser?.avatarColor ?: DEFAULT_AVATAR_COLOR,
            bookCount = bookCountOverride ?: coverPaths.size,
            totalDurationSeconds = totalDurationMs / 1000,
            createdAtMs = createdAt,
            updatedAtMs = updatedAt,
            coverPaths = coverPaths,
        )
    }

    /**
     * Run an RPC call, converting the contract-layer [WireAppResult] into the client
     * [AppResult]. Re-throws [CancellationException]; all other throwables become
     * [AppResult.Failure] via [ErrorMapper].
     */
    private suspend fun <T> rpcCall(block: suspend () -> WireAppResult<T>): AppResult<T> =
        try {
            when (val result = block()) {
                is WireAppResult.Success -> AppResult.Success(result.data)
                is WireAppResult.Failure -> AppResult.Failure(result.error)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.warn(e) { "Shelf RPC failed" }
            AppResult.Failure(ErrorMapper.map(e))
        }
}

/** Throw the typed error on failure; used to satisfy the legacy non-result mutation signatures. */
private fun <T> AppResult<T>.getOrThrow(): T =
    when (this) {
        is AppResult.Success -> data
        is AppResult.Failure -> throw ShelfOperationException(error.message)
    }

/** Internal carrier so the legacy throw-based mutation signatures can surface a typed failure's message. */
private class ShelfOperationException(
    message: String,
) : Exception(message)

private fun com.calypsan.listenup.api.dto.shelf.Shelf.toDomain(): Shelf =
    Shelf(
        id = id.value,
        name = name,
        description = description.ifEmpty { null },
        ownerId = "",
        ownerDisplayName = "",
        ownerAvatarColor = DEFAULT_AVATAR_COLOR,
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
        ownerId = ownerId,
        ownerDisplayName = ownerDisplayName,
        ownerAvatarColor = DEFAULT_AVATAR_COLOR,
        bookCount = shelf.bookCount,
        totalDurationSeconds = 0,
        createdAtMs = shelf.updatedAt,
        updatedAtMs = shelf.updatedAt,
    )

private fun ShelfDetailDto.toDomain(): ShelfDetail =
    ShelfDetail(
        id = id.value,
        name = name,
        description = description.ifEmpty { null },
        owner =
            ShelfOwner(
                id = "",
                displayName = "",
                avatarColor = DEFAULT_AVATAR_COLOR,
            ),
        bookCount = bookCount,
        totalDurationSeconds = totalDurationMs / 1000,
        books =
            books.map { book ->
                ShelfBook(
                    id = book.bookId,
                    title = book.title,
                    authorNames = book.authors,
                    coverPath = null,
                    durationSeconds = 0,
                )
            },
    )
