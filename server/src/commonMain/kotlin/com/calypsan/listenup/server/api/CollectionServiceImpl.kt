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
import com.calypsan.listenup.api.sync.AccessScope
import com.calypsan.listenup.api.sync.CollectionShareSyncPayload
import com.calypsan.listenup.api.sync.CollectionSyncPayload
import com.calypsan.listenup.api.sync.SyncControl
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.CollectionId
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPermissionPolicy
import com.calypsan.listenup.server.auth.toColumn
import com.calypsan.listenup.server.auth.toContract
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.services.BookRevisionTouch
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.CollectionBookRepository
import com.calypsan.listenup.server.sync.CollectionGrantRepository
import com.calypsan.listenup.server.sync.CollectionRepository
import kotlin.uuid.Uuid
import kotlin.time.Clock

private const val LIST_BOOKS_MIN = 1
private const val LIST_BOOKS_MAX = 1000
private const val MAX_NAME_LENGTH = 200

/**
 * The largest scoped [AccessChanged][SyncControl.AccessChanged] delta the server will emit. Above
 * it — too many affected collections or books — the frame degrades to coarse (scope = null) and the
 * client re-derives its whole accessible library, which is cheaper than a huge targeted fetch. 200
 * comfortably covers every realistic single collection mutation while capping the pathological case.
 */
internal const val DELTA_MAX_BOOKS = 200

/**
 * Builds the recipient-agnostic [AccessScope] naming the entities an access change touched, or
 * `null` when the change is too large to delta ([maxBooks]) — the explicit, never-silent fallback to
 * a coarse re-derive. A pure function of the affected ids, so the emission sites can build the scope
 * without any per-user computation and a unit test can pin the threshold directly.
 */
internal fun accessScopeFor(
    collectionIds: Collection<String>,
    bookIds: Collection<String>,
    maxBooks: Int = DELTA_MAX_BOOKS,
): AccessScope? {
    val cols = collectionIds.toSet()
    val books = bookIds.toSet()
    if (cols.size > maxBooks || books.size > maxBooks) return null
    return AccessScope(collectionIds = cols.toList(), bookIds = books.toList())
}

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
 * gated through [ownerGate] like rename/delete. System collections (ALL_BOOKS, INBOX) reject
 * all three share mutations with [CollectionError.SystemCollectionReadOnly]; the default
 * ALL_BOOKS grants are managed exclusively by [DefaultAllBooksGrantIssuer]. The per-user
 * `AccessChanged` reconcile signal on share/unshare is Collections-1b (it depends on the
 * book-visibility layer); 1a just persists the share rows and emits the normal sync events.
 */
