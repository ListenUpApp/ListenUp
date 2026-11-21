package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import kotlin.math.abs

/**
 * Tombstones the `FINISHED_BOOK` announcements no read accounts for. Until [StatsRecorder] raised the
 * announcement only for a finish that appended a read, a replay merged into the previous read announced
 * itself anyway, so the feed said "finished" twice for one read.
 *
 * Each of a user's ListenUp reads of a book keeps the one announcement nearest its finish (see
 * [surplusFinishAnnouncements]); the rest are soft-deleted through [activities], which bumps their
 * revision so every client's catch-up converges on the deletion. Only a (user, book) announced more often
 * than it was read is touched, so the sweep is idempotent: once the surplus is gone it finds nothing, and
 * it runs at every boot for that reason rather than as a one-shot migration.
 */
class FinishAnnouncementSweep(
    private val sql: ListenUpDatabase,
    private val activities: ActivitySyncRepository,
    private val reads: BookReadsRepository,
) {
    /** Tombstones every surplus announcement and returns how many it tombstoned. */
    suspend fun run(): Int {
        val pairs = suspendTransaction(sql) { sql.activitiesQueries.overAnnouncedFinishes().executeAsList() }
        return pairs.sumOf { (userId, bookId) ->
            val announcements =
                suspendTransaction(sql) {
                    sql.activitiesQueries
                        .liveFinishAnnouncements(user_id = userId, book_id = bookId) { id, occurredAt ->
                            FinishAnnouncement(id, occurredAt)
                        }.executeAsList()
                }
            val surplus = surplusFinishAnnouncements(announcements, reads.finishesForUserBook(userId, bookId))
            surplus.count { activities.softDelete(it) is AppResult.Success }
        }
    }
}

/** One live `FINISHED_BOOK` activity: its id and when it says the finish happened. */
internal data class FinishAnnouncement(
    val id: String,
    val occurredAt: Long,
)

/**
 * The ids of [announcements] that no read in [readFinishes] (each read's `finished_at`) accounts for.
 *
 * Every read claims the announcement nearest its finish, closest pairs first, so a read whose finish
 * moved forward (a merged replay) keeps the announcement of its latest finish, and one re-dated backward
 * keeps the announcement of its new date. With no reads nothing is surplus: there is no evidence to
 * judge the announcements by.
 */
internal fun surplusFinishAnnouncements(
    announcements: List<FinishAnnouncement>,
    readFinishes: List<Long>,
): List<String> {
    if (readFinishes.isEmpty() || announcements.size <= readFinishes.size) return emptyList()
    val claimedReads = mutableSetOf<Int>()
    val kept = mutableSetOf<String>()
    readFinishes
        .withIndex()
        .flatMap { (readIndex, finishedAt) ->
            announcements.map { announcement ->
                Triple(readIndex, announcement, abs(announcement.occurredAt - finishedAt))
            }
        }.sortedWith(compareBy<Triple<Int, FinishAnnouncement, Long>> { it.third }.thenBy { it.second.id })
        .forEach { (readIndex, announcement, _) ->
            if (readIndex !in claimedReads && announcement.id !in kept) {
                claimedReads += readIndex
                kept += announcement.id
            }
        }
    return announcements.map { it.id }.filterNot { it in kept }
}
