package com.calypsan.listenup.server.matching.undo

import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.scheduler.CoverReferences

/** The covers the orphan sweep must keep: every book row's cover path, and every live match receipt's. */
internal class BookCoverReferences(
    private val db: ListenUpDatabase,
    private val receipts: MatchReceiptStore,
) : CoverReferences {
    override suspend fun bookCoverPaths(): Set<String> =
        suspendTransaction(db) { db.booksQueries.selectAllCoverPaths().executeAsList().filterNotNullTo(mutableSetOf()) }

    override suspend fun pinnedCoverPaths(): Set<String> = receipts.pinnedCoverPaths()
}
