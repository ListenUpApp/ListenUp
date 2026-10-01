package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.api.BookAccessPolicy
import com.calypsan.listenup.server.auth.UserRoleLookup
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.sync.ShelfBookRepository
import com.calypsan.listenup.server.sync.ShelfRepository

/** The shelf Want to Read lands on once the user's starter "To Read" shelf is gone. */
internal const val WANT_TO_READ_SHELF_NAME = "Want to Read"

/**
 * Brings the user's Hardcover Want to Read list onto a ListenUp shelf, one way (#1539). The pull hands it
 * every page ([applyPage]):
 * - A matched Want to Read book the user can see is placed on the target shelf: the starter "To Read"
 *   shelf while it lives (whatever it's called now); else the "Want to Read" shelf made for this before;
 *   else a new public "Want to Read" shelf, made only when there is a book to put on it.
 * - A book already on the shelf by hand is the user's: it is never recorded, so never taken off.
 * - A book the user took off by hand ([HardcoverShelfEntryState.USER_REMOVED]) stays off.
 *
 * Every shelf write goes through [ShelfRepository] and [ShelfBookRepository], so the shelves sync domains
 * carry it to the user's devices. A record is written before its book is added: a crash in between leaves
 * an `ON_SHELF` record with the book missing, which the next sighting heals. The opposite order would leave
 * Hardcover's book looking hand-added, and Hardcover could never take it off.
 */
class HardcoverWantToRead(
    private val sql: ListenUpDatabase,
    private val entries: HardcoverShelfEntryStore,
    private val shelves: ShelfRepository,
    private val shelfBooks: ShelfBookRepository,
    private val access: BookAccessPolicy,
) {
    private val roles = UserRoleLookup(sql)

    /**
     * Applies one pull page, whose entries resolved to the library books in [resolved] (keyed by
     * user-book id), seen at [seenAt]. A failed shelf write is answered at once, so the page isn't committed
     * and the pull asks for it again.
     */
    suspend fun applyPage(
        userId: String,
        page: List<HardcoverShelfEntry>,
        resolved: Map<Long, ShelfResolution>,
        seenAt: Long,
    ): AppResult<Unit> {
        val wanted =
            page
                .filter { it.statusId == HardcoverStatus.WANT_TO_READ }
                .mapNotNull { entry -> resolved[entry.userBookId]?.let { WantedBook(entry.userBookId, it.bookId) } }
        if (wanted.isEmpty()) return AppResult.Success(Unit)
        val role = roles.roleOf(userId) ?: return AppResult.Success(Unit)
        val target = TargetShelf(userId)
        for (book in wanted) {
            if (!access.canAccess(userId, role, book.bookId)) continue
            val placed = place(userId, book, target, seenAt)
            if (placed is AppResult.Failure) return placed
        }
        return AppResult.Success(Unit)
    }

    private suspend fun place(
        userId: String,
        book: WantedBook,
        target: TargetShelf,
        seenAt: Long,
    ): AppResult<Unit> {
        val shelfId = target.live()
        val sighting = entries.sighting(userId, book.bookId, shelfId)
        val record = sighting.record
        return when {
            // Taken off by hand, or Hardcover's and where it should be: only note that Want to Read still has it.
            record?.state == HardcoverShelfEntryState.USER_REMOVED ||
                (record != null && record.shelfId == shelfId && sighting.isOnTarget) -> {
                entries.markSeen(userId, book.bookId, book.hcUserBookId, seenAt)
                AppResult.Success(Unit)
            }

            // Hardcover's record names a shelf that is gone, but the user already put the book on the new one.
            record != null && sighting.isOnTarget -> {
                entries.forget(userId, book.bookId)
                AppResult.Success(Unit)
            }

            // On the shelf by hand: the user's, never Hardcover's.
            record == null && sighting.isOnTarget -> {
                AppResult.Success(Unit)
            }

            // New; or Hardcover's but missing (a crash between record and add); or its shelf has gone.
            else -> {
                add(userId, book, target, seenAt)
            }
        }
    }

    private suspend fun add(
        userId: String,
        book: WantedBook,
        target: TargetShelf,
        seenAt: Long,
    ): AppResult<Unit> {
        val shelfId =
            when (val made = target.orMake()) {
                is AppResult.Success -> made.data
                is AppResult.Failure -> return made
            }
        entries.putOnShelf(userId, book.bookId, shelfId, book.hcUserBookId, seenAt)
        return when (val added = shelfBooks.addBook(shelfId, book.bookId, userId)) {
            is AppResult.Success -> AppResult.Success(Unit)
            is AppResult.Failure -> added
        }
    }

    /** The live shelf Want to Read lands on now, or null when there is none yet. */
    private suspend fun liveTarget(userId: String): String? {
        val targets = entries.targets(userId) ?: return null
        return listOfNotNull(targets.starterShelfId, targets.hardcoverShelfId)
            .firstOrNull { shelves.findOwnedById(it)?.ownerId == userId }
    }

    /** One Want to Read entry, resolved to a library book. */
    private data class WantedBook(
        val hcUserBookId: Long,
        val bookId: String,
    )

    /** One page's target shelf: looked up once, and made only when a book needs it. */
    private inner class TargetShelf(
        private val userId: String,
    ) {
        private var looked = false
        private var shelfId: String? = null

        suspend fun live(): String? {
            if (!looked) {
                shelfId = liveTarget(userId)
                looked = true
            }
            return shelfId
        }

        suspend fun orMake(): AppResult<String> {
            live()?.let { return AppResult.Success(it) }
            return when (val made = shelves.createPublicShelf(userId, WANT_TO_READ_SHELF_NAME)) {
                is AppResult.Success -> {
                    entries.rememberHardcoverShelf(userId, made.data.id)
                    shelfId = made.data.id
                    AppResult.Success(made.data.id)
                }

                is AppResult.Failure -> {
                    made
                }
            }
        }
    }
}
