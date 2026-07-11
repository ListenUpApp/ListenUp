package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.readingorder.DiscoveredReadingOrder
import com.calypsan.listenup.api.dto.readingorder.ReadingOrderBookWrite
import com.calypsan.listenup.api.dto.readingorder.ReadingOrderDetail as ReadingOrderDetailDto
import com.calypsan.listenup.api.dto.readingorder.ReadingOrderUpdate
import com.calypsan.listenup.api.dto.readingorder.SetActiveReadingOrderRequest
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.map
import com.calypsan.listenup.client.core.error.ErrorMapper
import com.calypsan.listenup.client.data.local.db.ReadingOrderBookDao
import com.calypsan.listenup.client.data.local.db.ReadingOrderBookEntity
import com.calypsan.listenup.client.data.local.db.ReadingOrderDao
import com.calypsan.listenup.client.data.local.db.ReadingOrderEntity
import com.calypsan.listenup.client.data.local.db.ReadingOrderFollowDao
import com.calypsan.listenup.client.data.local.db.ReadingOrderFollowEntity
import com.calypsan.listenup.client.data.local.db.ReadingOrderWithBookCount
import com.calypsan.listenup.client.data.local.db.UserDao
import com.calypsan.listenup.client.data.remote.ReadingOrderRpcFactory
import com.calypsan.listenup.client.data.sync.OfflineEditor
import com.calypsan.listenup.client.data.sync.domains.OpKind
import com.calypsan.listenup.client.data.sync.domains.OutboxChannels
import com.calypsan.listenup.client.domain.model.ReadingOrder
import com.calypsan.listenup.client.domain.model.ReadingOrderBook
import com.calypsan.listenup.client.domain.model.ReadingOrderDetail
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.ReadingOrderRepository
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ReadingOrderId
import com.calypsan.listenup.core.currentEpochMilliseconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Reading-order repository — substrate-Room-backed reads, **offline-first**
 * mutations per Integration Foundations §5.3.
 *
 * Own-order reads (`observeMyReadingOrders`, `observeById`, `getById`,
 * `observeActiveReadingOrder`) come from the local Room mirror, which the sync
 * engine populates via the substrate SSE stream and the reading-order sync
 * domains. `bookCount` is JOIN-derived; `coverPaths` and `totalDurationSeconds`
 * are derived from the order's member books present in the local `books` mirror.
 *
 * Discovery ([discoverReadingOrders]) is **not** own-data — other users' orders
 * never enter the substrate. It is served on demand over RPC.
 *
 * Mutations diverge from the Shelf template per the §5.3 amendment: metadata
 * updates, membership add/remove/reorder, and the follow-state setter write Room
 * optimistically and enqueue a durable outbox op via [OfflineEditor] — an edit
 * made offline persists and replays on reconnect; the authoritative state arrives
 * via the SSE echo and reconciles through the domain descriptors. Create stays
 * direct RPC (the server mints the id) with an optimistic insert-if-absent mirror
 * on success; delete stays direct RPC by standing product decision.
 */
