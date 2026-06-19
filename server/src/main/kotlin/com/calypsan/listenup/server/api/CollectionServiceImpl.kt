package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.CollectionService
import com.calypsan.listenup.api.dto.CollectionShareDto
import com.calypsan.listenup.api.dto.CollectionSummary
import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.error.CollectionError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.getOrElse
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.CollectionShareSyncPayload
import com.calypsan.listenup.api.sync.CollectionSyncPayload
import com.calypsan.listenup.api.sync.SyncControl
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.CollectionId
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPermissionPolicy
import com.calypsan.listenup.server.auth.toColumn
import com.calypsan.listenup.server.auth.toContract
import com.calypsan.listenup.server.db.BookTable
import com.calypsan.listenup.server.db.LibraryTable
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.db.UserTable
import com.calypsan.listenup.server.services.BookRevisionTouch
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.CollectionBookRepository
import com.calypsan.listenup.server.sync.CollectionRepository
import com.calypsan.listenup.server.sync.CollectionShareRepository
import java.util.UUID
import kotlin.time.Clock
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

private const val LIST_BOOKS_MIN = 1
private const val LIST_BOOKS_MAX = 1000
private const val MAX_NAME_LENGTH = 200

/**
 * [CollectionService] implementation.
 *
 * Resolves the authenticated caller from [principal] (never from request fields),
 * bridges the contract [UserRole] to the DB [UserRoleColumn] the
 * [CollectionAccessPolicy] speaks, and gates every operation through that policy.
 *
 * Access semantics deliberately distinguish "can't see" from "can see but can't":
 * - **Read** ([getCollection], [listCollectionBooks]) require [CollectionAccessPolicy.Decision.canAccess];
 *   otherwise [CollectionError.NotFound] — we never leak the existence of a collection
 *   the caller has no relationship to.
 * - **Write-book** ([addBookToCollection], [removeBookFromCollection]) require write
 *   permission; a caller who can read but not write gets [CollectionError.Forbidden],
 *   a caller who can't see it at all gets [CollectionError.NotFound].
 * - **Owner-only** ([renameCollection], [deleteCollection]) require ownership (admins
 *   bypass via the policy); a non-owner who can read gets [CollectionError.Forbidden],
 *   one who can't see it gets [CollectionError.NotFound].
 *
 * Route handlers call [copyWith] to bind each request to the authenticated principal;
 * the Koin singleton carries an unscoped placeholder that yields no principal.
 *
 * Sharing ([shareCollection], [updateShare], [revokeShare], [listShares]) is owner-only —
 * gated through [ownerGate] like rename/delete. The per-user `AccessChanged` reconcile
 * signal on share/unshare is Collections-1b (it depends on the book-visibility layer);
 * 1a just persists the share rows and emits the normal sync events.
 */
