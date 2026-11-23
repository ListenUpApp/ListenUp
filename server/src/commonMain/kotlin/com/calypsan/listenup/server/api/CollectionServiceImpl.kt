package com.calypsan.listenup.server.api

import app.cash.sqldelight.db.SqlDriver
import com.calypsan.listenup.api.CollectionService
import com.calypsan.listenup.api.dto.CollectionShareDto
import com.calypsan.listenup.api.dto.CollectionSummary
import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.CollectionError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.getOrElse
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.CollectionShareSyncPayload
import com.calypsan.listenup.api.sync.CollectionSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.CollectionId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.isAdmin
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
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val MAX_NAME_LENGTH = 200

/**
 * Largest number of collections one book may be filed under in a single
 * [CollectionServiceImpl.setBookCollections] call. The picker that produces this list is a checkbox
 * over the caller's visible collections and a real save carries a handful; a book filed under more
 * than this many is not a shelf anyone browses. 200 mirrors `MAX_CONTRIBUTORS_PER_BOOK` in
 * `BookServiceImpl`, the same shape of per-book list.
 */
internal const val MAX_COLLECTIONS_PER_BOOK = 200

/**
 * The failure for a [CollectionServiceImpl.setBookCollections] target set larger than
 * [MAX_COLLECTIONS_PER_BOOK], or `null` when [collectionIds] is within the bound.
 */
private fun overCollectionCap(collectionIds: List<CollectionId>): CollectionError.InvalidInput? =
    if (collectionIds.size > MAX_COLLECTIONS_PER_BOOK) {
        CollectionError.InvalidInput(
            debugInfo = "collectionIds: size ${collectionIds.size} exceeds max $MAX_COLLECTIONS_PER_BOOK",
        )
    } else {
        null
    }

/**
 * [CollectionService] implementation.
 *
 * Resolves the authenticated caller from [principal] (never from request fields), bridges the
 * contract [UserRole] to the DB [UserRoleColumn] the [CollectionAccessPolicy] speaks, and gates
 * every operation through that policy.
 *
 * Route handlers call [copyWith] to bind each request to the authenticated principal; per-request
 * copies share [accessNotifier] and [systemReconciler] (including its striped `bookLocks`) so
 * concurrent requests serialise against the same per-book mutexes.
 */
