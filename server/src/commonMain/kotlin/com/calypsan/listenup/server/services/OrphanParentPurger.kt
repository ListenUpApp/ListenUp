package com.calypsan.listenup.server.services

import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.GenreId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.sync.MoodRepository
import com.calypsan.listenup.server.sync.TagRepository

/**
 * The parent ids a book was ever linked to — captured tombstone-inclusively before its removal so the
 * post-cascade orphan check knows which parents to re-evaluate, even on a crash-resume run where the
 * junctions are already tombstoned.
 */
class LinkedParents(
    val contributorIds: List<String>,
    val seriesIds: List<String>,
    val genreIds: List<String>,
    val tagIds: List<String>,
    val moodIds: List<String>,
)

/**
 * Deletion-based orphan purge for the metadata parents of a removed book (the user "nuke" directive).
 *
 * When a book is soft-deleted, a contributor / series / genre / tag / mood that was linked ONLY to
 * that book is now an orphan — a parent with zero live book children that would otherwise keep
 * appearing in browse lists with a phantom "0 books". This purger tombstones each such orphan so it
 * stops appearing; the parents are mirrored sync domains, so the tombstone propagates to clients.
 *
 * **Two-phase, by design.** [captureParents] reads the linked parent ids BEFORE the removal, and reads
 * them tombstone-inclusively — every parent EVER linked to the book, whether or not its junction is
 * still live. All five parent tables are read the same way: `book_contributors` /
 * `book_series_memberships` / `book_genres` are hard-replace child tables whose rows persist across the
 * book tombstone, and `book_tags` / `book_moods` are read via `tagIdsForBook` / `moodIdsForBook`, which
 * omit the `deleted_at IS NULL` guard. This makes capture resume-safe: a re-invoked `softDelete` after a
 * crash that already tombstoned the junctions still nominates the tags and moods. [purgeOrphaned] runs
 * AFTER the book + junction cascade and tombstones every captured parent whose live-book count has
 * dropped to zero — the live-book-count query (joining live books through live junctions) is the SOLE
 * decision-maker, so widening the capture can never wrongly purge a parent that still has any live
 * junction-book pair.
 *
 * **Revival note.** A remove-then-rescan resurrects purged parents: contributors and series are
 * revived IN PLACE by `resolveOrCreate` (a dedup hit on a tombstoned row clears `deleted_at`,
 * bumps the revision, and publishes `SyncEvent.Updated`, keeping the id stable). Genres, tags,
 * and moods are re-created as fresh rows instead — their slug lookups are live-only and their
 * slug unique indexes are partial (`WHERE deleted_at IS NULL`), so a new id is minted.
 */
class OrphanParentPurger(
    private val db: ListenUpDatabase,
    private val contributorRepository: ContributorRepository,
    private val seriesRepository: SeriesRepository,
    private val genreRepository: GenreRepository,
    private val tagRepository: TagRepository?,
    private val moodRepository: MoodRepository?,
) {
    /**
     * Reads the parent ids ever linked to [bookId] — the orphan-check candidate set. Tombstone-inclusive
     * across all five parent tables so a resume run (after a crash that already tombstoned the junctions)
     * still nominates every parent; the live-book-count query in [purgeOrphaned] is the purge decision.
     */
    suspend fun captureParents(bookId: String): LinkedParents =
        suspendTransaction(db) {
            LinkedParents(
                contributorIds = db.bookContributorsQueries.contributorIdsForBook(bookId).executeAsList(),
                seriesIds = db.bookSeriesMembershipsQueries.seriesIdsForBook(bookId).executeAsList(),
                genreIds = db.bookGenresQueries.genreIdsForBook(bookId).executeAsList(),
                tagIds = db.bookTagsQueries.tagIdsForBook(bookId).executeAsList(),
                moodIds = db.bookMoodsQueries.moodIdsForBook(bookId).executeAsList(),
            )
        }

    /** Tombstones every parent in [parents] that has zero live book children after the removal. */
    suspend fun purgeOrphaned(parents: LinkedParents) {
        for (id in parents.contributorIds) {
            if (liveBookCount { bookContributorsQueries.liveBookCountForContributor(id).executeAsOne() } == 0L) {
                contributorRepository.softDelete(ContributorId(id))
            }
        }
        for (id in parents.seriesIds) {
            if (liveBookCount { bookSeriesMembershipsQueries.liveBookCountForSeries(id).executeAsOne() } == 0L) {
                seriesRepository.softDelete(SeriesId(id))
            }
        }
        for (id in parents.genreIds) {
            if (liveBookCount { bookGenresQueries.liveBookCountForGenre(id).executeAsOne() } == 0L) {
                genreRepository.softDelete(GenreId(id))
            }
        }
        tagRepository?.let { repo ->
            for (id in parents.tagIds) {
                if (liveBookCount { bookTagsQueries.liveBookCountForTag(id).executeAsOne() } == 0L) {
                    repo.softDelete(id)
                }
            }
        }
        moodRepository?.let { repo ->
            for (id in parents.moodIds) {
                if (liveBookCount { bookMoodsQueries.liveBookCountForMood(id).executeAsOne() } == 0L) {
                    repo.softDelete(id)
                }
            }
        }
    }

    private suspend fun liveBookCount(query: ListenUpDatabase.() -> Long): Long = suspendTransaction(db) { db.query() }
}
