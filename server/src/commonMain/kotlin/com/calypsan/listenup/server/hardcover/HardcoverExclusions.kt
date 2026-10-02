package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction

/**
 * What keeping one book off Hardcover would take out of ListenUp (#1541): [readsInReaders] when the listener's
 * Hardcover reads of it are mirrored into Readers, [onToReadFromHardcover] when Hardcover's Want to Read put it
 * on a shelf and it is still there. A client asks first only when one of them is true.
 */
data class HardcoverKeepOffRemovals(
    val readsInReaders: Boolean,
    val onToReadFromHardcover: Boolean,
)

/**
 * The books each listener keeps off Hardcover (#1541), read. Every part of Hardcover sync that acts on one
 * book asks [isExcluded] first: the push recorder and the push worker, the pull's shelf resolver, and Book
 * Detail's match. The history selection, the match pass, Needs a match and the Want to Read sweep leave
 * kept-off books out in their SQL. [HardcoverKeepOff] is what keeps a book off and syncs it again.
 */
class HardcoverExclusions(
    private val sql: ListenUpDatabase,
) {
    /** Whether [userId] keeps [bookId] off Hardcover. */
    suspend fun isExcluded(
        userId: String,
        bookId: String,
    ): Boolean =
        suspendTransaction(sql) { sql.hardcoverBookExclusionsQueries.isExcluded(userId, bookId).executeAsOne() }

    /** What keeping [bookId] off Hardcover would take out of [userId]'s ListenUp, read together. */
    suspend fun keepOffRemovals(
        userId: String,
        bookId: String,
    ): HardcoverKeepOffRemovals =
        suspendTransaction(sql) {
            HardcoverKeepOffRemovals(
                readsInReaders = sql.hasPulledReadsOf(userId, bookId),
                onToReadFromHardcover =
                    sql.hardcoverShelfEntriesQueries
                        .isOnShelfFromHardcover(
                            userId,
                            bookId,
                        ).executeAsOne(),
            )
        }
}

/** How many books still in the library [userId] keeps off Hardcover: Connected's `keptOffBookCount`. */
internal suspend fun ListenUpDatabase.keptOffBookCount(userId: String): Int =
    suspendTransaction(this) { hardcoverBookExclusionsQueries.countKeptOffBooks(userId).executeAsOne().toInt() }
