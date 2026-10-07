package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.ReadingOrderService
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.readingorder.ReadingOrderChoice
import com.calypsan.listenup.api.error.ReadingOrderError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.map
import com.calypsan.listenup.api.sync.ReadingOrderFollowSyncPayload
import com.calypsan.listenup.api.sync.ReadingOrderSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ReadingOrderId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.domain.readingorder.ReadingOrderName
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPermissionPolicy
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.sync.FirehoseSuppressed
import com.calypsan.listenup.server.sync.ReadingOrderBookRepository
import com.calypsan.listenup.server.sync.ReadingOrderFollowRepository
import com.calypsan.listenup.server.sync.ReadingOrderRepository
import kotlin.time.Clock
import kotlinx.coroutines.currentCoroutineContext

/**
 * [ReadingOrderService] (#962). Every mutation is idempotent on replay, because clients write through the
 * outbox. The caller is always the bound [principal] (the route's [copyWith]), never a request field.
 *
 * Who may do what:
 * - make an order: [UserPermissionPolicy.requireCanMakeReadingOrders] (ROOT/ADMIN implicit);
 * - change one: ROOT/ADMIN, or its maker while the maker still holds the permission;
 * - follow one: anyone.
 *
 * An order whose series is not live (deleted, merged away or purged) is dormant: every write to it is
 * [ReadingOrderError.NotFound], and it applies again if its series comes back. No hook runs when a series
 * dies; a merge moves the source's orders instead (see `SeriesMergeReceipts`).
 */
