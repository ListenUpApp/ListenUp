package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.ShelfService
import com.calypsan.listenup.api.dto.activity.ActivityType
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.shelf.DiscoveredShelf
import com.calypsan.listenup.api.dto.shelf.Shelf
import com.calypsan.listenup.api.dto.shelf.ShelfDetail
import com.calypsan.listenup.api.error.ShelfError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.ShelfSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ShelfId
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.services.ActivityRecorder
import com.calypsan.listenup.server.sync.OwnedShelf
import com.calypsan.listenup.server.sync.ShelfBookRepository
import com.calypsan.listenup.server.sync.ShelfRepository
import java.util.UUID
import kotlin.time.Clock

private const val MAX_NAME_LENGTH = 200
private const val DISCOVER_LIMIT_MIN = 1
private const val DISCOVER_LIMIT_MAX = 200

/**
 * [ShelfService] implementation.
 *
 * Resolves the authenticated caller from [principal] (never from request fields),
 * gates every operation through ownership, and applies [BookAccessPolicy] when a
 * non-owner views or discovers another user's shelf so books the viewer cannot
 * access are silently excluded.
 *
 * Access semantics — shelves are single-owner, so the rule is simpler than
 * collections (no shared write grants):
 * - **Mutation** ([createShelf], [updateShelf], [deleteShelf], [addBookToShelf],
 *   [removeBookFromShelf], [reorderShelfBooks]) is owner-only. A non-owner gets
 *   [ShelfError.Forbidden] (the shelf exists, they just can't touch it); a missing
 *   shelf gets [ShelfError.NotFound]. ROOT/ADMIN bypass ownership.
 * - **Read** ([getShelf]) returns all books to the owner/admin; to a non-owner on a
 *   *public* shelf it returns only [BookAccessPolicy]-visible books; to a non-owner on
 *   a *private* shelf it returns [ShelfError.NotFound] — never leaking the shelf's
 *   existence.
 * - **Discovery** ([discoverShelves]) lists other users' public shelves whose book
 *   set has ≥1 caller-accessible book, with the book count reflecting only what the
 *   caller can see.
 *
 * Route handlers call [copyWith] to bind each request to the authenticated principal;
 * the Koin singleton carries an unscoped placeholder that yields no principal.
 */