internal class CollectionServiceImpl(
    private val collectionRepo: CollectionRepository,
    private val collectionBookRepo: CollectionBookRepository,
    private val grantRepo: CollectionGrantRepository,
    private val accessPolicy: CollectionAccessPolicy,
    private val permissionPolicy: UserPermissionPolicy,
    private val bus: ChangeBus,
    private val sql: ListenUpDatabase,
    private val clock: Clock = Clock.System,
    private val bookRevisionTouch: BookRevisionTouch,
    private val principal: PrincipalProvider,
) : CollectionService {
    // ── Observation ─────────────────────────────────────────────────────────

    override suspend fun listCollections(): AppResult<List<CollectionSummary>> {
        val caller = resolveCaller() ?: return noPrincipal()

        val collections =
            if (caller.role == UserRoleColumn.ROOT || caller.role == UserRoleColumn.ADMIN) {
                // Admin god-view: all collections including system ones (ALL_BOOKS, INBOX).
                collectionRepo.listAll()
            } else {
                val owned = collectionRepo.listOwnedBy(caller.userId)
                val sharedIds = grantRepo.listActiveGrantsForUser(caller.userId).map { it.collectionId }
                val shared = sharedIds.mapNotNull { collectionRepo.findById(it) }
                // Spec §3.2: ALL_BOOKS and INBOX must not appear in a member's collection list.
                // Every member holds a default ALL_BOOKS grant, so the shared path leaks it;
                // filter the combined result by the set of live system-collection ids.
                val systemIds = collectionRepo.systemCollectionIds()
                (owned + shared).distinctBy { it.id }.filterNot { it.id in systemIds }
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
                id = Uuid.random().toString(),
                libraryId = libraryId,
                ownerId = caller.userId,
                name = trimmed,
                isInbox = false,
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
        val systemIds = collectionRepo.systemCollectionIds()
        if (id.value in systemIds) return AppResult.Failure(CollectionError.SystemCollectionReadOnly())

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

        if (collectionRepo.findById(id.value) == null) return AppResult.Failure(CollectionError.NotFound())
        val systemIds = collectionRepo.systemCollectionIds()
        if (id.value in systemIds) return AppResult.Failure(CollectionError.SystemCollectionReadOnly())

        // Cascade: tombstone the membership rows, every active grant, then the collection. Each is
        // a suspend repo call that opens its own SQLDelight transaction, so they run sequentially
        // (they cannot nest inside a non-suspend SQLDelight transaction body). Sequential
        // single-engine writes never contend for the lone SQLite write lock.
        // Capture the live members BEFORE the cascade so we can re-home any book that loses its
        // last real membership: deleting a collection must never strand a book with zero memberships.
        val affectedBookIds = collectionBookRepo.findBookIdsForCollection(id.value)
        // S2: capture the collection's audience (owner + active grant recipients) and the scoped
        // delta BEFORE the cascade — while the grants are still live — but PUBLISH the frame AFTER
        // the cascade commits. Staging the audience keeps the "loses access in real time" guarantee
        // for a member who could reach these books only via this collection; deferring the publish
        // fixes the pre-existing race where the frame raced ahead of the membership/revision writes
        // it points at, so a fast client could fetch stale rows.
        val audience = accessChangedAudience(listOf(id.value))
        val scope = accessScopeFor(listOf(id.value), affectedBookIds)
        collectionBookRepo.softDeleteAllForCollection(id.value)
        // A book whose only membership was this collection returns to ALL_BOOKS (reconcile bumps
        // its revision + nudges ALL_BOOKS grant-holders so members re-derive the now-public book).
        // Every affected book's revision is touched regardless, so a member whose access just changed
        // re-derives visibility on their incremental `revision > cursor` pull (a book that stays in
        // other collections isn't reconciled but must still drop for this collection's lost audience).
        for (bookId in affectedBookIds) {
            reconcileSystemMembership(bookId)
            bookRevisionTouch.touchRevision(BookId(bookId))
        }
        for (grant in grantRepo.listActiveGrantsForCollection(id.value)) {
            grantRepo.softDelete(grant.id)
        }
        collectionRepo.softDelete(id.value)
        // Cascade committed — now nudge the staged audience so no frame outruns its writes.
        if (audience.isNotEmpty()) publishAccessChanged(audience, scope)
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
                notifyAccessChanged(listOf(id.value), listOf(bookId.value))
                // Bump the book's revision so each member's incremental `revision > cursor` pull
                // re-delivers the now-visible book — the access nudge alone never carries the row.
                bookRevisionTouch.touchRevision(bookId)
                // First real membership ⇒ the book leaves the everyone-visible ALL_BOOKS substrate.
                // Adding directly to a system collection (ALL_BOOKS/INBOX) is a managed action, not
                // reconciled — reconcile there would immediately undo the deliberate placement.
                if (id.value !in collectionRepo.systemCollectionIds()) {
                    reconcileSystemMembership(bookId.value)
                }
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
        notifyAccessChanged(listOf(id.value), listOf(bookId.value))
        // Bump the book's revision so each member's incremental `revision > cursor` pull re-evaluates
        // its now-changed visibility and prunes it when no access path remains.
        bookRevisionTouch.touchRevision(bookId)
        // Removing the last real membership ⇒ the book returns to ALL_BOOKS (never stranded).
        reconcileSystemMembership(bookId.value)
        return AppResult.Success(Unit)
    }

    override suspend fun setBookCollections(
        bookId: BookId,
        collectionIds: List<CollectionId>,
    ): AppResult<Unit> {
        val caller = resolveCaller() ?: return noPrincipal()
        adminGate(caller.role)?.let { return AppResult.Failure(it) }

        if (!bookExists(bookId.value)) return AppResult.Failure(CollectionError.BookNotFound())

        // System collections (ALL_BOOKS, INBOX) are server-managed — a client must never NAME one
        // as a target. Exclude system ids from the caller-supplied set and from the diff so this
        // replace-set only touches NORMAL memberships; ALL_BOOKS is then maintained by
        // reconcileSystemMembership below (derived from the resulting real set), never named here.
        val systemIds = collectionRepo.systemCollectionIds()

        // Validate every distinct target up front — exists and live — before mutating:
        // a tombstoned target would otherwise be silently resurrected by upsert, and a
        // bad id would surface as an opaque FK violation mid-transaction.
        val targetIds = collectionIds.map { it.value }.toSet() - systemIds
        for (targetId in targetIds) {
            collectionRepo.findById(targetId) ?: return AppResult.Failure(CollectionError.NotFound())
        }

        val current = collectionBookRepo.findCollectionIdsForBook(bookId.value).toSet() - systemIds
        val added = targetIds - current
        val removed = current - targetIds

        // Sequential suspend repo writes — each opens its own SQLDelight transaction, so they
        // cannot nest inside a non-suspend SQLDelight transaction body.
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

        // Changing the book's collection set changes who can see the book. Every enumerable user
        // whose access may have shifted — the visible users of each ADDED collection (they may have
        // gained the book) and each REMOVED collection (they may have lost their only access path) —
        // is nudged once to re-derive. The non-enumerable public↔private "everyone" edge converges on
        // the next firehose catch-up — the documented 1b behavior.
        notifyAccessChanged(added + removed, listOf(bookId.value))
        // Bump the subject book's revision once when its membership actually changed, so each member's
        // incremental `revision > cursor` pull re-evaluates its visibility (delivering or pruning it).
        if (added.isNotEmpty() || removed.isNotEmpty()) {
            bookRevisionTouch.touchRevision(bookId)
        }
        // Derive ALL_BOOKS from the resulting real set: in ALL_BOOKS iff no real membership remains
        // (and not held in INBOX). This drops a curated book out of the everyone-collection (the
        // #680/#730 leak) and re-homes an empty target set into ALL_BOOKS instead of orphaning it.
        reconcileSystemMembership(bookId.value)
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
        val systemIds = collectionRepo.systemCollectionIds()
        if (id.value in systemIds) return AppResult.Failure(CollectionError.SystemCollectionReadOnly())
        // canShare is an ADDITIONAL gate beyond ownership: a member must hold canShare AND own
        // (or admin-bypass) the collection to share it. ROOT/ADMIN pass the flag implicitly.
        permissionPolicy
            .requireCanShare(UserId(caller.userId), caller.role.toContract())
            ?.let { return AppResult.Failure(it) }

        if (sharedWithUserId == caller.userId) return AppResult.Failure(CollectionError.SelfShare())
        if (!userExists(sharedWithUserId)) return AppResult.Failure(CollectionError.UserNotFound())
        if (grantRepo.findActiveGrant(id.value, sharedWithUserId) != null) {
            return AppResult.Failure(CollectionError.AlreadyShared())
        }

        val payload =
            CollectionShareSyncPayload(
                id = Uuid.random().toString(),
                collectionId = id.value,
                sharedWithUserId = sharedWithUserId,
                sharedByUserId = caller.userId,
                permission = permission,
                revision = 0L,
                updatedAt = clock.now().toEpochMilliseconds(),
                deletedAt = null,
            )
        return when (val result = grantRepo.upsert(payload)) {
            is AppResult.Success -> {
                // The newly-shared user's accessible set just grew — tell them to re-derive, scoped
                // to this collection and the books it currently holds (the ones they just gained).
                publishAccessChanged(
                    setOf(sharedWithUserId),
                    accessScopeFor(listOf(id.value), collectionBookRepo.findBookIdsForCollection(id.value)),
                )
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
        val systemIds = collectionRepo.systemCollectionIds()
        if (id.value in systemIds) return AppResult.Failure(CollectionError.SystemCollectionReadOnly())

        val existing =
            grantRepo.findActiveGrant(id.value, sharedWithUserId)
                ?: return AppResult.Failure(CollectionError.NotFound())

        val updated = existing.copy(permission = permission, updatedAt = clock.now().toEpochMilliseconds())
        return when (val result = grantRepo.upsert(updated)) {
            is AppResult.Success -> {
                // The share's permission changed — the recipient must re-derive what they can do,
                // scoped to this collection and its current books.
                publishAccessChanged(
                    setOf(sharedWithUserId),
                    accessScopeFor(listOf(id.value), collectionBookRepo.findBookIdsForCollection(id.value)),
                )
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
        val systemIds = collectionRepo.systemCollectionIds()
        if (id.value in systemIds) return AppResult.Failure(CollectionError.SystemCollectionReadOnly())

        // softDeleteGrant returns Failure(NotFound) when no live grant exists; revoke is
        // idempotent — a no-op revoke satisfies the caller's intent. Only nudge the ex-target
        // when a live grant was actually removed: a no-op revoke didn't change their access.
        if (grantRepo.softDeleteGrant(id.value, sharedWithUserId) is AppResult.Success) {
            // The ex-target lost this grant — scope the re-derive to this collection and the books
            // it holds (the ones they may have just lost; per-user truth resolves at fetch time).
            publishAccessChanged(
                setOf(sharedWithUserId),
                accessScopeFor(listOf(id.value), collectionBookRepo.findBookIdsForCollection(id.value)),
            )
        }
        return AppResult.Success(Unit)
    }

    override suspend fun listShares(id: CollectionId): AppResult<List<CollectionShareDto>> {
        val caller = resolveCaller() ?: return noPrincipal()
        val decision = accessPolicy.decide(caller.userId, caller.role, id.value)
        ownerGate(decision, caller.role)?.let { return AppResult.Failure(it) }

        val shares = grantRepo.listActiveGrantsForCollection(id.value).map { it.toDto() }
        return AppResult.Success(shares)
    }

    // ── Inbox (system collection + release flow) ──────────────────────────────────
    //
    // These are ADMIN-INTERNAL operations, deliberately NOT part of the @Rpc
    // CollectionService contract (frozen at 12 user-facing methods). They are exposed as
    // public methods so the admin REST routes (and potentially the scanner) can call them.

    /**
     * Resolves the library's per-library system collection of [type], creating it on first use.
     *
     * A system collection (owned by [SYSTEM_OWNER_ID]) is identified by
     * the server-only `collections.type` column ([SystemCollectionType.name]) — never on the wire.
     * It is created lazily: the first call materialises it, subsequent calls return the same row.
     * Idempotency rests on [CollectionRepository.findSystemCollection], backstopped at the DB by a
     * per-type partial unique index — `idx_collections_inbox` (one live INBOX per library) and
     * `idx_collections_all_books` (one live ALL_BOOKS per library) — so a concurrent first-call
     * cannot create a duplicate system collection.
     *
     * The row is materialised through the normal [CollectionRepository.upsert] path (which never
     * sees `type` — it's not a payload field), then [CollectionRepository.setType] stamps the
     * server-only column with no revision bump or publish.
     *
     * **Owner.** System collections are owned by the [SYSTEM_OWNER_ID] sentinel — a fixed string
     * that is never inserted into `users.id`. This means creation succeeds before any admin user
     * exists (e.g. at library bootstrap). [CollectionAccessPolicy] grants owner-write via
     * `coll.ownerId == userId`; with the sentinel owner no real user matches that branch.
     * Admins reach system collections through the god-view null-filter path.
     *
     * This is a system operation: it does not require or consult a caller principal.
     */
    suspend fun getOrCreateSystemCollection(
        libraryId: String,
        type: SystemCollectionType,
    ): AppResult<CollectionSummary> {
        collectionRepo.findSystemCollection(libraryId, type.name)?.let { return summarizeSystem(it) }

        val payload =
            CollectionSyncPayload(
                id = Uuid.random().toString(),
                libraryId = libraryId,
                ownerId = SYSTEM_OWNER_ID,
                name = if (type == SystemCollectionType.ALL_BOOKS) "All Books" else "Inbox",
                isInbox = type == SystemCollectionType.INBOX,
                revision = 0L,
                updatedAt = clock.now().toEpochMilliseconds(),
            )
        return when (val result = collectionRepo.upsert(payload)) {
            is AppResult.Success -> {
                collectionRepo.setType(result.data.id, type.name)
                // Re-read after setType so that isInbox is derived from the freshly-stamped
                // type column (writePayload does not write is_inbox; isInbox is a projection of type).
                val refreshed = collectionRepo.findById(result.data.id) ?: result.data
                summarizeSystem(refreshed)
            }

            is AppResult.Failure -> {
                AppResult.Failure(result.error)
            }
        }
    }

    /**
     * Resolves the library's inbox, creating it on first use.
     *
     * Delegates to [getOrCreateSystemCollection] with [SystemCollectionType.INBOX] — the inbox is
     * a per-library system collection (`isInbox = true`, never globally accessible, not deletable).
     */
    suspend fun getOrCreateInbox(libraryId: String): AppResult<CollectionSummary> =
        getOrCreateSystemCollection(libraryId, SystemCollectionType.INBOX)

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
     * **empty** target list is released to `ALL_BOOKS` — the library's public substrate
     * collection — so it stays visible to every member under the pure-union visibility rule.
     * (Under that rule "uncollected" means *invisible*, so approving a held book into no
     * collection would silently hide it; releasing into `ALL_BOOKS` is how a book becomes
     * public.) The whole release runs in a single transaction so a partial failure does not
     * strand books half-released.
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

        // Books released with no explicit target join ALL_BOOKS (the public substrate). Resolve
        // (or create) it once, up front, only when at least one book needs it — keeping the
        // common "release into explicit collections" path free of system-collection lookups.
        val needsAllBooks = assignments.values.any { it.isEmpty() }
        val allBooksId =
            if (needsAllBooks) {
                getOrCreateSystemCollection(libraryId, SystemCollectionType.ALL_BOOKS)
                    .getOrElse { return AppResult.Failure(it) }
                    .id.value
            } else {
                null
            }

        // Validate every distinct target collection up front — exists, live, same library —
        // before mutating anything: a bad id otherwise surfaces as an opaque FK violation, and
        // a tombstoned target would be silently resurrected by upsert. Validating before the
        // transaction keeps the whole release atomic and the error typed.
        val explicitTargetIds = assignments.values.flatten().toSet()
        for (targetId in explicitTargetIds) {
            val target =
                collectionRepo.findById(targetId)
                    ?: return AppResult.Failure(CollectionError.NotFound())
            if (target.libraryId != libraryId) {
                return AppResult.Failure(
                    CollectionError.InvalidInput(debugInfo = "Target collection $targetId is in a different library"),
                )
            }
        }

        // Sequential suspend repo writes — each opens its own SQLDelight transaction, so they
        // cannot nest inside a non-suspend SQLDelight transaction body. The pre-flight validation
        // above kept the release typed; a partial failure here is the same risk the prior outer
        // Exposed transaction carried, since it never took the SQLite write lock anyway.
        for ((bookId, targetCollectionIds) in assignments) {
            collectionBookRepo.softDelete(collectionId = inboxId, bookId = bookId)
            // Empty target → release to ALL_BOOKS so the book stays publicly visible.
            val resolvedTargets = targetCollectionIds.ifEmpty { listOfNotNull(allBooksId) }
            for (targetId in resolvedTargets) {
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

        // Every user who can see a target collection just gained access to the released books —
        // nudge each once to re-derive. ALL_BOOKS is included so its grant recipients re-derive.
        notifyAccessChanged(explicitTargetIds + listOfNotNull(allBooksId), assignments.keys)
        // Bump each released book's revision so members' incremental `revision > cursor` pull
        // re-evaluates its now-changed visibility — the access nudge alone never carries the row.
        for (bookId in assignments.keys) {
            bookRevisionTouch.touchRevision(BookId(bookId))
        }
        // Maintain exclusivity from the post-release set: a book released into explicit targets must
        // not linger in ALL_BOOKS (defensive tombstone of any stale junction); one released unsorted
        // stays in ALL_BOOKS (the fallback added above). reconcile derives both.
        for (bookId in assignments.keys) {
            reconcileSystemMembership(bookId)
        }
        return AppResult.Success(Unit)
    }

    /**
     * Enforces the exclusivity invariant between the everyone-visible ALL_BOOKS substrate and
     * every regular collection: a book is in ALL_BOOKS **iff** it belongs to no other (non-system)
     * collection and is not held in INBOX. Called after any junction mutation.
     *
     * Derives the book's live memberships, then:
     *  - `real.isEmpty() && !held` → ensures a live ALL_BOOKS junction via
     *    [CollectionBookRepository.upsert], which **resurrects** a tombstoned row — unlike the
     *    insert-only `writeSystemMembership`, which would skip an existing/tombstoned junction.
     *  - otherwise → tombstones the live ALL_BOOKS junction if present.
     *
     * Only when ALL_BOOKS membership actually flips does it nudge that collection's grant-holders
     * (every member holds a default ALL_BOOKS grant) and bump the book's revision, so each member
     * re-derives their view and the visibility delta converges. Idempotent: a no-op flip emits
     * nothing.
     */
    private suspend fun reconcileSystemMembership(bookId: String) {
        val libraryId = bookLibraryId(bookId) ?: return
        val systemIds = collectionRepo.systemCollectionIds()
        val liveMemberships = collectionBookRepo.findCollectionIdsForBook(bookId).toSet()
        val real = liveMemberships - systemIds
        val inboxId = collectionRepo.findInboxForLibrary(libraryId)?.id
        val held = inboxId != null && inboxId in liveMemberships
        val allBooksId = collectionRepo.findSystemCollection(libraryId, SYSTEM_TYPE_ALL_BOOKS)?.id
        val allBooksLive = allBooksId != null && allBooksId in liveMemberships

        if (real.isEmpty() && !held) {
            if (allBooksLive) return
            val id =
                allBooksId
                    ?: getOrCreateSystemCollection(libraryId, SystemCollectionType.ALL_BOOKS)
                        .getOrElse { return }
                        .id.value
            collectionBookRepo.upsert(
                CollectionBookSyncPayload(
                    collectionId = id,
                    bookId = bookId,
                    createdAt = clock.now().toEpochMilliseconds(),
                    revision = 0L,
                    deletedAt = null,
                ),
            )
            notifyAccessChanged(listOf(id), listOf(bookId))
            bookRevisionTouch.touchRevision(BookId(bookId))
        } else if (allBooksLive) {
            collectionBookRepo.softDelete(collectionId = allBooksId, bookId = bookId)
            notifyAccessChanged(listOf(allBooksId), listOf(bookId))
            bookRevisionTouch.touchRevision(BookId(bookId))
        }
    }

    /** The `books.library_id` for [bookId], or null when the book is absent — resolves its system collections. */
    private suspend fun bookLibraryId(bookId: String): String? =
        suspendTransaction(sql) { sql.booksQueries.selectLibraryIdById(bookId).executeAsOneOrNull() }

    /**
     * Nudge the audience of an access change on [collectionIds] — each collection's owner + active
     * grant recipients — to re-derive, carrying the [scoped delta][accessScopeFor] of the
     * [affectedBookIds] (books whose accessibility may have flipped). [affectedBookIds] is
     * **required**: an emission site that cannot enumerate the touched books passes the coarse
     * `emptyList()` deliberately (there is no way to forget it silently), and a scope over
     * [DELTA_MAX_BOOKS] entities degrades to coarse. The audience is resolved once here; the
     * recipient-agnostic frame is published to every member (per-user truth resolves at fetch time).
     */
    private suspend fun notifyAccessChanged(
        collectionIds: Collection<String>,
        affectedBookIds: Collection<String>,
    ) {
        val audience = accessChangedAudience(collectionIds)
        if (audience.isEmpty()) return
        publishAccessChanged(audience, accessScopeFor(collectionIds, affectedBookIds))
    }

    /**
     * The users to nudge for an access change on [collectionIds]: each collection's owner + its
     * active grant recipients, minus the [SYSTEM_OWNER_ID] sentinel (not a real client — ALL_BOOKS
     * reaches its real audience through the per-member default grants enumerated here). Captured
     * separately from [publishAccessChanged] so a cascade (e.g. [deleteCollection]) can resolve the
     * audience *before* it tombstones the grants, then publish *after* the writes commit.
     */
    private suspend fun accessChangedAudience(collectionIds: Collection<String>): Set<String> =
        collectionIds
            .flatMap { collectionId ->
                val owner = collectionRepo.findById(collectionId)?.ownerId
                val shareUsers = grantRepo.listActiveGrantsForCollection(collectionId).map { it.sharedWithUserId }
                if (owner != null) shareUsers + owner else shareUsers
            }.toSet()
            .minus(SYSTEM_OWNER_ID)

    /** Publishes the recipient-agnostic [AccessChanged][SyncControl.AccessChanged] frame carrying [scope] to each of [userIds]. */
    private suspend fun publishAccessChanged(
        userIds: Set<String>,
        scope: AccessScope?,
    ) {
        for (userId in userIds) {
            bus.publishControl(SyncControl.AccessChanged(scope), userId)
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
            grantRepo = grantRepo,
            accessPolicy = accessPolicy,
            permissionPolicy = permissionPolicy,
            bus = bus,
            sql = sql,
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
            isSystem = collection.isSystem,
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
                isSystem = true,
                bookCount = collectionBookRepo.countLiveForCollection(collection.id),
                callerPermission = SharePermission.Write,
                isOwner = true,
            ),
        )

    private suspend fun bookExists(bookId: String): Boolean =
        suspendTransaction(sql) { sql.booksQueries.existsLiveById(bookId).executeAsOne() }

    private suspend fun userExists(userId: String): Boolean =
        suspendTransaction(sql) { sql.usersQueries.existsById(userId).executeAsOne() }

    private fun CollectionShareSyncPayload.toDto(): CollectionShareDto =
        CollectionShareDto(
            id = id,
            collectionId = CollectionId(collectionId),
            sharedWithUserId = UserId(sharedWithUserId),
            permission = permission,
        )

    private suspend fun CollectionRepository.softDelete(id: String): AppResult<Unit> = softDelete(id, clientOpId = null)

    private suspend fun CollectionGrantRepository.softDelete(id: String): AppResult<Unit> =
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
    grantRepo: CollectionGrantRepository,
    bus: ChangeBus,
    sql: com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase,
    bookRevisionTouch: BookRevisionTouch,
    clock: Clock = Clock.System,
): CollectionService =
    CollectionServiceImpl(
        collectionRepo = collectionRepo,
        collectionBookRepo = collectionBookRepo,
        grantRepo = grantRepo,
        accessPolicy = CollectionAccessPolicy(collectionRepo, grantRepo),
        permissionPolicy = UserPermissionPolicy(sql),
        bus = bus,
        sql = sql,
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