internal class CollectionServiceImpl(
    private val collectionRepo: CollectionRepository,
    private val collectionBookRepo: CollectionBookRepository,
    private val shareRepo: CollectionShareRepository,
    private val accessPolicy: CollectionAccessPolicy,
    private val permissionPolicy: UserPermissionPolicy,
    private val bus: ChangeBus,
    private val db: Database,
    private val clock: Clock = Clock.System,
    private val bookRevisionTouch: BookRevisionTouch,
    private val principal: PrincipalProvider,
) : CollectionService {
    // ── Observation ─────────────────────────────────────────────────────────

    override suspend fun listCollections(): AppResult<List<CollectionSummary>> {
        val caller = resolveCaller() ?: return noPrincipal()

        val collections =
            if (caller.role == UserRoleColumn.ROOT || caller.role == UserRoleColumn.ADMIN) {
                collectionRepo.listAll()
            } else {
                val owned = collectionRepo.listOwnedBy(caller.userId)
                val sharedIds = shareRepo.listActiveSharesForUser(caller.userId).map { it.collectionId }
                val shared = sharedIds.mapNotNull { collectionRepo.findById(it) }
                (owned + shared).distinctBy { it.id }
            }

        val summaries = collections.map { summarize(it, caller) }
        return AppResult.Success(summaries)
    }

    override suspend fun getCollection(id: CollectionId): AppResult<CollectionSummary> {
        val caller = resolveCaller() ?: return noPrincipal()
        val decision = accessPolicy.decide(caller.userId, caller.role, id.value)
        if (!decision.canAccess) return AppResult.Failure(CollectionError.NotFound())
        val collection = collectionRepo.findById(id.value) ?: return AppResult.Failure(CollectionError.NotFound())
        return AppResult.Success(summarize(collection, caller, decision))
    }

    override suspend fun listCollectionBooks(
        id: CollectionId,
        limit: Int,
    ): AppResult<List<BookId>> {
        val caller = resolveCaller() ?: return noPrincipal()
        if (!accessPolicy.canRead(caller.userId, caller.role, id.value)) {
            return AppResult.Failure(CollectionError.NotFound())
        }
        val safeLimit = limit.coerceIn(LIST_BOOKS_MIN, LIST_BOOKS_MAX)
        val bookIds = collectionBookRepo.findBookIdsForCollection(id.value).take(safeLimit).map { BookId(it) }
        return AppResult.Success(bookIds)
    }

    // ── Membership mutation ───────────────────────────────────────────────────

    override suspend fun createCollection(
        libraryId: String,
        name: String,
    ): AppResult<CollectionSummary> {
        val caller = resolveCaller() ?: return noPrincipal()
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_NAME_LENGTH) {
            return AppResult.Failure(CollectionError.InvalidInput())
        }

        val payload =
            CollectionSyncPayload(
                id = UUID.randomUUID().toString(),
                libraryId = libraryId,
                ownerId = caller.userId,
                name = trimmed,
                isInbox = false,
                isGlobalAccess = false,
                revision = 0L,
                updatedAt = clock.now().toEpochMilliseconds(),
            )
        val saved = collectionRepo.upsert(payload).getOrElse { return AppResult.Failure(it) }
        return AppResult.Success(summarize(saved, caller))
    }

    override suspend fun renameCollection(
        id: CollectionId,
        name: String,
    ): AppResult<CollectionSummary> {
        val caller = resolveCaller() ?: return noPrincipal()
        val decision = accessPolicy.decide(caller.userId, caller.role, id.value)
        ownerGate(decision, caller.role)?.let { return AppResult.Failure(it) }

        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_NAME_LENGTH) {
            return AppResult.Failure(CollectionError.InvalidInput())
        }

        val existing = collectionRepo.findById(id.value) ?: return AppResult.Failure(CollectionError.NotFound())
        val updated = existing.copy(name = trimmed, updatedAt = clock.now().toEpochMilliseconds())
        val saved = collectionRepo.upsert(updated).getOrElse { return AppResult.Failure(it) }
        return AppResult.Success(summarize(saved, caller, decision))
    }

    override suspend fun deleteCollection(id: CollectionId): AppResult<Unit> {
        val caller = resolveCaller() ?: return noPrincipal()
        val decision = accessPolicy.decide(caller.userId, caller.role, id.value)
        ownerGate(decision, caller.role)?.let { return AppResult.Failure(it) }

        val collection = collectionRepo.findById(id.value) ?: return AppResult.Failure(CollectionError.NotFound())
        if (collection.isInbox) return AppResult.Failure(CollectionError.InboxNotDeletable())

        suspendTransaction(db) {
            collectionBookRepo.softDeleteAllForCollection(id.value)
            for (share in shareRepo.listActiveSharesForCollection(id.value)) {
                shareRepo.softDelete(share.id)
            }
            collectionRepo.softDelete(id.value)
        }
        return AppResult.Success(Unit)
    }

    override suspend fun addBookToCollection(
        id: CollectionId,
        bookId: BookId,
    ): AppResult<Unit> {
        val caller = resolveCaller() ?: return noPrincipal()
        val decision = accessPolicy.decide(caller.userId, caller.role, id.value)
        writeGate(decision)?.let { return AppResult.Failure(it) }

        if (!bookExists(bookId.value)) return AppResult.Failure(CollectionError.BookNotFound())

        val payload =
            CollectionBookSyncPayload(
                collectionId = id.value,
                bookId = bookId.value,
                createdAt = clock.now().toEpochMilliseconds(),
                revision = 0L,
                deletedAt = null,
            )
        return when (val result = collectionBookRepo.upsert(payload)) {
            is AppResult.Success -> {
                // Adding the book to this collection grants its visible users (owner + active-share
                // recipients) a new access path — nudge each to re-derive, matching setBookCollections.
                notifyAccessChanged(listOf(id.value))
                // Bump the book's revision so each member's incremental `revision > cursor` pull
                // re-delivers the now-visible book — the access nudge alone never carries the row.
                bookRevisionTouch.touchRevision(bookId)
                AppResult.Success(Unit)
            }
            is AppResult.Failure -> {
                AppResult.Failure(result.error)
            }
        }
    }

    override suspend fun removeBookFromCollection(
        id: CollectionId,
        bookId: BookId,
    ): AppResult<Unit> {
        val caller = resolveCaller() ?: return noPrincipal()
        val decision = accessPolicy.decide(caller.userId, caller.role, id.value)
        writeGate(decision)?.let { return AppResult.Failure(it) }

        // softDelete is idempotent: a Failure(NotFound) means the junction was already
        // absent, which satisfies the caller's intent — treat as success.
        collectionBookRepo.softDelete(collectionId = id.value, bookId = bookId.value)
        // Removing the book may have severed a visible user's only access path to it — nudge this
        // collection's visible users to re-derive (and prune if needed), matching setBookCollections.
        notifyAccessChanged(listOf(id.value))
        // Bump the book's revision so each member's incremental `revision > cursor` pull re-evaluates
        // its now-changed visibility and prunes it when no access path remains.
        bookRevisionTouch.touchRevision(bookId)
        return AppResult.Success(Unit)
    }

    override suspend fun setBookCollections(
        bookId: BookId,
        collectionIds: List<CollectionId>,
    ): AppResult<Unit> {
        val caller = resolveCaller() ?: return noPrincipal()
        adminGate(caller.role)?.let { return AppResult.Failure(it) }

        if (!bookExists(bookId.value)) return AppResult.Failure(CollectionError.BookNotFound())

        // Validate every distinct target up front — exists and live — before mutating:
        // a tombstoned target would otherwise be silently resurrected by upsert, and a
        // bad id would surface as an opaque FK violation mid-transaction.
        val targetIds = collectionIds.map { it.value }.toSet()
        for (targetId in targetIds) {
            collectionRepo.findById(targetId) ?: return AppResult.Failure(CollectionError.NotFound())
        }

        val current = collectionBookRepo.findCollectionIdsForBook(bookId.value).toSet()
        val added = targetIds - current
        val removed = current - targetIds

        suspendTransaction(db) {
            for (collectionId in removed) {
                collectionBookRepo.softDelete(collectionId = collectionId, bookId = bookId.value)
            }
            for (collectionId in added) {
                collectionBookRepo.upsert(
                    CollectionBookSyncPayload(
                        collectionId = collectionId,
                        bookId = bookId.value,
                        createdAt = clock.now().toEpochMilliseconds(),
                        revision = 0L,
                        deletedAt = null,
                    ),
                )
            }
        }

        // Changing the book's collection set changes who can see the book. Every enumerable user
        // whose access may have shifted — the visible users of each ADDED collection (they may have
        // gained the book) and each REMOVED collection (they may have lost their only access path) —
        // is nudged once to re-derive. The non-enumerable public↔private "everyone" edge converges on
        // the next firehose catch-up — the documented 1b behavior.
        notifyAccessChanged(added + removed)
        return AppResult.Success(Unit)
    }

    // ── Share mutation (owner-only) ─────────────────────────────────────────────

    override suspend fun shareCollection(
        id: CollectionId,
        sharedWithUserId: String,
        permission: SharePermission,
    ): AppResult<CollectionShareDto> {
        val caller = resolveCaller() ?: return noPrincipal()
        val decision = accessPolicy.decide(caller.userId, caller.role, id.value)
        ownerGate(decision, caller.role)?.let { return AppResult.Failure(it) }
        // canShare is an ADDITIONAL gate beyond ownership: a member must hold canShare AND own
        // (or admin-bypass) the collection to share it. ROOT/ADMIN pass the flag implicitly.
        permissionPolicy.requireCanShare(UserId(caller.userId), caller.role.toContract())
            ?.let { return AppResult.Failure(it) }

        if (sharedWithUserId == caller.userId) return AppResult.Failure(CollectionError.SelfShare())
        if (!userExists(sharedWithUserId)) return AppResult.Failure(CollectionError.UserNotFound())
        if (shareRepo.findActiveShare(id.value, sharedWithUserId) != null) {
            return AppResult.Failure(CollectionError.AlreadyShared())
        }

        val payload =
            CollectionShareSyncPayload(
                id = UUID.randomUUID().toString(),
                collectionId = id.value,
                sharedWithUserId = sharedWithUserId,
                sharedByUserId = caller.userId,
                permission = permission,
                revision = 0L,
                updatedAt = clock.now().toEpochMilliseconds(),
                deletedAt = null,
            )
        return when (val result = shareRepo.upsert(payload)) {
            is AppResult.Success -> {
                // The newly-shared user's accessible set just grew — tell them to re-derive.
                bus.publishControl(SyncControl.AccessChanged, sharedWithUserId)
                AppResult.Success(result.data.toDto())
            }
            is AppResult.Failure -> {
                AppResult.Failure(result.error)
            }
        }
    }

    override suspend fun updateShare(
        id: CollectionId,
        sharedWithUserId: String,
        permission: SharePermission,
    ): AppResult<CollectionShareDto> {
        val caller = resolveCaller() ?: return noPrincipal()
        val decision = accessPolicy.decide(caller.userId, caller.role, id.value)
        ownerGate(decision, caller.role)?.let { return AppResult.Failure(it) }

        val existing =
            shareRepo.findActiveShare(id.value, sharedWithUserId)
                ?: return AppResult.Failure(CollectionError.NotFound())

        val updated = existing.copy(permission = permission, updatedAt = clock.now().toEpochMilliseconds())
        return when (val result = shareRepo.upsert(updated)) {
            is AppResult.Success -> {
                // The share's permission changed — the recipient must re-derive what they can do.
                bus.publishControl(SyncControl.AccessChanged, sharedWithUserId)
                AppResult.Success(result.data.toDto())
            }
            is AppResult.Failure -> {
                AppResult.Failure(result.error)
            }
        }
    }

    override suspend fun revokeShare(
        id: CollectionId,
        sharedWithUserId: String,
    ): AppResult<Unit> {
        val caller = resolveCaller() ?: return noPrincipal()
        val decision = accessPolicy.decide(caller.userId, caller.role, id.value)
        ownerGate(decision, caller.role)?.let { return AppResult.Failure(it) }

        // softDeleteShare returns Failure(NotFound) when no live share exists; revoke is
        // idempotent — a no-op revoke satisfies the caller's intent. Only nudge the ex-target
        // when a live share was actually removed: a no-op revoke didn't change their access.
        if (shareRepo.softDeleteShare(id.value, sharedWithUserId) is AppResult.Success) {
            bus.publishControl(SyncControl.AccessChanged, sharedWithUserId)
        }
        return AppResult.Success(Unit)
    }

    override suspend fun listShares(id: CollectionId): AppResult<List<CollectionShareDto>> {
        val caller = resolveCaller() ?: return noPrincipal()
        val decision = accessPolicy.decide(caller.userId, caller.role, id.value)
        ownerGate(decision, caller.role)?.let { return AppResult.Failure(it) }

        val shares = shareRepo.listActiveSharesForCollection(id.value).map { it.toDto() }
        return AppResult.Success(shares)
    }

    // ── Inbox (system collection + release flow) ──────────────────────────────────
    //
    // These are ADMIN-INTERNAL operations, deliberately NOT part of the @Rpc
    // CollectionService contract (frozen at 12 user-facing methods). They are exposed as
    // public methods so the admin REST routes (and, eventually, the scanner) can call them.

    /**
     * Resolves the library's inbox, creating it on first use.
     *
     * The inbox is a per-library SYSTEM collection (`isInbox = true`, never globally
     * accessible, not deletable). It is created lazily: the first call materialises it,
     * subsequent calls return the same row. Idempotency rests on
     * [CollectionRepository.findInboxForLibrary] plus the `idx_collections_inbox` partial
     * unique index that guarantees at most one live inbox per library.
     *
     * **Owner resolution.** The inbox owner is the library's `createdByUserId` when set
     * (forward-staged for the multi-user phase; null today), otherwise the first ROOT user,
     * otherwise the first ADMIN user. If the server has no admin at all the inbox cannot be
     * attributed and we fail with [CollectionError.InvalidInput] rather than orphan it.
     *
     * This is a system operation: it does not require or consult a caller principal.
     */
    suspend fun getOrCreateInbox(libraryId: String): AppResult<CollectionSummary> {
        collectionRepo.findInboxForLibrary(libraryId)?.let { return summarizeSystem(it) }

        val ownerId = resolveInboxOwner(libraryId) ?: return AppResult.Failure(
            CollectionError.InvalidInput(debugInfo = "No admin user available to own the inbox for library $libraryId"),
        )

        val payload =
            CollectionSyncPayload(
                id = UUID.randomUUID().toString(),
                libraryId = libraryId,
                ownerId = ownerId,
                name = "Inbox",
                isInbox = true,
                isGlobalAccess = false,
                revision = 0L,
                updatedAt = clock.now().toEpochMilliseconds(),
            )
        return when (val result = collectionRepo.upsert(payload)) {
            is AppResult.Success -> summarizeSystem(result.data)
            is AppResult.Failure -> AppResult.Failure(result.error)
        }
    }

    /**
     * Adds [bookId] to the library's inbox, resolving (or creating) the inbox first.
     *
     * A deliberate admin/system action — used by the admin REST routes to quarantine a
     * book for triage. It is unconditional: callers decide when to invoke it.
     *
     * **Distinct from the scan hook.** Scan-time auto-quarantine now lands inbox membership
     * *atomically* in the book-insert transaction (see
     * [com.calypsan.listenup.server.services.BookPersister] +
     * [com.calypsan.listenup.server.services.BookRepository], gated on `library.inboxEnabled`),
     * so a new book never has a momentarily-public window. This `addToInbox` is the separate
     * *deliberate admin action* for an already-public book: the firehose evaluates
     * `BookAccessPolicy.canAccess` at delivery, so the book is hidden from members as soon as
     * the membership commits — the action *reduces* its reach rather than exposing hidden content.
     *
     * A system operation (no caller principal); the book must exist
     * ([CollectionError.BookNotFound]).
     */
    suspend fun addToInbox(
        bookId: String,
        libraryId: String,
    ): AppResult<Unit> {
        if (!bookExists(bookId)) return AppResult.Failure(CollectionError.BookNotFound())

        val inbox = getOrCreateInbox(libraryId).getOrElse { return AppResult.Failure(it) }

        val payload =
            CollectionBookSyncPayload(
                collectionId = inbox.id.value,
                bookId = bookId,
                createdAt = clock.now().toEpochMilliseconds(),
                revision = 0L,
                deletedAt = null,
            )
        return when (val result = collectionBookRepo.upsert(payload)) {
            is AppResult.Success -> AppResult.Success(Unit)
            is AppResult.Failure -> AppResult.Failure(result.error)
        }
    }

    /**
     * Releases books out of the library's inbox into their assigned target collections.
     *
     * [assignments] maps `bookId → target collectionIds`. For each book the inbox junction
     * is soft-deleted, then a junction is added for every target collection. A book with an
     * empty target list is simply removed from the inbox and becomes uncollected. The whole
     * release runs in a single transaction so a partial failure does not strand books
     * half-released.
     *
     * Admin-only ([CollectionError.Forbidden] otherwise); requires a caller principal.
     */
    suspend fun releaseBooks(
        libraryId: String,
        assignments: Map<String, List<String>>,
    ): AppResult<Unit> {
        val caller = resolveCaller() ?: return noPrincipal()
        adminGate(caller.role)?.let { return AppResult.Failure(it) }

        val inbox = getOrCreateInbox(libraryId).getOrElse { return AppResult.Failure(it) }
        val inboxId = inbox.id.value

        // Validate every distinct target collection up front — exists, live, same library —
        // before mutating anything: a bad id otherwise surfaces as an opaque FK violation, and
        // a tombstoned target would be silently resurrected by upsert. Validating before the
        // transaction keeps the whole release atomic and the error typed.
        val distinctTargetIds = assignments.values.flatten().toSet()
        for (targetId in distinctTargetIds) {
            val target =
                collectionRepo.findById(targetId)
                    ?: return AppResult.Failure(CollectionError.NotFound())
            if (target.libraryId != libraryId) {
                return AppResult.Failure(
                    CollectionError.InvalidInput(debugInfo = "Target collection $targetId is in a different library"),
                )
            }
        }

        suspendTransaction(db) {
            for ((bookId, targetCollectionIds) in assignments) {
                collectionBookRepo.softDelete(collectionId = inboxId, bookId = bookId)
                for (targetId in targetCollectionIds) {
                    collectionBookRepo.upsert(
                        CollectionBookSyncPayload(
                            collectionId = targetId,
                            bookId = bookId,
                            createdAt = clock.now().toEpochMilliseconds(),
                            revision = 0L,
                            deletedAt = null,
                        ),
                    )
                }
            }
        }

        // Every user who can see a target collection just gained access to the released books —
        // nudge each once to re-derive.
        notifyAccessChanged(distinctTargetIds)
        return AppResult.Success(Unit)
    }

    /**
     * Publishes a per-user [SyncControl.AccessChanged] nudge to every enumerable user whose
     * visibility may have shifted because the membership of [collectionIds] changed — each
     * collection's owner plus its active-share recipients. The single emission contract shared
     * by every visibility-mutating method (add/remove book, [setBookCollections], [releaseBooks]).
     *
     * A nudged member re-derives their view and prunes any book they can no longer reach — the
     * gated-out book/junction sync events alone would never tell them. Admins see everything, so
     * they need no signal; the non-enumerable public↔private "everyone" edge converges on the next
     * firehose catch-up (the documented 1b behavior).
     */
    private suspend fun notifyAccessChanged(collectionIds: Collection<String>) {
        val affectedUserIds =
            collectionIds
                .flatMap { collectionId ->
                    val owner = collectionRepo.findById(collectionId)?.ownerId
                    val shareUsers = shareRepo.listActiveSharesForCollection(collectionId).map { it.sharedWithUserId }
                    if (owner != null) shareUsers + owner else shareUsers
                }.toSet()
        for (affectedUserId in affectedUserIds) {
            bus.publishControl(SyncControl.AccessChanged, affectedUserId)
        }
    }

    /**
     * Returns the live book ids in the library's inbox, or an empty list when no inbox
     * exists yet — a read must never auto-create one.
     *
     * Admin-only ([CollectionError.Forbidden] otherwise); requires a caller principal.
     */
    suspend fun listInbox(libraryId: String): AppResult<List<BookId>> {
        val caller = resolveCaller() ?: return noPrincipal()
        adminGate(caller.role)?.let { return AppResult.Failure(it) }

        val inbox = collectionRepo.findInboxForLibrary(libraryId) ?: return AppResult.Success(emptyList())
        val bookIds = collectionBookRepo.findBookIdsForCollection(inbox.id).map { BookId(it) }
        return AppResult.Success(bookIds)
    }

    // ── Principal binding ───────────────────────────────────────────────────────

    /** Returns a copy scoped to the given [principal]. Route handlers call this per-request. */
    fun copyWith(principal: PrincipalProvider): CollectionServiceImpl =
        CollectionServiceImpl(
            collectionRepo = collectionRepo,
            collectionBookRepo = collectionBookRepo,
            shareRepo = shareRepo,
            accessPolicy = accessPolicy,
            permissionPolicy = permissionPolicy,
            bus = bus,
            db = db,
            clock = clock,
            bookRevisionTouch = bookRevisionTouch,
            principal = principal,
        )

    // ── Private helpers ─────────────────────────────────────────────────────────

    /** The resolved caller: their user id and the DB-enum role the policy expects. */
    private data class Caller(
        val userId: String,
        val role: UserRoleColumn,
    )

    private fun resolveCaller(): Caller? = principal.current()?.let { Caller(it.userId.value, it.role.toColumn()) }

    private fun noPrincipal(): AppResult.Failure = AppResult.Failure(CollectionError.NotFound())

    /**
     * Owner-only gate: null = allowed (owner or admin); [CollectionError.Forbidden] if the
     * caller can see the collection but is neither owner nor admin; [CollectionError.NotFound]
     * if they can't see it at all (don't leak existence).
     *
     * A write-share recipient holds `canWrite` but is not an owner — so ownership can't be
     * inferred from the [decision] alone; admin status is read from [role] directly.
     */
    private fun ownerGate(
        decision: CollectionAccessPolicy.Decision,
        role: UserRoleColumn,
    ): CollectionError? =
        when {
            decision.isOwner -> null
            role == UserRoleColumn.ROOT || role == UserRoleColumn.ADMIN -> null
            decision.canAccess -> CollectionError.Forbidden()
            else -> CollectionError.NotFound()
        }

    /** Admin gate: null = allowed (ROOT/ADMIN); [CollectionError.Forbidden] for everyone else. */
    private fun adminGate(role: UserRoleColumn): CollectionError? =
        if (role == UserRoleColumn.ROOT || role == UserRoleColumn.ADMIN) null else CollectionError.Forbidden()

    /**
     * Resolves the user id that should own the library's inbox: the library's
     * `createdByUserId` if set, else the first ROOT user, else the first ADMIN user,
     * else null when the server has no admin at all.
     */
    private suspend fun resolveInboxOwner(libraryId: String): String? =
        suspendTransaction(db) {
            LibraryTable
                .selectAll()
                .where { LibraryTable.id eq libraryId }
                .firstOrNull()
                ?.get(LibraryTable.createdByUserId)
                ?: firstUserWithRole(UserRoleColumn.ROOT)
                ?: firstUserWithRole(UserRoleColumn.ADMIN)
        }

    /** First user id whose role matches [role], or null. Must run inside a transaction. */
    private fun firstUserWithRole(role: UserRoleColumn): String? =
        UserTable
            .selectAll()
            .where { UserTable.role eq role }
            .firstOrNull()
            ?.get(UserTable.id)
            ?.value

    /** Write gate: null = allowed; Forbidden if the caller can read but not write; NotFound otherwise. */
    private fun writeGate(decision: CollectionAccessPolicy.Decision): CollectionError? =
        when {
            decision.canAccess && decision.permission.canWrite() -> null
            decision.canAccess -> CollectionError.Forbidden()
            else -> CollectionError.NotFound()
        }

    private suspend fun summarize(
        collection: CollectionSyncPayload,
        caller: Caller,
        decision: CollectionAccessPolicy.Decision? = null,
    ): CollectionSummary {
        val verdict = decision ?: accessPolicy.decide(caller.userId, caller.role, collection.id)
        return CollectionSummary(
            id = CollectionId(collection.id),
            name = collection.name,
            ownerId = UserId(collection.ownerId),
            isInbox = collection.isInbox,
            isGlobalAccess = collection.isGlobalAccess,
            bookCount = collectionBookRepo.countLiveForCollection(collection.id),
            callerPermission = verdict.permission,
            isOwner = verdict.isOwner,
        )
    }

    /**
     * Summarises a system collection (the inbox) without a caller context.
     *
     * The inbox is owned by an admin and managed through admin operations, so the summary
     * reflects the owning admin's view: [CollectionSummary.isOwner] true,
     * [CollectionSummary.callerPermission] Write.
     */
    private suspend fun summarizeSystem(collection: CollectionSyncPayload): AppResult<CollectionSummary> =
        AppResult.Success(
            CollectionSummary(
                id = CollectionId(collection.id),
                name = collection.name,
                ownerId = UserId(collection.ownerId),
                isInbox = collection.isInbox,
                isGlobalAccess = collection.isGlobalAccess,
                bookCount = collectionBookRepo.countLiveForCollection(collection.id),
                callerPermission = SharePermission.Write,
                isOwner = true,
            ),
        )

    private suspend fun bookExists(bookId: String): Boolean =
        suspendTransaction(db) {
            BookTable
                .selectAll()
                .where { (BookTable.id eq bookId) and BookTable.deletedAt.isNull() }
                .count() > 0
        }

    private suspend fun userExists(userId: String): Boolean =
        suspendTransaction(db) {
            UserTable
                .selectAll()
                .where { UserTable.id eq userId }
                .count() > 0
        }

    private fun CollectionShareSyncPayload.toDto(): CollectionShareDto =
        CollectionShareDto(
            id = id,
            collectionId = CollectionId(collectionId),
            sharedWithUserId = UserId(sharedWithUserId),
            permission = permission,
        )

    private suspend fun CollectionRepository.softDelete(id: String): AppResult<Unit> = softDelete(id, clientOpId = null)

    private suspend fun CollectionShareRepository.softDelete(id: String): AppResult<Unit> =
        softDelete(id, clientOpId = null)
}

