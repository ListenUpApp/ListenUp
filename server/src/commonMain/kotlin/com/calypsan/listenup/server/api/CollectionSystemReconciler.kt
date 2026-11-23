package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.CollectionSummary
import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.CollectionError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.getOrElse
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.CollectionSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.CollectionId
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.logging.loggerFor
import com.calypsan.listenup.server.services.BookRevisionTouch
import com.calypsan.listenup.server.sync.CollectionBookRepository
import com.calypsan.listenup.server.sync.CollectionRepository
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val log = loggerFor<CollectionSystemReconciler>()

/**
 * Stripe count for [CollectionSystemReconciler.bookLocks]. A power of two well above the concurrency
 * a self-hosted server sees, so same-stripe collisions between different books are rare and cost
 * only serialisation.
 */
private const val BOOK_LOCK_STRIPES = 64

/** Logs a discarded `collection_books` upsert [error] for [bookId]/[collectionId], tagged [op]. */
private fun logFailedMembershipWrite(
    op: String,
    bookId: String,
    collectionId: String,
    error: AppError,
) {
    log.error { "$op: membership write failed for book=$bookId collection=$collectionId — $error" }
}

/** Logs that [op] skipped the ALL_BOOKS reconcile for [bookId] after a failed membership write above it. */
internal fun logSkippedReconcile(
    op: String,
    bookId: String,
) {
    log.warn { "$op: skipping reconcile for book=$bookId after a failed membership write" }
}

/**
 * Upserts [payload] via this repository, logging (tagged [op]) and returning `false` on a write
 * failure instead of discarding the [AppResult].
 */
internal suspend fun CollectionBookRepository.upsertOrLog(
    op: String,
    payload: CollectionBookSyncPayload,
): Boolean {
    val result = upsert(payload)
    if (result is AppResult.Failure) {
        logFailedMembershipWrite(
            op = op,
            bookId = payload.bookId,
            collectionId = payload.collectionId,
            error = result.error,
        )
        return false
    }
    return true
}

/** What releasing one book out of the inbox did. */
internal enum class BookRelease {
    /** It was held and has left the inbox for its targets. */
    RELEASED,

    /** It was no longer held — another release took it first — so nothing was written. */
    NOT_HELD,

    /** A write faulted and the book was rolled back whole: still held, exactly as before. */
    FAILED,
}

/**
 * Releases one held book via [CollectionBookRepository.releaseFromInbox] under its book [lock].
 * A fault rolls the book back whole, logs the failure, and answers [BookRelease.FAILED] so other
 * books in the same batch still release and reconcile.
 */
internal suspend fun CollectionBookRepository.releaseHeldBook(
    lock: Mutex,
    bookId: String,
    inboxId: String,
    targetIds: List<String>,
    createdAt: Long,
): BookRelease =
    try {
        val released =
            lock.withLock {
                releaseFromInbox(bookId = bookId, inboxId = inboxId, targetIds = targetIds, createdAt = createdAt)
            }
        if (released) BookRelease.RELEASED else BookRelease.NOT_HELD
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.error(e) { "releaseBooks: book=$bookId stayed held after a failed release" }
        BookRelease.FAILED
    }

/** What a release of [requested] books answers: `Success` only when none of them stayed held. */
internal fun releaseOutcome(
    failedBookIds: List<String>,
    requested: Int,
): AppResult<Unit> =
    if (failedBookIds.isEmpty()) {
        AppResult.Success(Unit)
    } else {
        AppResult.Failure(
            CollectionError.ReleaseIncomplete(
                failedBookIds = failedBookIds,
                debugInfo = "${failedBookIds.size} of $requested books stayed held",
            ),
        )
    }

/** Returns whether [bookId] is live in `books`. */
internal suspend fun ListenUpDatabase.bookExistsLive(bookId: String): Boolean =
    suspendTransaction(this) { booksQueries.existsLiveById(bookId).executeAsOne() }

/**
 * Owns per-library system collections (`ALL_BOOKS`, `INBOX`), striped per-book locks, and the
 * exclusivity invariant that keeps uncollected non-quarantined books in `ALL_BOOKS`.
 */
