package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.ReadingOrderService
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.readingorder.DiscoveredReadingOrder
import com.calypsan.listenup.api.dto.readingorder.ReadingOrder
import com.calypsan.listenup.api.dto.readingorder.ReadingOrderDetail
import com.calypsan.listenup.api.dto.readingorder.SetActiveReadingOrderRequest
import com.calypsan.listenup.api.error.ReadingOrderError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.ReadingOrderFollowSyncPayload
import com.calypsan.listenup.api.sync.ReadingOrderSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ReadingOrderId
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.services.ActivityRecorder
import com.calypsan.listenup.server.sync.OwnedReadingOrder
import com.calypsan.listenup.server.sync.ReadingOrderBookRepository
import com.calypsan.listenup.server.sync.ReadingOrderFollowRepository
import com.calypsan.listenup.server.sync.ReadingOrderRepository
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val MAX_NAME_LENGTH = 200
private const val DISCOVER_LIMIT_MIN = 1
private const val DISCOVER_LIMIT_MAX = 200

/**
 * [ReadingOrderService] implementation.
 *
 * Resolves the authenticated caller from [principal] (never from request fields),
 * gates every operation through ownership, and applies [BookAccessPolicy] when a
 * non-owner views or discovers another user's reading order so books the viewer
 * cannot access are silently excluded. A near-exact sibling of [ShelfServiceImpl]
 * (Integration Foundations spec §5.3, the Reading Orders fold-in) — the sole
 * content difference is the [ReadingOrderSyncPayload.attribution] field threaded
 * through create/update.
 *
 * Access semantics — reading orders are single-owner, so the rule is simpler than
 * collections (no shared write grants):
 * - **Mutation** ([createReadingOrder], [updateReadingOrder], [deleteReadingOrder],
 *   [addBookToReadingOrder], [removeBookFromReadingOrder], [reorderReadingOrderBooks])
 *   is owner-only. A non-owner gets [ReadingOrderError.Forbidden] (the reading order
 *   exists, they just can't touch it); a missing reading order gets
 *   [ReadingOrderError.NotFound]. ROOT/ADMIN bypass ownership.
 * - **Read** ([getReadingOrder]) returns all books to the owner/admin; to a non-owner
 *   on a *public* reading order it returns only [BookAccessPolicy]-visible books; to
 *   a non-owner on a *private* reading order it returns [ReadingOrderError.NotFound]
 *   — never leaking the reading order's existence.
 * - **Discovery** ([discoverReadingOrders]) lists other users' public reading orders
 *   whose book set has ≥1 caller-accessible book, with the book count reflecting
 *   only what the caller can see.
 *
 * Route handlers call [copyWith] to bind each request to the authenticated principal;
 * the Koin singleton carries an unscoped placeholder that yields no principal.
 */
