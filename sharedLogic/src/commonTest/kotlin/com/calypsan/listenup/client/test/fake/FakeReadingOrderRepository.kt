package com.calypsan.listenup.client.test.fake

import com.calypsan.listenup.api.error.ReadingOrderError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.ReadingOrder
import com.calypsan.listenup.client.domain.model.ReadingOrderDetail
import com.calypsan.listenup.client.domain.repository.ReadingOrderRepository
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ReadingOrderId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory fake of [ReadingOrderRepository]. Backed by [MutableStateFlow]s so
 * reactive `observe*` methods re-emit on every `set*` call — mirroring the
 * Room-backed implementation's read-after-write semantics.
 *
 * Mutations ([createReadingOrder], [deleteReadingOrder], [reorderBooks],
 * [addBooksToReadingOrder], [removeBookFromReadingOrder], [setActiveReadingOrder])
 * record their invocations in a `*Calls` list and default to [AppResult.Success],
 * overridable per-test via the matching `*Result` var for exercising ViewModel
 * failure branches.
 */
class FakeReadingOrderRepository(
    initialOrders: List<ReadingOrder> = emptyList(),
) : ReadingOrderRepository {
    private val myOrders = MutableStateFlow(initialOrders)
    private val discoverableOrders = MutableStateFlow<List<ReadingOrder>>(emptyList())
    private val activeOrderBySeries = MutableStateFlow<Map<String, ReadingOrderId?>>(emptyMap())
    private val bookIdsByOrder = MutableStateFlow<Map<String, List<BookId>>>(emptyMap())
    private val detailByOrder = MutableStateFlow<Map<String, ReadingOrderDetail>>(emptyMap())

    /** Test-injectable override for the next [createReadingOrder] result; null (the default) mints an order. */
    var createReadingOrderResult: AppResult<ReadingOrder>? = null

    /** Test-injectable override for the next [deleteReadingOrder] result; null (the default) succeeds. */
    var deleteReadingOrderResult: AppResult<Unit>? = null

    /** Test-injectable override for the next [reorderBooks] result; null (the default) succeeds. */
    var reorderBooksResult: AppResult<Unit>? = null

    /** Test-injectable override for the next [addBooksToReadingOrder] result; null (the default) succeeds. */
    var addBooksToReadingOrderResult: AppResult<Unit>? = null

    /** Test-injectable override for the next [removeBookFromReadingOrder] result; null (the default) succeeds. */
    var removeBookFromReadingOrderResult: AppResult<Unit>? = null

    /** Test-injectable override for the next [setActiveReadingOrder] result; null (the default) succeeds. */
    var setActiveReadingOrderResult: AppResult<Unit>? = null

    /** Test-injectable override for the next [getReadingOrderDetail] result; null (default) reads the seeded detail. */
    var getReadingOrderDetailResult: AppResult<ReadingOrderDetail>? = null

    /** Test-injectable override for the next [discoverReadingOrders] result; null (default) reads the seeded list. */
    var discoverReadingOrdersResult: AppResult<List<ReadingOrder>>? = null

    /** Test-injectable override for the next [getUserReadingOrders] result; null (default) reads the seeded list. */
    var getUserReadingOrdersResult: AppResult<List<ReadingOrder>>? = null

    /** Recorded [createReadingOrder] invocations, in call order. */
    val createReadingOrderCalls: MutableList<CreateReadingOrderCall> = mutableListOf()

    /** Recorded [deleteReadingOrder] invocations, in call order. */
    val deleteReadingOrderCalls: MutableList<ReadingOrderId> = mutableListOf()

    /** Recorded [reorderBooks] invocations, in call order. */
    val reorderBooksCalls: MutableList<ReorderBooksCall> = mutableListOf()

    /** Recorded [addBooksToReadingOrder] invocations, in call order. */
    val addBooksToReadingOrderCalls: MutableList<AddBooksCall> = mutableListOf()

    /** Recorded [removeBookFromReadingOrder] invocations, in call order. */
    val removeBookFromReadingOrderCalls: MutableList<RemoveBookCall> = mutableListOf()

    /** Recorded [setActiveReadingOrder] invocations, in call order. */
    val setActiveReadingOrderCalls: MutableList<SetActiveCall> = mutableListOf()

    /** One recorded [createReadingOrder] call's arguments. */
    data class CreateReadingOrderCall(
        val name: String,
        val description: String?,
        val attribution: String?,
        val isPrivate: Boolean,
    )

    /** One recorded [reorderBooks] call's arguments. */
    data class ReorderBooksCall(
        val id: ReadingOrderId,
        val orderedBookIds: List<BookId>,
    )

    /** One recorded [addBooksToReadingOrder] call's arguments. */
    data class AddBooksCall(
        val id: ReadingOrderId,
        val bookIds: List<BookId>,
    )

    /** One recorded [removeBookFromReadingOrder] call's arguments. */
    data class RemoveBookCall(
        val id: ReadingOrderId,
        val bookId: BookId,
    )

    /** One recorded [setActiveReadingOrder] call's arguments. */
    data class SetActiveCall(
        val seriesId: String,
        val readingOrderId: ReadingOrderId?,
    )

    override fun observeMyReadingOrders(): Flow<List<ReadingOrder>> = myOrders.asStateFlow()

    override fun observeById(id: ReadingOrderId): Flow<ReadingOrder?> =
        myOrders.asStateFlow().map { list -> list.find { it.id == id } }

    override suspend fun getById(id: ReadingOrderId): ReadingOrder? = myOrders.value.find { it.id == id }

    override fun observeReadingOrderBookIds(id: ReadingOrderId): Flow<List<BookId>> =
        bookIdsByOrder.asStateFlow().map { it[id.value].orEmpty() }

    override suspend fun getUserReadingOrders(userId: String): AppResult<List<ReadingOrder>> =
        getUserReadingOrdersResult ?: AppResult.Success(discoverableOrders.value.filter { it.ownerId == userId })

    override suspend fun discoverReadingOrders(): AppResult<List<ReadingOrder>> =
        discoverReadingOrdersResult ?: AppResult.Success(discoverableOrders.value)

    override suspend fun getReadingOrderDetail(id: ReadingOrderId): AppResult<ReadingOrderDetail> =
        getReadingOrderDetailResult
            ?: detailByOrder.value[id.value]?.let { AppResult.Success(it) }
            ?: AppResult.Failure(ReadingOrderError.NotFound())

    override suspend fun createReadingOrder(
        name: String,
        description: String?,
        attribution: String?,
        isPrivate: Boolean,
    ): AppResult<ReadingOrder> {
        createReadingOrderCalls += CreateReadingOrderCall(name, description, attribution, isPrivate)
        createReadingOrderResult?.let { return it }
        val created =
            ReadingOrder(
                id = ReadingOrderId("fake-order-${myOrders.value.size}"),
                name = name,
                description = description,
                attribution = attribution ?: "",
                isPrivate = isPrivate,
                ownerId = "",
                ownerDisplayName = "",
                bookCount = 0,
                totalDurationSeconds = 0,
                createdAtMs = 0,
                updatedAtMs = 0,
            )
        myOrders.value = myOrders.value + created
        return AppResult.Success(created)
    }

    override suspend fun updateReadingOrder(
        id: ReadingOrderId,
        name: String,
        description: String?,
        attribution: String?,
        isPrivate: Boolean,
    ): AppResult<Unit> {
        myOrders.value =
            myOrders.value.map { order ->
                if (order.id == id) {
                    order.copy(
                        name = name,
                        description = description,
                        attribution = attribution ?: "",
                        isPrivate = isPrivate,
                    )
                } else {
                    order
                }
            }
        return AppResult.Success(Unit)
    }

    override suspend fun deleteReadingOrder(id: ReadingOrderId): AppResult<Unit> {
        deleteReadingOrderCalls += id
        deleteReadingOrderResult?.let { return it }
        myOrders.value = myOrders.value.filterNot { it.id == id }
        return AppResult.Success(Unit)
    }

    override suspend fun addBooksToReadingOrder(
        id: ReadingOrderId,
        bookIds: List<BookId>,
    ): AppResult<Unit> {
        addBooksToReadingOrderCalls += AddBooksCall(id, bookIds)
        addBooksToReadingOrderResult?.let { return it }
        val existing = bookIdsByOrder.value[id.value].orEmpty()
        bookIdsByOrder.value = bookIdsByOrder.value + (id.value to (existing + bookIds))
        return AppResult.Success(Unit)
    }

    override suspend fun removeBookFromReadingOrder(
        id: ReadingOrderId,
        bookId: BookId,
    ): AppResult<Unit> {
        removeBookFromReadingOrderCalls += RemoveBookCall(id, bookId)
        removeBookFromReadingOrderResult?.let { return it }
        val existing = bookIdsByOrder.value[id.value].orEmpty()
        bookIdsByOrder.value = bookIdsByOrder.value + (id.value to existing.filterNot { it == bookId })
        return AppResult.Success(Unit)
    }

    override suspend fun reorderBooks(
        id: ReadingOrderId,
        orderedBookIds: List<BookId>,
    ): AppResult<Unit> {
        reorderBooksCalls += ReorderBooksCall(id, orderedBookIds)
        reorderBooksResult?.let { return it }
        bookIdsByOrder.value = bookIdsByOrder.value + (id.value to orderedBookIds)
        return AppResult.Success(Unit)
    }

    override fun observeActiveReadingOrder(seriesId: String): Flow<ReadingOrderId?> =
        activeOrderBySeries.asStateFlow().map { it[seriesId] }

    override suspend fun setActiveReadingOrder(
        seriesId: String,
        readingOrderId: ReadingOrderId?,
    ): AppResult<Unit> {
        setActiveReadingOrderCalls += SetActiveCall(seriesId, readingOrderId)
        setActiveReadingOrderResult?.let { return it }
        activeOrderBySeries.value = activeOrderBySeries.value + (seriesId to readingOrderId)
        return AppResult.Success(Unit)
    }

    /** Test helper: replace the caller's own reading orders, emitting to all observers. */
    fun setMyOrders(orders: List<ReadingOrder>) {
        myOrders.value = orders
    }

    /** Test helper: replace the discoverable orders backing [discoverReadingOrders]/[getUserReadingOrders]. */
    fun setDiscoverableOrders(orders: List<ReadingOrder>) {
        discoverableOrders.value = orders
    }

    /** Test helper: seed [id]'s ordered member book ids, emitting to [observeReadingOrderBookIds] observers. */
    fun setBookIds(
        id: ReadingOrderId,
        bookIds: List<BookId>,
    ) {
        bookIdsByOrder.value = bookIdsByOrder.value + (id.value to bookIds)
    }

    /** Test helper: seed [id]'s full detail, returned by [getReadingOrderDetail] absent an override. */
    fun setDetail(
        id: ReadingOrderId,
        detail: ReadingOrderDetail,
    ) {
        detailByOrder.value = detailByOrder.value + (id.value to detail)
    }

    /** Test helper: directly seed the active reading order for [seriesId], emitting to [observeActiveReadingOrder]. */
    fun setActiveOrder(
        seriesId: String,
        readingOrderId: ReadingOrderId?,
    ) {
        activeOrderBySeries.value = activeOrderBySeries.value + (seriesId to readingOrderId)
    }
}