internal class CollectionServiceImpl(
    private val collectionRepo: CollectionRepository,
    private val collectionBookRepo: CollectionBookRepository,
    private val grantRepo: CollectionGrantRepository,
    private val accessPolicy: CollectionAccessPolicy,
    private val bookAccessPolicy: BookAccessPolicy,
    private val bus: ChangeBus,
    private val sql: ListenUpDatabase,
    private val clock: Clock = Clock.System,
    private val bookRevisionTouch: BookRevisionTouch,
    private val principal: PrincipalProvider,
    private val accessNotifier: CollectionAccessNotifier =
        CollectionAccessNotifier(collectionRepo, grantRepo, bus),
    private val systemReconciler: CollectionSystemReconciler =
        CollectionSystemReconciler(
            collectionRepo = collectionRepo,
            collectionBookRepo = collectionBookRepo,
            sql = sql,
            clock = clock,
            bookRevisionTouch = bookRevisionTouch,
            accessNotifier = accessNotifier,
        ),
) : CollectionService {
    internal fun bookLock(bookId: String) = systemReconciler.bookLock(bookId)

    // ── Membership mutation ───────────────────────────────────────────────────

    override suspend fun createCollection(
        libraryId: String,
        name: String,
    ): AppResult<CollectionSummary> {
        val caller = resolveCaller() ?: return noPrincipal()
        adminGate(caller.role)?.let { return AppResult.Failure(it) }
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
        manageGate(decision, caller.role)?.let { return AppResult.Failure(it) }
        if (id.value in collectionRepo.systemCollectionIds()) {
            return AppResult.Failure(CollectionError.SystemCollectionReadOnly())
        }

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
        manageGate(decision, caller.role)?.let { return AppResult.Failure(it) }

        if (collectionRepo.findById(id.value) == null) return AppResult.Failure(CollectionError.NotFound())
        if (id.value in collectionRepo.systemCollectionIds()) {
            return AppResult.Failure(CollectionError.SystemCollectionReadOnly())
        }

        // Capture affected books and audience before tombstoning; publish after writes commit so no
        // client pulls stale rows before the cascade finishes.
        val affectedBookIds = collectionBookRepo.findBookIdsForCollection(id.value)
        val audience = accessNotifier.audienceFor(listOf(id.value))
        val scope = accessScopeFor(listOf(id.value), affectedBookIds)
        collectionBookRepo.softDeleteAllForCollection(id.value)
        val lookups = systemReconciler.SystemMembershipLookups()
        for (bookId in affectedBookIds) {
            systemReconciler.withBookLock(bookId) { reconcileSystemMembership(bookId, lookups) }
        }
        bookRevisionTouch.touchRevisions(affectedBookIds.map(::BookId))
        for (grant in grantRepo.listActiveGrantsForCollection(id.value)) {
            grantRepo.softDelete(grant.id)
        }
        collectionRepo.softDelete(id.value)
        if (audience.isNotEmpty()) accessNotifier.publish(audience, scope)
        return AppResult.Success(Unit)
    }

    override suspend fun addBookToCollection(
        id: CollectionId,
        bookId: BookId,
    ): AppResult<Unit> {
        val caller = resolveCaller() ?: return noPrincipal()
        val decision = accessPolicy.decide(caller.userId, caller.role, id.value)
        writeGate(decision)?.let { return AppResult.Failure(it) }

        if (!bookAccessPolicy.canAccess(caller.userId, caller.role.toContract(), bookId.value)) {
            return AppResult.Failure(CollectionError.BookNotFound())
        }
        if (!sql.bookExistsLive(bookId.value)) return AppResult.Failure(CollectionError.BookNotFound())

        val payload =
            CollectionBookSyncPayload(
                id = Uuid.random().toString(),
                collectionId = id.value,
                bookId = bookId.value,
                createdAt = clock.now().toEpochMilliseconds(),
                revision = 0L,
                deletedAt = null,
            )
        return systemReconciler.withBookLock(bookId.value) {
            when (val result = collectionBookRepo.upsert(payload)) {
                is AppResult.Success -> {
                    accessNotifier.notify(listOf(id.value), listOf(bookId.value))
                    bookRevisionTouch.touchRevision(bookId)
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
    }

    override suspend fun removeBookFromCollection(
        id: CollectionId,
        bookId: BookId,
    ): AppResult<Unit> {
        val caller = resolveCaller() ?: return noPrincipal()
        val decision = accessPolicy.decide(caller.userId, caller.role, id.value)
        writeGate(decision)?.let { return AppResult.Failure(it) }

        systemReconciler.withBookLock(bookId.value) {
            collectionBookRepo.softDelete(collectionId = id.value, bookId = bookId.value)
            accessNotifier.notify(listOf(id.value), listOf(bookId.value))
            bookRevisionTouch.touchRevision(bookId)
            reconcileSystemMembership(bookId.value)
        }
        return AppResult.Success(Unit)
    }

    override suspend fun setBookCollections(
        bookId: BookId,
        collectionIds: List<CollectionId>,
    ): AppResult<Unit> {
        val caller = resolveCaller() ?: return noPrincipal()
        adminGate(caller.role)?.let { return AppResult.Failure(it) }
        overCollectionCap(collectionIds)?.let { return AppResult.Failure(it) }

        if (!sql.bookExistsLive(bookId.value)) return AppResult.Failure(CollectionError.BookNotFound())

        val systemIds = collectionRepo.systemCollectionIds()
        val targetIds = collectionIds.map { it.value }.toSet() - systemIds
        if (targetIds.isNotEmpty() && collectionRepo.findByIds(targetIds).size < targetIds.size) {
            return AppResult.Failure(CollectionError.NotFound())
        }

        systemReconciler.withBookLock(bookId.value) {
            val current = collectionBookRepo.findCollectionIdsForBook(bookId.value).toSet() - systemIds
            val added = targetIds - current
            val removed = current - targetIds

            for (collectionId in removed) {
                collectionBookRepo.softDelete(collectionId = collectionId, bookId = bookId.value)
            }
            var hadFailedWrite = false
            for (collectionId in added) {
                val payload =
                    CollectionBookSyncPayload(
                        id = Uuid.random().toString(),
                        collectionId = collectionId,
                        bookId = bookId.value,
                        createdAt = clock.now().toEpochMilliseconds(),
                        revision = 0L,
                        deletedAt = null,
                    )
                if (!collectionBookRepo.upsertOrLog("setBookCollections", payload)) hadFailedWrite = true
            }

            accessNotifier.notify(added + removed, listOf(bookId.value))
            if (added.isNotEmpty() || removed.isNotEmpty()) {
                bookRevisionTouch.touchRevision(bookId)
            }
            if (!hadFailedWrite) {
                reconcileSystemMembership(bookId.value)
            } else {
                logSkippedReconcile("setBookCollections", bookId.value)
            }
        }
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
        manageGate(decision, caller.role)?.let { return AppResult.Failure(it) }
        if (id.value in collectionRepo.systemCollectionIds()) {
            return AppResult.Failure(CollectionError.SystemCollectionReadOnly())
        }
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
                accessNotifier.publish(
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
        manageGate(decision, caller.role)?.let { return AppResult.Failure(it) }
        if (id.value in collectionRepo.systemCollectionIds()) {
            return AppResult.Failure(CollectionError.SystemCollectionReadOnly())
        }

        if (grantRepo.softDeleteGrant(id.value, sharedWithUserId) is AppResult.Success) {
            accessNotifier.publish(
                setOf(sharedWithUserId),
                accessScopeFor(listOf(id.value), collectionBookRepo.findBookIdsForCollection(id.value)),
            )
        }
        return AppResult.Success(Unit)
    }

    // ── Inbox (system collection + release flow) ──────────────────────────────────

    /** Resolves the library's per-library system collection of [type], creating it on first use. */
    suspend fun getOrCreateSystemCollection(
        libraryId: String,
        type: SystemCollectionType,
    ): AppResult<CollectionSummary> = systemReconciler.getOrCreateSystemCollection(libraryId, type)

    /** Resolves the library's inbox, creating it on first use. */
    suspend fun getOrCreateInbox(libraryId: String): AppResult<CollectionSummary> =
        systemReconciler.getOrCreateInbox(libraryId)

    /** Adds [bookId] to [libraryId]'s inbox, resolving (or creating) the inbox first. */
    suspend fun addToInbox(
        bookId: String,
        libraryId: String,
    ): AppResult<Unit> = systemReconciler.addToInbox(bookId, libraryId)

    override suspend fun releaseBooks(
        libraryId: LibraryId,
        assignments: Map<BookId, List<CollectionId>>,
    ): AppResult<Unit> {
        val caller = resolveCaller() ?: return noPrincipal()
        adminGate(caller.role)?.let { return AppResult.Failure(it) }

        val libraryIdValue = libraryId.value
        val targetsByBookId =
            assignments.entries.associate { (bookId, targets) ->
                bookId.value to
                    targets.map(CollectionId::value)
            }
        val inboxId = getOrCreateInbox(libraryIdValue).getOrElse { return AppResult.Failure(it) }.id.value

        val allBooksId =
            if (targetsByBookId.values.any { it.isEmpty() }) {
                getOrCreateSystemCollection(libraryIdValue, SystemCollectionType.ALL_BOOKS)
                    .getOrElse { return AppResult.Failure(it) }
                    .id.value
            } else {
                null
            }

        validateReleaseTargets(targetsByBookId.values.flatten().toSet(), libraryIdValue)?.let {
            return AppResult.Failure(it)
        }

        val releasedBookIds = mutableListOf<String>()
        val releasedTargetIds = mutableSetOf<String>()
        val failedBookIds = mutableListOf<String>()
        for ((bookId, targetCollectionIds) in targetsByBookId) {
            val resolvedTargets = targetCollectionIds.ifEmpty { listOfNotNull(allBooksId) }
            when (
                collectionBookRepo.releaseHeldBook(
                    lock = systemReconciler.bookLock(bookId),
                    bookId = bookId,
                    inboxId = inboxId,
                    targetIds = resolvedTargets,
                    createdAt = clock.now().toEpochMilliseconds(),
                )
            ) {
                BookRelease.RELEASED -> {
                    releasedBookIds += bookId
                    releasedTargetIds += resolvedTargets
                }

                BookRelease.FAILED -> {
                    failedBookIds += bookId
                }

                BookRelease.NOT_HELD -> {}
            }
        }

        if (releasedBookIds.isNotEmpty()) {
            accessNotifier.notify(releasedTargetIds, releasedBookIds)
            bookRevisionTouch.touchRevisions(releasedBookIds.map(::BookId))
        }
        val lookups = systemReconciler.SystemMembershipLookups()
        for (bookId in releasedBookIds) {
            systemReconciler.withBookLock(bookId) { reconcileSystemMembership(bookId, lookups) }
        }
        for (bookId in failedBookIds) logSkippedReconcile("releaseBooks", bookId)

        return releaseOutcome(failedBookIds, requested = targetsByBookId.size)
    }

    // ── Principal binding ───────────────────────────────────────────────────────

    /** Returns a copy scoped to the given [principal]. Route handlers call this per-request. */
    fun copyWith(principal: PrincipalProvider): CollectionServiceImpl =
        CollectionServiceImpl(
            collectionRepo = collectionRepo,
            collectionBookRepo = collectionBookRepo,
            grantRepo = grantRepo,
            accessPolicy = accessPolicy,
            bookAccessPolicy = bookAccessPolicy,
            bus = bus,
            sql = sql,
            clock = clock,
            bookRevisionTouch = bookRevisionTouch,
            principal = principal,
            accessNotifier = accessNotifier,
            systemReconciler = systemReconciler,
        )

    // ── Private helpers ─────────────────────────────────────────────────────────

    private data class Caller(
        val userId: String,
        val role: UserRoleColumn,
    )

    private fun resolveCaller(): Caller? = principal.current()?.let { Caller(it.userId.value, it.role.toColumn()) }

    private fun noPrincipal(): AppResult.Failure = AppResult.Failure(CollectionError.NotFound())

    private fun manageGate(
        decision: CollectionAccessPolicy.Decision,
        role: UserRoleColumn,
    ): AppError? =
        when {
            role.isAdmin() -> null
            decision.canAccess -> AuthError.PermissionDenied()
            else -> CollectionError.NotFound()
        }

    private suspend fun validateReleaseTargets(
        explicitTargetIds: Set<String>,
        libraryIdValue: String,
    ): CollectionError? {
        if (explicitTargetIds.isEmpty()) return null
        val foundTargets = collectionRepo.findByIds(explicitTargetIds)
        for (targetId in explicitTargetIds) {
            val target = foundTargets[targetId] ?: return CollectionError.NotFound()
            if (target.libraryId != libraryIdValue) {
                return CollectionError.InvalidInput(debugInfo = "Target collection $targetId is in a different library")
            }
        }
        return null
    }

    private suspend fun reconcileSystemMembership(bookId: String) = systemReconciler.reconcileSystemMembership(bookId)

    private suspend fun reconcileSystemMembership(
        bookId: String,
        lookups: CollectionSystemReconciler.SystemMembershipLookups,
    ) = systemReconciler.reconcileSystemMembership(bookId, lookups)

    private suspend fun summarize(
        collection: CollectionSyncPayload,
        caller: Caller,
        decision: CollectionAccessPolicy.Decision? = null,
        bookCount: Long? = null,
    ): CollectionSummary {
        val verdict = decision ?: accessPolicy.decide(caller.userId, caller.role, collection.id)
        return CollectionSummary(
            id = CollectionId(collection.id),
            name = collection.name,
            ownerId = UserId(collection.ownerId),
            isInbox = collection.isInbox,
            isSystem = collection.isSystem,
            bookCount = bookCount ?: collectionBookRepo.countLiveForCollection(collection.id),
            callerPermission = verdict.permission,
            isOwner = verdict.isOwner,
        )
    }

    private suspend fun userExists(userId: String): Boolean =
        suspendTransaction(sql) { sql.usersQueries.existsById(userId).executeAsOne() }

    private fun CollectionShareSyncPayload.toDto(): CollectionShareDto =
        CollectionShareDto(
            id = id,
            collectionId = CollectionId(collectionId),
            sharedWithUserId = UserId(sharedWithUserId),
            permission = permission,
        )
}

/** Admin gate: null = allowed (ROOT/ADMIN); [AuthError.PermissionDenied] for everyone else. */
private fun adminGate(role: UserRoleColumn): AppError? = if (role.isAdmin()) null else AuthError.PermissionDenied()

/** Write gate: null = allowed; PermissionDenied if the caller can read but not write; NotFound otherwise. */
private fun writeGate(decision: CollectionAccessPolicy.Decision): AppError? =
    when {
        decision.canAccess && decision.permission.canWrite() -> null
        decision.canAccess -> AuthError.PermissionDenied()
        else -> CollectionError.NotFound()
    }

/**
 * Constructs a [CollectionService] backed by [CollectionServiceImpl]. Public so cross-module
 * test harnesses can build the service without depending on the Koin graph or piercing the
 * `internal` access on [CollectionServiceImpl] / [CollectionAccessPolicy].
 */
fun createCollectionService(
    collectionRepo: CollectionRepository,
    collectionBookRepo: CollectionBookRepository,
    grantRepo: CollectionGrantRepository,
    bus: ChangeBus,
    sql: ListenUpDatabase,
    driver: SqlDriver,
    bookRevisionTouch: BookRevisionTouch,
    clock: Clock = Clock.System,
): CollectionService =
    CollectionServiceImpl(
        collectionRepo = collectionRepo,
        collectionBookRepo = collectionBookRepo,
        grantRepo = grantRepo,
        accessPolicy = CollectionAccessPolicy(collectionRepo, grantRepo),
        bookAccessPolicy = BookAccessPolicy(sql, driver),
        bus = bus,
        sql = sql,
        clock = clock,
        bookRevisionTouch = bookRevisionTouch,
        principal = PrincipalProvider { error("Unscoped CollectionService — call collectionServiceScopedTo") },
    )

/**
 * Scopes a [CollectionService] built by [createCollectionService] to [principal] for one caller.
 */
fun collectionServiceScopedTo(
    service: CollectionService,
    principal: PrincipalProvider,
): CollectionService = (service as CollectionServiceImpl).copyWith(principal)