internal class ReadingOrderServiceImpl(
    private val readingOrderRepo: ReadingOrderRepository,
    private val readingOrderBookRepo: ReadingOrderBookRepository,
    private val followRepo: ReadingOrderFollowRepository,
    private val bookAccessPolicy: BookAccessPolicy,
    private val readAssembler: ReadingOrderReadAssembler,
    private val clock: Clock = Clock.System,
    private val principal: PrincipalProvider,
    private val activityRecorder: ActivityRecorder? = null,
) : ReadingOrderService {
    // ── Own-order mutation ────────────────────────────────────────────────────

    override suspend fun createReadingOrder(
        name: String,
        description: String,
        attribution: String,
        isPrivate: Boolean,
    ): AppResult<ReadingOrder> {
        val caller = resolveCaller() ?: return noPrincipal()
        val trimmed = validateName(name) ?: return AppResult.Failure(ReadingOrderError.InvalidName())

        val now = clock.now().toEpochMilliseconds()
        val payload =
            ReadingOrderSyncPayload(
                id = Uuid.random().toString(),
                name = trimmed,
                description = description,
                attribution = attribution,
                isPrivate = isPrivate,
                revision = 0L,
                updatedAt = now,
                createdAt = now,
                deletedAt = null,
            )
        return when (val result = readingOrderRepo.upsert(payload, userId = caller.userId)) {
            is AppResult.Success -> AppResult.Success(result.data.toSummary(bookCount = 0))
            is AppResult.Failure -> AppResult.Failure(result.error)
        }
    }

    override suspend fun updateReadingOrder(
        readingOrderId: ReadingOrderId,
        name: String,
        description: String,
        attribution: String,
        isPrivate: Boolean,
    ): AppResult<ReadingOrder> {
        val caller = resolveCaller() ?: return noPrincipal()
        val owned =
            when (val gate = requireOwner(readingOrderId, caller)) {
                is OwnerGate.Denied -> return AppResult.Failure(gate.error)
                is OwnerGate.Allowed -> gate.owned
            }
        val trimmed = validateName(name) ?: return AppResult.Failure(ReadingOrderError.InvalidName())

        val updated =
            owned.readingOrder.copy(
                name = trimmed,
                description = description,
                attribution = attribution,
                isPrivate = isPrivate,
                updatedAt = clock.now().toEpochMilliseconds(),
            )
        return when (val result = readingOrderRepo.upsert(updated, userId = owned.ownerId)) {
            is AppResult.Success -> AppResult.Success(result.data.toSummary(liveBookCount(readingOrderId)))
            is AppResult.Failure -> AppResult.Failure(result.error)
        }
    }

    override suspend fun deleteReadingOrder(readingOrderId: ReadingOrderId): AppResult<Unit> {
        val caller = resolveCaller() ?: return noPrincipal()
        val owned =
            when (val gate = requireOwner(readingOrderId, caller)) {
                is OwnerGate.Denied -> return AppResult.Failure(gate.error)
                is OwnerGate.Allowed -> gate.owned
            }

        readingOrderBookRepo.softDeleteByReadingOrder(readingOrderId.value, userId = owned.ownerId)
        // Clear every follower's pointer at the deleted order (cross-user by design) so no
        // follow row dangles at a tombstone — each clear syncs to its owner as a revision bump.
        followRepo.clearFollowsOf(readingOrderId.value)
        return readingOrderRepo.softDelete(readingOrderId, userId = owned.ownerId)
    }

    override suspend fun addBookToReadingOrder(
        readingOrderId: ReadingOrderId,
        bookId: BookId,
    ): AppResult<Unit> {
        val caller = resolveCaller() ?: return noPrincipal()
        val owned =
            when (val gate = requireOwner(readingOrderId, caller)) {
                is OwnerGate.Denied -> return AppResult.Failure(gate.error)
                is OwnerGate.Allowed -> gate.owned
            }

        // An owner can only add a book they can actually see — gate by the same
        // visibility rule that hides it everywhere else. NotFound (not Forbidden):
        // we don't reveal that an inaccessible book exists.
        if (!bookAccessPolicy.canAccess(caller.userId, caller.role, bookId.value)) {
            return AppResult.Failure(ReadingOrderError.NotFound())
        }

        return when (
            val result =
                readingOrderBookRepo.addBook(
                    readingOrderId.value,
                    bookId.value,
                    userId = owned.ownerId,
                )
        ) {
            is AppResult.Success -> AppResult.Success(Unit)
            is AppResult.Failure -> AppResult.Failure(result.error)
        }
    }

    override suspend fun removeBookFromReadingOrder(
        readingOrderId: ReadingOrderId,
        bookId: BookId,
    ): AppResult<Unit> {
        val caller = resolveCaller() ?: return noPrincipal()
        val owned =
            when (val gate = requireOwner(readingOrderId, caller)) {
                is OwnerGate.Denied -> return AppResult.Failure(gate.error)
                is OwnerGate.Allowed -> gate.owned
            }

        readingOrderBookRepo.removeBook(readingOrderId.value, bookId.value, userId = owned.ownerId)
        return AppResult.Success(Unit)
    }

    override suspend fun reorderReadingOrderBooks(
        readingOrderId: ReadingOrderId,
        orderedBookIds: List<BookId>,
    ): AppResult<Unit> {
        val caller = resolveCaller() ?: return noPrincipal()
        val owned =
            when (val gate = requireOwner(readingOrderId, caller)) {
                is OwnerGate.Denied -> return AppResult.Failure(gate.error)
                is OwnerGate.Allowed -> gate.owned
            }

        return readingOrderBookRepo.reorder(
            readingOrderId.value,
            orderedBookIds.map { it.value },
            userId = owned.ownerId,
        )
    }

    // ── Follow-state ──────────────────────────────────────────────────────────

    override suspend fun setActiveReadingOrder(request: SetActiveReadingOrderRequest): AppResult<Unit> {
        val caller = resolveCaller() ?: return noPrincipal()

        // Following requires a reading order the caller can actually see: their own
        // (any privacy) or another user's public order. NotFound (not Forbidden) so a
        // private order's existence is never revealed.
        val targetId = request.activeReadingOrderId
        if (targetId != null) {
            val owned =
                readingOrderRepo.findOwnedById(targetId) ?: return AppResult.Failure(ReadingOrderError.NotFound())
            val visible = owned.ownerId == caller.userId || caller.isAdmin || !owned.readingOrder.isPrivate
            if (!visible) return AppResult.Failure(ReadingOrderError.NotFound())
        }

        val now = clock.now().toEpochMilliseconds()
        val existing = followRepo.findLive(caller.userId, request.seriesId)
        val payload =
            existing?.copy(activeReadingOrderId = targetId, updatedAt = now)
                ?: ReadingOrderFollowSyncPayload(
                    id = "${caller.userId}:${request.seriesId}",
                    seriesId = request.seriesId,
                    activeReadingOrderId = targetId,
                    revision = 0L,
                    updatedAt = now,
                    createdAt = now,
                    deletedAt = null,
                )
        return when (val result = followRepo.upsert(payload, userId = caller.userId)) {
            is AppResult.Success -> AppResult.Success(Unit)
            is AppResult.Failure -> AppResult.Failure(result.error)
        }
    }

    // ── Observation ──────────────────────────────────────────────────────────

    override suspend fun listMyReadingOrders(): AppResult<List<ReadingOrder>> {
        val caller = resolveCaller() ?: return noPrincipal()
        val orders =
            readingOrderRepo
                .listOwnedBy(caller.userId)
                .sortedByDescending { it.updatedAt }
                .map { order -> order.toSummary(liveBookCount(ReadingOrderId(order.id))) }
        return AppResult.Success(orders)
    }

    override suspend fun getReadingOrder(readingOrderId: ReadingOrderId): AppResult<ReadingOrderDetail> {
        val caller = resolveCaller() ?: return noPrincipal()
        val owned =
            readingOrderRepo.findOwnedById(readingOrderId.value)
                ?: return AppResult.Failure(ReadingOrderError.NotFound())
        val order = owned.readingOrder

        val isOwner = owned.ownerId == caller.userId
        val canSeeAll = isOwner || caller.isAdmin
        val liveBookIds = readingOrderBookRepo.listByReadingOrder(order.id).map { it.bookId }

        val visibleBookIds =
            when {
                canSeeAll -> liveBookIds
                order.isPrivate -> return AppResult.Failure(ReadingOrderError.NotFound())
                else -> filterAccessible(liveBookIds, caller)
            }

        val views = readAssembler.viewsFor(visibleBookIds)
        return AppResult.Success(
            ReadingOrderDetail(
                id = ReadingOrderId(order.id),
                name = order.name,
                description = order.description,
                attribution = order.attribution,
                isPrivate = order.isPrivate,
                isOwner = isOwner,
                books = views,
                bookCount = views.size,
                totalDurationMs = readAssembler.totalDurationMsFor(visibleBookIds),
            ),
        )
    }

    override suspend fun discoverReadingOrders(limit: Int): AppResult<List<DiscoveredReadingOrder>> {
        val caller = resolveCaller() ?: return noPrincipal()
        val safeLimit = limit.coerceIn(DISCOVER_LIMIT_MIN, DISCOVER_LIMIT_MAX)

        val discovered =
            readingOrderRepo
                .listDiscoverable(excludeUserId = caller.userId)
                .mapNotNull { owned -> discoveredOrNull(owned, caller) }
                .take(safeLimit)
        return AppResult.Success(discovered)
    }

    override suspend fun getUserReadingOrders(userId: UserId): AppResult<List<ReadingOrder>> {
        val caller = resolveCaller() ?: return noPrincipal()
        val orders =
            readingOrderRepo
                .listForOwner(userId.value)
                .mapNotNull { owned -> accessibleSummaryOrNull(owned, caller) }
        return AppResult.Success(orders)
    }

    // ── Principal binding ─────────────────────────────────────────────────────

    /** Returns a copy scoped to the given [principal]. Route handlers call this per-request. */
    fun copyWith(principal: PrincipalProvider): ReadingOrderServiceImpl =
        ReadingOrderServiceImpl(
            readingOrderRepo = readingOrderRepo,
            readingOrderBookRepo = readingOrderBookRepo,
            followRepo = followRepo,
            bookAccessPolicy = bookAccessPolicy,
            readAssembler = readAssembler,
            clock = clock,
            principal = principal,
            activityRecorder = activityRecorder,
        )

    // ── Private helpers ───────────────────────────────────────────────────────

    /** The resolved caller: their user id and contract role (the role [BookAccessPolicy] speaks). */
    private data class Caller(
        val userId: String,
        val role: UserRole,
    ) {
        val isAdmin: Boolean get() = role == UserRole.ROOT || role == UserRole.ADMIN
    }

    /** Owner-gate outcome: [Allowed] carries the loaded reading order; [Denied] carries the typed failure. */
    private sealed interface OwnerGate {
        /** Ownership passed; carries the loaded [OwnedReadingOrder] for the operation to act on. */
        data class Allowed(
            val owned: OwnedReadingOrder,
        ) : OwnerGate

        /** Ownership failed; carries the typed [ReadingOrderError] to return to the caller. */
        data class Denied(
            val error: ReadingOrderError,
        ) : OwnerGate
    }

    private fun resolveCaller(): Caller? = principal.current()?.let { Caller(it.userId.value, it.role) }

    private fun noPrincipal(): AppResult.Failure = AppResult.Failure(ReadingOrderError.NotFound())

    private fun validateName(name: String): String? {
        val trimmed = name.trim()
        return if (trimmed.isEmpty() || trimmed.length > MAX_NAME_LENGTH) null else trimmed
    }

    /**
     * Owner-only gate: loads the reading order and decides whether [caller] may
     * mutate it. Missing reading order → [OwnerGate.Denied] with
     * [ReadingOrderError.NotFound]; present but neither owner nor admin →
     * [OwnerGate.Denied] with [ReadingOrderError.Forbidden]; otherwise
     * [OwnerGate.Allowed] with the loaded reading order.
     */
    private suspend fun requireOwner(
        readingOrderId: ReadingOrderId,
        caller: Caller,
    ): OwnerGate {
        val owned =
            readingOrderRepo.findOwnedById(readingOrderId.value)
                ?: return OwnerGate.Denied(ReadingOrderError.NotFound())
        return if (owned.ownerId == caller.userId || caller.isAdmin) {
            OwnerGate.Allowed(owned)
        } else {
            OwnerGate.Denied(ReadingOrderError.Forbidden())
        }
    }

    /** Intersects [bookIds] with the caller's accessible set; admins (null = unconstrained) keep all. */
    private suspend fun filterAccessible(
        bookIds: List<String>,
        caller: Caller,
    ): List<String> {
        val accessible = bookAccessPolicy.accessibleBookIds(caller.userId, caller.role) ?: return bookIds
        return bookIds.filter { it in accessible }
    }

    /**
     * Maps [owned] to a [DiscoveredReadingOrder] for [caller], or null when the
     * caller can see none of the reading order's books — an order with zero
     * accessible books is excluded from discovery entirely. The summary's book
     * count reflects only accessible books.
     */
    private suspend fun discoveredOrNull(
        owned: OwnedReadingOrder,
        caller: Caller,
    ): DiscoveredReadingOrder? {
        val liveBookIds = readingOrderBookRepo.listByReadingOrder(owned.readingOrder.id).map { it.bookId }
        val accessibleCount = filterAccessible(liveBookIds, caller).size
        if (accessibleCount == 0) return null
        return DiscoveredReadingOrder(
            readingOrder = owned.readingOrder.toSummary(bookCount = accessibleCount),
            ownerId = owned.ownerId,
            ownerDisplayName = readAssembler.displayNameFor(owned.ownerId),
        )
    }

    /**
     * Like [discoveredOrNull] but returns the bare [ReadingOrder] summary (no owner
     * wrapper); null when zero books are accessible to [caller] — an order with
     * zero accessible books is excluded from [getUserReadingOrders] entirely.
     */
    private suspend fun accessibleSummaryOrNull(
        owned: OwnedReadingOrder,
        caller: Caller,
    ): ReadingOrder? {
        val liveBookIds = readingOrderBookRepo.listByReadingOrder(owned.readingOrder.id).map { it.bookId }
        val accessibleCount = filterAccessible(liveBookIds, caller).size
        if (accessibleCount == 0) return null
        return owned.readingOrder.toSummary(bookCount = accessibleCount)
    }

    private suspend fun liveBookCount(readingOrderId: ReadingOrderId): Int =
        readingOrderBookRepo.listByReadingOrder(readingOrderId.value).size

    private fun ReadingOrderSyncPayload.toSummary(bookCount: Int): ReadingOrder =
        ReadingOrder(
            id = ReadingOrderId(id),
            name = name,
            description = description,
            attribution = attribution,
            isPrivate = isPrivate,
            bookCount = bookCount,
            updatedAt = updatedAt,
        )
}