/**
 * Constructs a [CollectionService] backed by [CollectionServiceImpl]. Public so cross-module
 * test harnesses (e.g. `:sharedLogic:jvmTest`'s `WithCollectionSyncEngineAgainstServer`) can
 * build the service without depending on the Koin graph or piercing the `internal` access on
 * [CollectionServiceImpl] / [CollectionAccessPolicy]. Production wiring continues to construct
 * the impl directly inside the books Koin module.
 *
 * Returns the concrete [CollectionService] interface; scope it per caller with
 * [collectionServiceScopedTo] before driving owner-only operations (create/share/revoke).
 */
fun createCollectionService(
    collectionRepo: CollectionRepository,
    collectionBookRepo: CollectionBookRepository,
    shareRepo: CollectionShareRepository,
    bus: ChangeBus,
    db: Database,
    bookRevisionTouch: BookRevisionTouch,
    clock: Clock = Clock.System,
): CollectionService =
    CollectionServiceImpl(
        collectionRepo = collectionRepo,
        collectionBookRepo = collectionBookRepo,
        shareRepo = shareRepo,
        accessPolicy = CollectionAccessPolicy(collectionRepo, shareRepo),
        permissionPolicy = UserPermissionPolicy(db),
        bus = bus,
        db = db,
        clock = clock,
        bookRevisionTouch = bookRevisionTouch,
        principal = PrincipalProvider { error("Unscoped CollectionService — call collectionServiceScopedTo") },
    )

/**
 * Scopes a [CollectionService] built by [createCollectionService] to [principal] for one caller.
 * Public so cross-module test harnesses can bind the authenticated caller without piercing the
 * `internal` access on [CollectionServiceImpl.copyWith].
 */
fun collectionServiceScopedTo(
    service: CollectionService,
    principal: PrincipalProvider,
): CollectionService = (service as CollectionServiceImpl).copyWith(principal)