@Suppress("TooManyFunctions")
internal class ReadingOrderRepositoryImpl(
    private val dao: ReadingOrderDao,
    private val bookDao: ReadingOrderBookDao,
    private val followDao: ReadingOrderFollowDao,
    private val userDao: UserDao,
    private val rpcFactory: ReadingOrderRpcFactory,
    private val offlineEditor: OfflineEditor,
    private val authSession: AuthSession,
) : ReadingOrderRepository {
    // ── Own-order observation (Room) ──────────────────────────────────────────────

    override fun observeMyReadingOrders(): Flow<List<ReadingOrder>> =
        dao.observeMyReadingOrdersWithBookCount().map { rows -> rows.map { it.toDomain() } }

    override fun observeById(id: ReadingOrderId): Flow<ReadingOrder?> =
        dao.observeById(id.value).map { entity ->
            entity?.toDomainWithDerived(
                coverPaths = dao.coverHashesFor(id.value),
                totalDurationMs = dao.totalDurationMsFor(id.value),
                bookCountOverride = dao.bookCountFor(id.value),
            )
        }

    override suspend fun getById(id: ReadingOrderId): ReadingOrder? =
        dao.getById(id.value)?.toDomainWithDerived(
            coverPaths = dao.coverHashesFor(id.value),
            totalDurationMs = dao.totalDurationMsFor(id.value),
            bookCountOverride = dao.bookCountFor(id.value),
        )

    // ── Discovery + detail (on-demand RPC) ────────────────────────────────────────

    override suspend fun getUserReadingOrders(userId: String): AppResult<List<ReadingOrder>> =
        rpcFactory
            .callResult { it.getUserReadingOrders(UserId(userId)) }
            .map { orders -> orders.map { it.toDomain() } }

    override suspend fun discoverReadingOrders(): AppResult<List<ReadingOrder>> =
        rpcFactory
            .callResult { it.discoverReadingOrders() }
            .map { discovered -> discovered.map { it.toDomain() } }

    override suspend fun getReadingOrderDetail(id: ReadingOrderId): AppResult<ReadingOrderDetail> =
        rpcFactory
            .callResult { it.getReadingOrder(id) }
            .map { it.toDomain() }

    // ── Lifecycle (direct RPC) ────────────────────────────────────────────────────

    override suspend fun createReadingOrder(
        name: String,
        description: String?,
        attribution: String?,
        isPrivate: Boolean,
    ): AppResult<ReadingOrder> =
        rpcFactory
            .callResult {
                it.createReadingOrder(
                    name = name,
                    description = description ?: "",
                    attribution = attribution ?: "",
                    isPrivate = isPrivate,
                )
            }.also { if (it is AppResult.Success) mirrorCreatedOrder(it.data) }
            .map { it.toDomain() }

    override suspend fun deleteReadingOrder(id: ReadingOrderId): AppResult<Unit> =
        rpcFactory.callResult { it.deleteReadingOrder(id) }

    // ── Mutation (offline-first via the outbox) ───────────────────────────────────

    override suspend fun updateReadingOrder(
        id: ReadingOrderId,
        name: String,
        description: String?,
        attribution: String?,
        isPrivate: Boolean,
    ): AppResult<Unit> {
        val patch =
            ReadingOrderUpdate(
                name = name,
                description = description ?: "",
                attribution = attribution ?: "",
                isPrivate = isPrivate,
            )
        return offlineEditor.edit(OutboxChannels.ReadingOrders, id.value, patch) {
            dao.getById(id.value)?.let { existing ->
                dao.upsert(
                    existing.copy(
                        name = patch.name,
                        description = patch.description,
                        attribution = patch.attribution,
                        isPrivate = patch.isPrivate,
                        // revision + updatedAt deliberately untouched — the echo advances them.
                    ),
                )
            }
        }
    }

    override suspend fun addBooksToReadingOrder(
        id: ReadingOrderId,
        bookIds: List<BookId>,
    ): AppResult<Unit> {
        // One optimistic row + one durable op per book (idempotent server add);
        // fail fast on the first enqueue error.
        bookIds.forEach { bookId ->
            val syntheticId = "${id.value}:${bookId.value}"
            val write = ReadingOrderBookWrite.Add(readingOrderId = id.value, bookId = bookId.value)
            val result =
                offlineEditor.edit(OutboxChannels.ReadingOrderBooks, syntheticId, write, op = OpKind.Create) {
                    val existing = bookDao.findById(syntheticId)
                    if (existing == null || existing.deletedAt != null) {
                        val now = currentEpochMilliseconds()
                        val nextSortOrder = (bookDao.maxSortOrder(id.value) ?: -1) + 1
                        bookDao.upsert(
                            ReadingOrderBookEntity(
                                id = syntheticId,
                                readingOrderId = id.value,
                                bookId = bookId.value,
                                sortOrder = nextSortOrder,
                                revision = existing?.revision ?: 0,
                                deletedAt = null,
                                updatedAt = now,
                                createdAt = existing?.createdAt ?: now,
                            ),
                        )
                    }
                }
            if (result is AppResult.Failure) return result
        }
        return AppResult.Success(Unit)
    }

    override suspend fun removeBookFromReadingOrder(
        id: ReadingOrderId,
        bookId: BookId,
    ): AppResult<Unit> {
        val syntheticId = "${id.value}:${bookId.value}"
        val write = ReadingOrderBookWrite.Remove(readingOrderId = id.value, bookId = bookId.value)
        return offlineEditor.edit(OutboxChannels.ReadingOrderBooks, syntheticId, write, op = OpKind.Delete) {
            bookDao.findById(syntheticId)?.let { existing ->
                // Local tombstone at the current revision — the echo advances it.
                bookDao.softDelete(id = syntheticId, deletedAt = currentEpochMilliseconds(), revision = existing.revision)
            }
        }
    }

    override suspend fun reorderBooks(
        id: ReadingOrderId,
        orderedBookIds: List<BookId>,
    ): AppResult<Unit> {
        val write =
            ReadingOrderBookWrite.Reorder(
                readingOrderId = id.value,
                orderedBookIds = orderedBookIds.map { it.value },
            )
        return offlineEditor.edit(OutboxChannels.ReadingOrderBooks, id.value, write, op = OpKind.Update) {
            orderedBookIds.forEachIndexed { index, bookId ->
                bookDao.updateSortOrder(id = "${id.value}:${bookId.value}", sortOrder = index)
            }
        }
    }

    // ── Follow-state (§5.4) ───────────────────────────────────────────────────────

    override fun observeActiveReadingOrder(seriesId: String): Flow<ReadingOrderId?> =
        followDao.observeActiveReadingOrderId(seriesId).map { id -> id?.let(::ReadingOrderId) }

    override suspend fun setActiveReadingOrder(
        seriesId: String,
        readingOrderId: ReadingOrderId?,
    ): AppResult<Unit> {
        val userId =
            authSession.getUserId()
                ?: return AppResult.Failure(ErrorMapper.map(IllegalStateException("No signed-in user")))
        val request = SetActiveReadingOrderRequest(seriesId = seriesId, activeReadingOrderId = readingOrderId?.value)
        return offlineEditor.edit(OutboxChannels.ReadingOrderFollows, seriesId, request, op = OpKind.Upsert) {
            val syntheticId = "$userId:$seriesId"
            val existing = followDao.findById(syntheticId)
            val now = currentEpochMilliseconds()
            followDao.upsert(
                ReadingOrderFollowEntity(
                    id = syntheticId,
                    seriesId = seriesId,
                    activeReadingOrderId = readingOrderId?.value,
                    revision = existing?.revision ?: 0,
                    deletedAt = null,
                    updatedAt = now,
                    createdAt = existing?.createdAt ?: now,
                ),
            )
        }
    }

    // ── Mapping ───────────────────────────────────────────────────────────────────

    /**
     * Map a JOIN-projected reading-order row to the domain model, deriving covers
     * and duration from the order's member books in the local mirror. Owner fields
     * come from the current user — the local mirror holds only the caller's own orders.
     */
    private suspend fun ReadingOrderWithBookCount.toDomain(): ReadingOrder =
        readingOrder.toDomainWithDerived(
            coverPaths = dao.coverHashesFor(readingOrder.id),
            totalDurationMs = dao.totalDurationMsFor(readingOrder.id),
            bookCountOverride = bookCount,
        )

    private suspend fun ReadingOrderEntity.toDomainWithDerived(
        coverPaths: List<String>,
        totalDurationMs: Long,
        bookCountOverride: Int,
    ): ReadingOrder {
        val currentUser = userDao.getCurrentUser()
        return ReadingOrder(
            id = ReadingOrderId(id),
            name = name,
            description = description.ifEmpty { null },
            attribution = attribution,
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
     * Optimistically mirror a just-created reading order into Room so it appears
     * immediately, without waiting for the SSE echo (never-stranded). Insert-if-absent
     * at revision 0: the authoritative echo (revision >= 1) always wins via the
     * domain's ServerWins/RevisionGuard, and a digest reconcile self-heals if the echo
     * landed first — so this never overwrites an echoed row.
     */
    private suspend fun mirrorCreatedOrder(order: com.calypsan.listenup.api.dto.readingorder.ReadingOrder) {
        if (dao.revisionOf(order.id.value) != null) return
        dao.upsert(
            ReadingOrderEntity(
                id = order.id.value,
                name = order.name,
                description = order.description,
                attribution = order.attribution,
                isPrivate = order.isPrivate,
                revision = 0,
                deletedAt = null,
                updatedAt = order.updatedAt,
                createdAt = order.updatedAt,
            ),
        )
    }
}

private fun com.calypsan.listenup.api.dto.readingorder.ReadingOrder.toDomain(): ReadingOrder =
    ReadingOrder(
        id = id,
        name = name,
        description = description.ifEmpty { null },
        attribution = attribution,
        isPrivate = isPrivate,
        ownerId = "",
        ownerDisplayName = "",
        bookCount = bookCount,
        totalDurationSeconds = 0,
        createdAtMs = updatedAt,
        updatedAtMs = updatedAt,
    )

private fun DiscoveredReadingOrder.toDomain(): ReadingOrder =
    ReadingOrder(
        id = readingOrder.id,
        name = readingOrder.name,
        description = readingOrder.description.ifEmpty { null },
        attribution = readingOrder.attribution,
        isPrivate = readingOrder.isPrivate,
        ownerId = ownerId,
        ownerDisplayName = ownerDisplayName,
        bookCount = readingOrder.bookCount,
        totalDurationSeconds = 0,
        createdAtMs = readingOrder.updatedAt,
        updatedAtMs = readingOrder.updatedAt,
    )

private fun ReadingOrderDetailDto.toDomain(): ReadingOrderDetail =
    ReadingOrderDetail(
        id = id,
        name = name,
        description = description.ifEmpty { null },
        attribution = attribution,
        isPrivate = isPrivate,
        isOwner = isOwner,
        bookCount = bookCount,
        totalDurationSeconds = totalDurationMs / 1000,
        books =
            books.map { book ->
                ReadingOrderBook(
                    id = BookId(book.bookId),
                    title = book.title,
                    authorNames = book.authors,
                )
            },
    )