internal class ShelfServiceImpl(
    private val shelfRepo: ShelfRepository,
    private val shelfBookRepo: ShelfBookRepository,
    private val bookAccessPolicy: BookAccessPolicy,
    private val readAssembler: ShelfReadAssembler,
    private val clock: Clock = Clock.System,
    private val principal: PrincipalProvider,
    private val activityRecorder: ActivityRecorder? = null,
) : ShelfService {
    // ── Own-shelf mutation ────────────────────────────────────────────────────

    override suspend fun createShelf(
        name: String,
        description: String,
        isPrivate: Boolean,
    ): AppResult<Shelf> {
        val caller = resolveCaller() ?: return noPrincipal()
        val trimmed = validateName(name) ?: return AppResult.Failure(ShelfError.InvalidName())

        val now = clock.now().toEpochMilliseconds()
        val payload =
            ShelfSyncPayload(
                id = UUID.randomUUID().toString(),
                name = trimmed,
                description = description,
                isPrivate = isPrivate,
                revision = 0L,
                updatedAt = now,
                createdAt = now,
                deletedAt = null,
            )
        return when (val result = shelfRepo.upsert(payload, userId = caller.userId)) {
            is AppResult.Success -> {
                if (!isPrivate) {
                    activityRecorder?.record(
                        caller.userId,
                        ActivityType.SHELF_CREATED,
                        shelfId = payload.id,
                        shelfName = trimmed,
                    )
                }
                AppResult.Success(result.data.toSummary(bookCount = 0))
            }

            is AppResult.Failure -> {
                AppResult.Failure(result.error)
            }
        }
    }

    override suspend fun updateShelf(
        shelfId: ShelfId,
        name: String,
        description: String,
        isPrivate: Boolean,
    ): AppResult<Shelf> {
        val caller = resolveCaller() ?: return noPrincipal()
        val owned =
            when (val gate = requireOwner(shelfId, caller)) {
                is OwnerGate.Denied -> return AppResult.Failure(gate.error)
                is OwnerGate.Allowed -> gate.owned
            }
        val trimmed = validateName(name) ?: return AppResult.Failure(ShelfError.InvalidName())

        val updated =
            owned.shelf.copy(
                name = trimmed,
                description = description,
                isPrivate = isPrivate,
                updatedAt = clock.now().toEpochMilliseconds(),
            )
        return when (val result = shelfRepo.upsert(updated, userId = owned.ownerId)) {
            is AppResult.Success -> AppResult.Success(result.data.toSummary(liveBookCount(shelfId)))
            is AppResult.Failure -> AppResult.Failure(result.error)
        }
    }

    override suspend fun deleteShelf(shelfId: ShelfId): AppResult<Unit> {
        val caller = resolveCaller() ?: return noPrincipal()
        val owned =
            when (val gate = requireOwner(shelfId, caller)) {
                is OwnerGate.Denied -> return AppResult.Failure(gate.error)
                is OwnerGate.Allowed -> gate.owned
            }

        shelfBookRepo.softDeleteByShelf(shelfId.value, userId = owned.ownerId)
        return shelfRepo.softDelete(shelfId, userId = owned.ownerId)
    }

    override suspend fun addBookToShelf(
        shelfId: ShelfId,
        bookId: BookId,
    ): AppResult<Unit> {
        val caller = resolveCaller() ?: return noPrincipal()
        val owned =
            when (val gate = requireOwner(shelfId, caller)) {
                is OwnerGate.Denied -> return AppResult.Failure(gate.error)
                is OwnerGate.Allowed -> gate.owned
            }

        // An owner can only shelve a book they can actually see — gate by the same
        // visibility rule that hides it everywhere else. NotFound (not Forbidden):
        // we don't reveal that an inaccessible book exists.
        if (!bookAccessPolicy.canAccess(caller.userId, caller.role, bookId.value)) {
            return AppResult.Failure(ShelfError.NotFound())
        }

        return when (val result = shelfBookRepo.addBook(shelfId.value, bookId.value, userId = owned.ownerId)) {
            is AppResult.Success -> AppResult.Success(Unit)
            is AppResult.Failure -> AppResult.Failure(result.error)
        }
    }

    override suspend fun removeBookFromShelf(
        shelfId: ShelfId,
        bookId: BookId,
    ): AppResult<Unit> {
        val caller = resolveCaller() ?: return noPrincipal()
        val owned =
            when (val gate = requireOwner(shelfId, caller)) {
                is OwnerGate.Denied -> return AppResult.Failure(gate.error)
                is OwnerGate.Allowed -> gate.owned
            }

        shelfBookRepo.removeBook(shelfId.value, bookId.value, userId = owned.ownerId)
        return AppResult.Success(Unit)
    }

    override suspend fun reorderShelfBooks(
        shelfId: ShelfId,
        orderedBookIds: List<BookId>,
    ): AppResult<Unit> {
        val caller = resolveCaller() ?: return noPrincipal()
        val owned =
            when (val gate = requireOwner(shelfId, caller)) {
                is OwnerGate.Denied -> return AppResult.Failure(gate.error)
                is OwnerGate.Allowed -> gate.owned
            }

        return shelfBookRepo.reorder(shelfId.value, orderedBookIds.map { it.value }, userId = owned.ownerId)
    }

    // ── Observation ──────────────────────────────────────────────────────────

    override suspend fun listMyShelves(): AppResult<List<Shelf>> {
        val caller = resolveCaller() ?: return noPrincipal()
        val shelves =
            shelfRepo
                .listOwnedBy(caller.userId)
                .sortedByDescending { it.updatedAt }
                .map { shelf -> shelf.toSummary(liveBookCount(ShelfId(shelf.id))) }
        return AppResult.Success(shelves)
    }

    override suspend fun getShelf(shelfId: ShelfId): AppResult<ShelfDetail> {
        val caller = resolveCaller() ?: return noPrincipal()
        val owned = shelfRepo.findOwnedById(shelfId.value) ?: return AppResult.Failure(ShelfError.NotFound())
        val shelf = owned.shelf

        val isOwner = owned.ownerId == caller.userId
        val canSeeAll = isOwner || caller.isAdmin
        val liveBookIds = shelfBookRepo.listByShelf(shelf.id).map { it.bookId }

        val visibleBookIds =
            when {
                canSeeAll -> liveBookIds
                shelf.isPrivate -> return AppResult.Failure(ShelfError.NotFound())
                else -> filterAccessible(liveBookIds, caller)
            }

        val views = readAssembler.viewsFor(visibleBookIds)
        return AppResult.Success(
            ShelfDetail(
                id = ShelfId(shelf.id),
                name = shelf.name,
                description = shelf.description,
                isPrivate = shelf.isPrivate,
                isOwner = isOwner,
                books = views,
                bookCount = views.size,
                totalDurationMs = readAssembler.totalDurationMsFor(visibleBookIds),
            ),
        )
    }

    override suspend fun discoverShelves(limit: Int): AppResult<List<DiscoveredShelf>> {
        val caller = resolveCaller() ?: return noPrincipal()
        val safeLimit = limit.coerceIn(DISCOVER_LIMIT_MIN, DISCOVER_LIMIT_MAX)

        val discovered =
            shelfRepo
                .listDiscoverable(excludeUserId = caller.userId)
                .mapNotNull { owned -> discoveredOrNull(owned, caller) }
                .take(safeLimit)
        return AppResult.Success(discovered)
    }

    override suspend fun getUserShelves(userId: UserId): AppResult<List<Shelf>> {
        val caller = resolveCaller() ?: return noPrincipal()
        val shelves =
            shelfRepo
                .listForOwner(userId.value)
                .mapNotNull { owned -> accessibleSummaryOrNull(owned, caller) }
        return AppResult.Success(shelves)
    }

    // ── Principal binding ─────────────────────────────────────────────────────

    /** Returns a copy scoped to the given [principal]. Route handlers call this per-request. */
    fun copyWith(principal: PrincipalProvider): ShelfServiceImpl =
        ShelfServiceImpl(
            shelfRepo = shelfRepo,
            shelfBookRepo = shelfBookRepo,
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

    /** Owner-gate outcome: [Allowed] carries the loaded shelf; [Denied] carries the typed failure. */
    private sealed interface OwnerGate {
        data class Allowed(
            val owned: OwnedShelf,
        ) : OwnerGate

        data class Denied(
            val error: ShelfError,
        ) : OwnerGate
    }

    private fun resolveCaller(): Caller? = principal.current()?.let { Caller(it.userId.value, it.role) }

    private fun noPrincipal(): AppResult.Failure = AppResult.Failure(ShelfError.NotFound())

    private fun validateName(name: String): String? {
        val trimmed = name.trim()
        return if (trimmed.isEmpty() || trimmed.length > MAX_NAME_LENGTH) null else trimmed
    }

    /**
     * Owner-only gate: loads the shelf and decides whether [caller] may mutate it.
     * Missing shelf → [OwnerGate.Denied] with [ShelfError.NotFound]; present but
     * neither owner nor admin → [OwnerGate.Denied] with [ShelfError.Forbidden];
     * otherwise [OwnerGate.Allowed] with the loaded shelf.
     */
    private suspend fun requireOwner(
        shelfId: ShelfId,
        caller: Caller,
    ): OwnerGate {
        val owned = shelfRepo.findOwnedById(shelfId.value) ?: return OwnerGate.Denied(ShelfError.NotFound())
        return if (owned.ownerId == caller.userId || caller.isAdmin) {
            OwnerGate.Allowed(owned)
        } else {
            OwnerGate.Denied(ShelfError.Forbidden())
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
     * Maps [owned] to a [DiscoveredShelf] for [caller], or null when the caller can
     * see none of the shelf's books — a shelf with zero accessible books is excluded
     * from discovery entirely. The summary's book count reflects only accessible books.
     */
    private suspend fun discoveredOrNull(
        owned: OwnedShelf,
        caller: Caller,
    ): DiscoveredShelf? {
        val liveBookIds = shelfBookRepo.listByShelf(owned.shelf.id).map { it.bookId }
        val accessibleCount = filterAccessible(liveBookIds, caller).size
        if (accessibleCount == 0) return null
        return DiscoveredShelf(
            shelf = owned.shelf.toSummary(bookCount = accessibleCount),
            ownerId = owned.ownerId,
            ownerDisplayName = readAssembler.displayNameFor(owned.ownerId),
        )
    }

    /**
     * Like [discoveredOrNull] but returns the bare [Shelf] summary (no owner wrapper); null
     * when zero books are accessible to [caller] — a shelf with zero accessible books is
     * excluded from [getUserShelves] entirely.
     */
    private suspend fun accessibleSummaryOrNull(
        owned: OwnedShelf,
        caller: Caller,
    ): Shelf? {
        val liveBookIds = shelfBookRepo.listByShelf(owned.shelf.id).map { it.bookId }
        val accessibleCount = filterAccessible(liveBookIds, caller).size
        if (accessibleCount == 0) return null
        return owned.shelf.toSummary(bookCount = accessibleCount)
    }

    private suspend fun liveBookCount(shelfId: ShelfId): Int = shelfBookRepo.listByShelf(shelfId.value).size

    private fun ShelfSyncPayload.toSummary(bookCount: Int): Shelf =
        Shelf(
            id = ShelfId(id),
            name = name,
            description = description,
            isPrivate = isPrivate,
            bookCount = bookCount,
            updatedAt = updatedAt,
        )
}