internal class CollectionSystemReconciler(
    private val collectionRepo: CollectionRepository,
    private val collectionBookRepo: CollectionBookRepository,
    private val sql: ListenUpDatabase,
    private val clock: Clock,
    private val bookRevisionTouch: BookRevisionTouch,
    private val accessNotifier: CollectionAccessNotifier,
) {
    /**
     * Striped locks serialising **membership mutation + [reconcileSystemMembership]** for one book.
     * Shared across per-request `CollectionServiceImpl.copyWith` instances so concurrent requests for
     * the same book never interleave read-then-write decisions.
     */
    private val bookLocks = Array(BOOK_LOCK_STRIPES) { Mutex() }

    /**
     * The stripe guarding [bookId]. Uses `Int.mod` (never `%`) so negative hashes stay in bounds.
     */
    internal fun bookLock(bookId: String): Mutex = bookLocks[bookId.hashCode().mod(BOOK_LOCK_STRIPES)]

    /** Runs [block] under the stripe lock guarding [bookId]. */
    suspend fun <T> withBookLock(
        bookId: String,
        block: suspend () -> T,
    ): T = bookLock(bookId).withLock { block() }

    /**
     * Loop-invariant lookups for [reconcileSystemMembership], resolved at most once per bulk operation.
     * The ALL_BOOKS id is memoized only once FOUND so the lazy-create fallback still fires on the
     * first book that materialises it, and [noteAllBooksCreated] records it for subsequent iterations.
     */
    internal inner class SystemMembershipLookups {
        private var systemIds: Set<String>? = null
        private val inboxIdByLibrary = mutableMapOf<String, String?>()
        private val allBooksIdByLibrary = mutableMapOf<String, String>()

        suspend fun systemIds(): Set<String> =
            systemIds ?: collectionRepo.systemCollectionIds().toSet().also { systemIds = it }

        suspend fun inboxId(libraryId: String): String? {
            if (libraryId !in inboxIdByLibrary) {
                inboxIdByLibrary[libraryId] = collectionRepo.findInboxForLibrary(libraryId)?.id
            }
            return inboxIdByLibrary[libraryId]
        }

        suspend fun allBooksId(libraryId: String): String? {
            allBooksIdByLibrary[libraryId]?.let { cached -> return cached }
            val allBooks = collectionRepo.findSystemCollection(libraryId, SYSTEM_TYPE_ALL_BOOKS) ?: return null
            allBooksIdByLibrary[libraryId] = allBooks.id
            return allBooks.id
        }

        fun noteAllBooksCreated(
            libraryId: String,
            id: String,
        ) {
            allBooksIdByLibrary[libraryId] = id
            systemIds = systemIds?.plus(id)
        }
    }

    /** Resolves the library's per-library system collection of [type], creating it on first use. */
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
                val refreshed = collectionRepo.findById(result.data.id) ?: result.data
                summarizeSystem(refreshed)
            }

            is AppResult.Failure -> {
                AppResult.Failure(result.error)
            }
        }
    }

    /** Resolves the library's inbox, creating it on first use. */
    suspend fun getOrCreateInbox(libraryId: String): AppResult<CollectionSummary> =
        getOrCreateSystemCollection(libraryId, SystemCollectionType.INBOX)

    /** Adds [bookId] to [libraryId]'s inbox, resolving (or creating) the inbox first. */
    suspend fun addToInbox(
        bookId: String,
        libraryId: String,
    ): AppResult<Unit> {
        if (!sql.bookExistsLive(bookId)) return AppResult.Failure(CollectionError.BookNotFound())

        val inbox = getOrCreateInbox(libraryId).getOrElse { return AppResult.Failure(it) }

        val payload =
            CollectionBookSyncPayload(
                id = Uuid.random().toString(),
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
     * Enforces the exclusivity invariant between `ALL_BOOKS` and regular collections / `INBOX`.
     * Callers must hold [bookLock] for [bookId].
     */
    suspend fun reconcileSystemMembership(
        bookId: String,
        lookups: SystemMembershipLookups = SystemMembershipLookups(),
    ) {
        val libraryId = bookLibraryId(bookId) ?: return
        val systemIds = lookups.systemIds()
        val liveMemberships = collectionBookRepo.findCollectionIdsForBook(bookId).toSet()
        val real = liveMemberships - systemIds
        val inboxId = lookups.inboxId(libraryId)
        val held = inboxId != null && inboxId in liveMemberships
        val allBooksId = lookups.allBooksId(libraryId)
        val allBooksLive = allBooksId != null && allBooksId in liveMemberships

        if (real.isEmpty() && !held) {
            if (allBooksLive) return
            val id =
                allBooksId
                    ?: getOrCreateSystemCollection(libraryId, SystemCollectionType.ALL_BOOKS)
                        .getOrElse { return }
                        .id.value
                        .also { lookups.noteAllBooksCreated(libraryId, it) }
            val payload =
                CollectionBookSyncPayload(
                    id = Uuid.random().toString(),
                    collectionId = id,
                    bookId = bookId,
                    createdAt = clock.now().toEpochMilliseconds(),
                    revision = 0L,
                    deletedAt = null,
                )
            if (!collectionBookRepo.upsertOrLog("reconcileSystemMembership", payload)) return
            accessNotifier.notify(listOf(id), listOf(bookId))
            bookRevisionTouch.touchRevision(BookId(bookId))
            return
        }

        val toDrop =
            buildList {
                if (allBooksLive) add(allBooksId)
                if (held && real.isNotEmpty()) add(inboxId)
            }.filterNotNull()
        if (toDrop.isEmpty()) return
        toDrop.forEach { collectionBookRepo.softDelete(collectionId = it, bookId = bookId) }
        accessNotifier.notify(toDrop, listOf(bookId))
        bookRevisionTouch.touchRevision(BookId(bookId))
    }

    private suspend fun bookLibraryId(bookId: String): String? =
        suspendTransaction(sql) { sql.booksQueries.selectLibraryIdById(bookId).executeAsOneOrNull() }

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
}
