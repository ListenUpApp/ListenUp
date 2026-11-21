package com.calypsan.listenup.server.services

import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.logging.loggerFor

private val log = loggerFor<SittingsBackfill>()

/**
 * Rebuilds every user's sittings from their whole listening history: run once per boot, in the background,
 * so history recorded before sittings existed has its rows, and anything a crash left half-written heals.
 *
 * Idempotent: rebuilding from unchanged evidence writes nothing, so a second run over a settled history is
 * reads only. Users are walked a page at a time in id order, so a large server's history is never held in
 * memory at once. Each user's rebuild is firehose-suppressed and nudges clients itself when it wrote
 * ([SittingReconciler.rebuildUser]), so a settled history sends nothing.
 */
class SittingsBackfill(
    private val db: ListenUpDatabase,
    private val reconciler: SittingReconciler,
    private val pageSize: Long = DEFAULT_PAGE_SIZE,
) {
    /** Runs the backfill to the end and returns how many sitting rows it wrote. */
    suspend fun run(): Int {
        var after = ""
        var written = 0
        while (true) {
            val users =
                suspendTransaction(
                    db,
                ) { db.sittingsQueries.selectUsersAfter(after = after, limit = pageSize).executeAsList() }
            users.forEach { userId -> written += reconciler.rebuildUser(userId) }
            if (users.size < pageSize) break
            after = users.last()
        }
        if (written > 0) log.info { "sittings backfill wrote $written rows" }
        return written
    }

    private companion object {
        const val DEFAULT_PAGE_SIZE = 50L
    }
}
