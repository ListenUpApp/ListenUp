package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.CollectionService
import com.calypsan.listenup.api.dto.CollectionShareDto
import com.calypsan.listenup.api.dto.CollectionSummary
import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.error.CollectionError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.CollectionSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.CollectionId
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.toColumn
import com.calypsan.listenup.server.db.BookTable
import com.calypsan.listenup.server.db.UserRoleColumn
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
private const val SHARE_TODO = "Collections-1a Task 7"

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
 * Sharing methods are Collections-1a Task 7 and are stubbed.
 */
internal class CollectionServiceImpl(
    private val collectionRepo: CollectionRepository,
    private val collectionBookRepo: CollectionBookRepository,
    private val shareRepo: CollectionShareRepository,
    private val accessPolicy: CollectionAccessPolicy,
    private val db: Database,
    private val clock: Clock = Clock.System,
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
        val saved =
            when (val result = collectionRepo.upsert(payload)) {
                is AppResult.Success -> result.data
                is AppResult.Failure -> return AppResult.Failure(result.error)
            }
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
        val saved =
            when (val result = collectionRepo.upsert(updated)) {
                is AppResult.Success -> result.data
                is AppResult.Failure -> return AppResult.Failure(result.error)
            }
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
            is AppResult.Success -> AppResult.Success(Unit)
            is AppResult.Failure -> AppResult.Failure(result.error)
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
        return AppResult.Success(Unit)
    }

    // ── Share mutation — Collections-1a Task 7 ──────────────────────────────────

    override suspend fun shareCollection(
        id: CollectionId,
        sharedWithUserId: String,
        permission: SharePermission,
    ): AppResult<CollectionShareDto> = TODO(SHARE_TODO)

    override suspend fun updateShare(
        id: CollectionId,
        sharedWithUserId: String,
        permission: SharePermission,
    ): AppResult<CollectionShareDto> = TODO(SHARE_TODO)

    override suspend fun revokeShare(
        id: CollectionId,
        sharedWithUserId: String,
    ): AppResult<Unit> = TODO(SHARE_TODO)

    override suspend fun listShares(id: CollectionId): AppResult<List<CollectionShareDto>> = TODO(SHARE_TODO)

    // ── Principal binding ───────────────────────────────────────────────────────

    /** Returns a copy scoped to the given [principal]. Route handlers call this per-request. */
    fun copyWith(principal: PrincipalProvider): CollectionServiceImpl =
        CollectionServiceImpl(
            collectionRepo = collectionRepo,
            collectionBookRepo = collectionBookRepo,
            shareRepo = shareRepo,
            accessPolicy = accessPolicy,
            db = db,
            clock = clock,
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

    private suspend fun bookExists(bookId: String): Boolean =
        suspendTransaction(db) {
            BookTable
                .selectAll()
                .where { (BookTable.id eq bookId) and BookTable.deletedAt.isNull() }
                .count() > 0
        }

    private suspend fun CollectionRepository.softDelete(id: String): AppResult<Unit> = softDelete(id, clientOpId = null)

    private suspend fun CollectionShareRepository.softDelete(id: String): AppResult<Unit> =
        softDelete(id, clientOpId = null)
}
