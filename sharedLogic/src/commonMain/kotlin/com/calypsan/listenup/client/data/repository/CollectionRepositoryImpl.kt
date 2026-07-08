package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.dto.CollectionShareDto
import com.calypsan.listenup.api.dto.CollectionSummary
import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult as WireAppResult
import com.calypsan.listenup.client.data.local.db.CollectionBookDao
import com.calypsan.listenup.client.data.local.db.CollectionEntity
import com.calypsan.listenup.client.data.local.db.CollectionShareDao
import com.calypsan.listenup.client.data.local.db.CollectionShareEntity
import com.calypsan.listenup.client.data.local.db.CollectionWithBookCount
import com.calypsan.listenup.client.data.local.db.CollectionDao
import com.calypsan.listenup.client.data.remote.CollectionRpcFactory
import com.calypsan.listenup.client.domain.model.Collection
import com.calypsan.listenup.client.domain.model.CollectionShare
import com.calypsan.listenup.client.domain.repository.CollectionRepository
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.CollectionId
import com.calypsan.listenup.core.currentEpochMilliseconds
import com.calypsan.listenup.client.core.error.ErrorMapper
import com.calypsan.listenup.api.result.map
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeout

private val logger = KotlinLogging.logger {}

/** Upper bound on a single collection RPC. Guards against a black-holed WebSocket that never resolves. */
private const val RPC_TIMEOUT_MS = 15_000L

/**
 * Collection repository — Room-backed reads, RPC-dispatched mutations.
 *
 * Reads (`observeCollections`, `observeCollectionBooks`, `observeShares`) come from
 * the local Room mirror, which the sync engine populates via the substrate's SSE
 * stream and the collection sync handlers. `bookCount` on the returned [Collection]
 * is computed at read time via JOIN on `collection_books` — there is no denormalized
 * column.
 *
 * Mutations call [com.calypsan.listenup.api.CollectionService] over RPC. No optimistic
 * Room writes — the SSE echo from the server is the single write path back into Room
 * (the Tags/Genres pattern).
 */
internal class CollectionRepositoryImpl(
    private val collectionDao: CollectionDao,
    private val collectionBookDao: CollectionBookDao,
    private val collectionShareDao: CollectionShareDao,
    private val rpcFactory: CollectionRpcFactory,
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
        rpcCall { rpcFactory.get().createCollection(libraryId, name) }
            .also { if (it is AppResult.Success) mirrorCreatedCollection(libraryId, it.data) }
            .map { it.toDomain() }

    override suspend fun rename(
        id: String,
        name: String,
    ): AppResult<Collection> =
        rpcCall { rpcFactory.get().renameCollection(CollectionId(id), name) }.map { it.toDomain() }

    override suspend fun delete(id: String): AppResult<Unit> =
        rpcCall {
            rpcFactory.get().deleteCollection(CollectionId(id))
        }

    override suspend fun addBook(
        collectionId: String,
        bookId: String,
    ): AppResult<Unit> = rpcCall { rpcFactory.get().addBookToCollection(CollectionId(collectionId), BookId(bookId)) }

    override suspend fun removeBook(
        collectionId: String,
        bookId: String,
    ): AppResult<Unit> =
        rpcCall { rpcFactory.get().removeBookFromCollection(CollectionId(collectionId), BookId(bookId)) }

    override suspend fun share(
        collectionId: String,
        sharedWithUserId: String,
        permission: SharePermission,
    ): AppResult<CollectionShare> =
        rpcCall {
            rpcFactory.get().shareCollection(CollectionId(collectionId), sharedWithUserId, permission)
        }.map { it.toDomain() }

    override suspend fun updateShare(
        collectionId: String,
        sharedWithUserId: String,
        permission: SharePermission,
    ): AppResult<CollectionShare> =
        rpcCall {
            rpcFactory.get().updateShare(CollectionId(collectionId), sharedWithUserId, permission)
        }.map { it.toDomain() }

    override suspend fun revokeShare(
        collectionId: String,
        sharedWithUserId: String,
    ): AppResult<Unit> = rpcCall { rpcFactory.get().revokeShare(CollectionId(collectionId), sharedWithUserId) }

    // ── Plumbing ────────────────────────────────────────────────────────────────

    /**
     * Run an RPC call, converting the contract-layer [WireAppResult] into the
     * client [AppResult]. Re-throws [CancellationException]; all other throwables
     * become [AppResult.Failure] via [ErrorMapper].
     */
    private suspend fun <T> rpcCall(block: suspend () -> WireAppResult<T>): AppResult<T> =
        try {
            when (val result = withTimeout(RPC_TIMEOUT_MS) { block() }) {
                is WireAppResult.Success -> AppResult.Success(result.data)
                is WireAppResult.Failure -> AppResult.Failure(result.error)
            }
        } catch (e: TimeoutCancellationException) {
            logger.warn(e) { "Collection RPC timed out after ${RPC_TIMEOUT_MS}ms" }
            AppResult.Failure(TransportError.Timeout())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.warn(e) { "Collection RPC failed" }
            AppResult.Failure(ErrorMapper.map(e))
        }

    /**
     * Optimistically mirror a just-created collection into Room so it appears immediately, without
     * waiting for the SSE echo (offline-first / never-stranded). Insert-if-absent at revision 0: the
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
