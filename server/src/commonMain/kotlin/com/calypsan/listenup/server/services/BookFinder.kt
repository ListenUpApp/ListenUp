package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction

/** SQLite caps a statement at 999 bound parameters; chunk `IN (…)` lists below it with headroom. */
private const val SQLITE_IN_CHUNK = 900

/**
 * Transaction-scoped read helpers for the books aggregate, over the generated SQLDelight
 * queries.
 *
 * Each method opens its own short read transaction — they do NOT bump the global revision,
 * the change bus, or any write query.
 *
 * @param db the SQLDelight database for the generated reads + aggregate hydration.
 */
internal class BookFinder(
    private val db: ListenUpDatabase,
) {
    /** Resolves the natural key `(folder_id, root_rel_path)` to a [BookId], or null. */
    suspend fun findByPath(
        folderId: FolderId,
        rootRelPath: String,
    ): BookId? =
        suspendTransaction(db) {
            db.booksQueries
                .selectIdByNaturalKey(folderId.value, rootRelPath)
                .executeAsOneOrNull()
                ?.let { BookId(it) }
        }

    /**
     * Bulk natural-key resolve: returns a `root_rel_path → BookId` map for every existing book in
     * [folderId] whose `root_rel_path` is in [paths]. The batched counterpart to [findByPath] —
     * one [suspendTransaction] with an `IN (…)` query instead of a read txn per book. [paths] is
     * chunked under SQLite's bound-parameter ceiling so a large scan never overflows it.
     */
    suspend fun findExistingByPaths(
        folderId: FolderId,
        paths: Collection<String>,
    ): Map<String, BookId> {
        if (paths.isEmpty()) return emptyMap()
        return suspendTransaction(db) {
            paths
                .distinct()
                .chunked(SQLITE_IN_CHUNK)
                .flatMap { chunk ->
                    db.booksQueries.selectIdsByPaths(folderId.value, chunk).executeAsList()
                }.associate { it.root_rel_path to BookId(it.id) }
        }
    }

    /** A book an inode points at, with what is needed to decide whether the match can be trusted. */
    data class InodeCandidate(
        val bookId: BookId,
        val rootRelPath: String,
        val audioFileSizes: Set<Long>,
    )

    /**
     * Every book in [folderId] recorded with one of [inodes], grouped by inode. More than one book
     * per inode is possible (hardlinks) and is returned as such; choosing is the caller's job.
     * Tombstoned rows are included so a removed book found again at a new path keeps its identity.
     */
    suspend fun findInodeCandidates(
        folderId: FolderId,
        inodes: Collection<Long>,
    ): Map<Long, List<InodeCandidate>> {
        if (inodes.isEmpty()) return emptyMap()
        return suspendTransaction(db) {
            val rows =
                inodes
                    .distinct()
                    .chunked(SQLITE_IN_CHUNK)
                    .flatMap { chunk ->
                        db.booksQueries.selectInodeCandidates(folderId.value, chunk).executeAsList()
                    }
            val sizesByBook =
                rows
                    .map { it.id }
                    .distinct()
                    .chunked(SQLITE_IN_CHUNK)
                    .flatMap { chunk ->
                        db.bookAudioFilesQueries.selectSizesForBooks(chunk).executeAsList()
                    }.groupBy({ it.book_id }, { it.size })
            rows.groupBy(
                keySelector = { row -> checkNotNull(row.inode) { "matched by inode, so inode is set" } },
                valueTransform = { row ->
                    InodeCandidate(BookId(row.id), row.root_rel_path, sizesByBook[row.id].orEmpty().toSet())
                },
            )
        }
    }

    /**
     * Returns the full book aggregates for every book that has a junction row for
     * [contributorId] in `book_contributors`.
     *
     * Used by [com.calypsan.listenup.server.api.ContributorServiceImpl.listBooksByContributor].
     * Opens its own read transaction — independent of the substrate's orchestration.
     */
    suspend fun findByContributor(contributorId: ContributorId): List<BookSyncPayload> =
        suspendTransaction(db) {
            val bookIds =
                db.bookContributorsQueries
                    .bookIdsForContributor(contributorId.value)
                    .executeAsList()
            db.readBookPayloads(bookIds)
        }

    /**
     * Returns the full book aggregates for every book that has a membership row for
     * [seriesId] in `book_series_memberships`, in series-position order.
     *
     * Used by [com.calypsan.listenup.server.api.SeriesServiceImpl.listBooksBySeries].
     * Opens its own read transaction — independent of the substrate's orchestration.
     */
    suspend fun findBySeries(seriesId: SeriesId): List<BookSyncPayload> =
        suspendTransaction(db) {
            val bookIds =
                db.bookSeriesMembershipsQueries
                    .bookIdsForSeries(seriesId.value)
                    .executeAsList()
            db.readBookPayloads(bookIds)
        }
}