internal class ReadingOrderServiceImpl(
    private val orders: ReadingOrderRepository,
    private val members: ReadingOrderBookRepository,
    private val follows: ReadingOrderFollowRepository,
    private val seriesRepo: SeriesRepository,
    private val sqlDb: ListenUpDatabase,
    private val accessPolicy: BookAccessPolicy,
    private val permissionPolicy: UserPermissionPolicy,
    private val principal: PrincipalProvider,
    private val clock: Clock = Clock.System,
) : ReadingOrderService {
    private val membership = ReadingOrderMembership(members, seriesRepo, sqlDb, accessPolicy)

    /** A copy bound to the authenticated caller — called by the RPC route per request. */
    fun copyWith(principal: PrincipalProvider): ReadingOrderServiceImpl =
        ReadingOrderServiceImpl(
            orders,
            members,
            follows,
            seriesRepo,
            sqlDb,
            accessPolicy,
            permissionPolicy,
            principal,
            clock,
        )

    override suspend fun createReadingOrder(
        id: ReadingOrderId,
        seriesId: SeriesId,
        name: String,
    ): AppResult<Unit> {
        val caller = principal.current() ?: return notFound(NO_PRINCIPAL)
        permissionPolicy.requireCanMakeReadingOrders(caller.userId, caller.role)?.let { return AppResult.Failure(it) }
        orders.findAny(id.value)?.let { existing -> return replayOfCreate(existing, caller) }
        if (!seriesIsLive(seriesId.value)) return notFound("series=${seriesId.value}")
        val valid = ReadingOrderName.validate(name) ?: return AppResult.Failure(ReadingOrderError.InvalidName())
        if (orders.liveIdForName(seriesId.value, valid) != null) {
            return AppResult.Failure(ReadingOrderError.NameAlreadyExists())
        }
        val now = clock.now().toEpochMilliseconds()
        return orders
            .upsert(ReadingOrderSyncPayload(id.value, seriesId.value, valid, caller.userId.value, 0, now, now, null))
            .map { }
    }

    /**
     * A create naming an id that already exists. The maker replaying their own live create is the outbox
     * retrying after a lost ack, so it succeeds unchanged. Their own deleted order is gone; anyone else's
     * id is not theirs to claim.
     */
    private fun replayOfCreate(
        existing: ReadingOrderSyncPayload,
        caller: UserPrincipal,
    ): AppResult<Unit> =
        when {
            existing.createdBy != caller.userId.value -> AppResult.Failure(ReadingOrderError.Forbidden())
            existing.deletedAt != null -> notFound("order=${existing.id} deleted")
            else -> AppResult.Success(Unit)
        }

    override suspend fun renameReadingOrder(
        id: ReadingOrderId,
        name: String,
    ): AppResult<Unit> {
        val order =
            when (val gate = requireEditable(id)) {
                is EditGate.Denied -> return gate.failure
                is EditGate.Allowed -> gate.order
            }
        val valid = ReadingOrderName.validate(name) ?: return AppResult.Failure(ReadingOrderError.InvalidName())
        val clash = orders.liveIdForName(order.seriesId, valid)
        if (clash != null && clash != order.id) return AppResult.Failure(ReadingOrderError.NameAlreadyExists())
        if (order.name == valid) return AppResult.Success(Unit)
        return orders.upsert(order.copy(name = valid)).map { }
    }

    override suspend fun deleteReadingOrder(id: ReadingOrderId): AppResult<Unit> {
        val order =
            when (val gate = requireEditable(id)) {
                is EditGate.Denied -> return gate.failure
                is EditGate.Allowed -> gate.order
            }
        val suppressed = currentCoroutineContext()[FirehoseSuppressed.Key] != null
        // One transaction: the order, its memberships and every user's follow of it go together, so no
        // device ever sees a follow pointing at a deleted order or members of an order that is gone.
        suspendTransaction(sqlDb) {
            with(members) { tombstoneAllForOrder(order.id, suppressed) }
            with(follows) { tombstoneFollowsOf(order.id, suppressed) }
            with(orders) { tombstoneOrder(ReadingOrderId(order.id), suppressed) }
        }
        return AppResult.Success(Unit)
    }

    override suspend fun addBookToReadingOrder(
        id: ReadingOrderId,
        bookId: BookId,
        membershipId: String,
    ): AppResult<Unit> =
        when (val gate = requireEditable(id)) {
            is EditGate.Denied -> gate.failure
            is EditGate.Allowed -> membership.add(gate.order, gate.caller, bookId, membershipId)
        }

    override suspend fun removeBookFromReadingOrder(
        id: ReadingOrderId,
        bookId: BookId,
    ): AppResult<Unit> =
        when (val gate = requireEditable(id)) {
            is EditGate.Denied -> gate.failure
            is EditGate.Allowed -> membership.remove(gate.order, bookId)
        }

    override suspend fun reorderReadingOrder(
        id: ReadingOrderId,
        orderedBookIds: List<BookId>,
    ): AppResult<Unit> =
        when (val gate = requireEditable(id)) {
            is EditGate.Denied -> gate.failure
            is EditGate.Allowed -> membership.reorder(gate.order, orderedBookIds)
        }

    override suspend fun chooseReadingOrder(
        seriesId: SeriesId,
        choice: ReadingOrderChoice,
    ): AppResult<Unit> {
        val caller = principal.current() ?: return notFound(NO_PRINCIPAL)
        if (!seriesIsLive(seriesId.value)) return notFound("series=${seriesId.value}")
        val orderId = choice.readingOrderIdOrNull()
        if (orderId != null) {
            val order = orders.findLive(orderId.value)
            val reachable = seriesRepo.liveTree().ancestorsOf(seriesId.value) + seriesId.value
            if (order == null || order.seriesId !in reachable) {
                return AppResult.Failure(ReadingOrderError.ChoiceUnavailable())
            }
        }
        val now = clock.now().toEpochMilliseconds()
        val followId = follows.followId(caller.userId.value, seriesId.value)
        return follows
            .upsert(
                ReadingOrderFollowSyncPayload(followId, seriesId.value, choice.kind, orderId?.value, 0, now, now, null),
                userId = caller.userId.value,
            ).map { }
    }

    override suspend fun clearReadingOrderChoice(seriesId: SeriesId): AppResult<Unit> {
        val caller = principal.current() ?: return notFound(NO_PRINCIPAL)
        val followId = follows.followId(caller.userId.value, seriesId.value)
        // No live choice is already the state the caller asked for.
        if (follows.findLive(followId) == null) return AppResult.Success(Unit)
        return follows.softDelete(followId, userId = caller.userId.value)
    }

    override suspend fun countReadingOrderFollowers(id: ReadingOrderId): AppResult<Int> =
        when (val gate = requireEditable(id)) {
            is EditGate.Denied -> gate.failure
            is EditGate.Allowed -> AppResult.Success(follows.countFollowersOf(gate.order.id))
        }

    /**
     * The live order [id], when the caller may change it: ROOT/ADMIN always; its maker only while they
     * still hold the permission (an admin turning it off keeps their orders, but freezes them). An order
     * that is gone, or whose series is not live, is [ReadingOrderError.NotFound].
     */
    private suspend fun requireEditable(id: ReadingOrderId): EditGate {
        val caller = principal.current() ?: return EditGate.Denied(notFound(NO_PRINCIPAL))
        val order =
            orders.findLive(id.value)?.takeIf { seriesIsLive(it.seriesId) }
                ?: return EditGate.Denied(notFound("order=${id.value}"))
        if (caller.role == UserRole.ROOT || caller.role == UserRole.ADMIN) return EditGate.Allowed(order, caller)
        val isMakerWithPermission =
            order.createdBy == caller.userId.value &&
                permissionPolicy.requireCanMakeReadingOrders(caller.userId, caller.role) == null
        if (!isMakerWithPermission) return EditGate.Denied(AppResult.Failure(ReadingOrderError.Forbidden()))
        return EditGate.Allowed(order, caller)
    }

    private suspend fun seriesIsLive(seriesId: String): Boolean =
        seriesRepo.findById(seriesId)?.let { it.deletedAt == null } ?: false

    private fun notFound(debugInfo: String): AppResult.Failure =
        AppResult.Failure(ReadingOrderError.NotFound(debugInfo = debugInfo))

    /** The outcome of [requireEditable]. */
    private sealed interface EditGate {
        /** The caller may change [order]. */
        data class Allowed(
            val order: ReadingOrderSyncPayload,
            val caller: UserPrincipal,
        ) : EditGate

        /** The caller may not; [failure] says why. */
        data class Denied(
            val failure: AppResult.Failure,
        ) : EditGate
    }
}

/** debugInfo for a call that reached the service without a bound caller — a wiring bug. */
private const val NO_PRINCIPAL = "no principal"
