package com.calypsan.listenup.server.services

import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.GenreId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.sync.BookMoodRepository
import com.calypsan.listenup.server.sync.BookTagRepository
import com.calypsan.listenup.server.sync.MoodRepository
import com.calypsan.listenup.server.sync.TagRepository

/**
 * The parent ids a book was linked to at the moment of its removal — captured while the book and
 * its junctions are still live so the post-cascade orphan check knows which parents to re-evaluate.
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
 * **Two-phase, by design.** [captureParents] reads the linked parent ids BEFORE the removal (the
 * `book_tags` / `book_moods` junctions are tombstoned by the book cascade, so their ids must be read
 * while still live; `book_contributors` / `book_series_memberships` / `book_genres` are hard-replace
 * child tables whose rows persist, but capturing all five together keeps the contract uniform).
 * [purgeOrphaned] runs AFTER the book + junction cascade and tombstones every captured parent whose
 * live-book count has dropped to zero. Each count joins live books, so a lingering junction to the
 * now-dead book never keeps a parent alive.
 *
 * **Revival note.** A purged parent is not explicitly revived; a remove-then-rescan re-ingests the
 * book through `resolveOrCreate`, which resurrects the parent, so memberships return on re-scan.
 */
class OrphanParentPurger(
    private val db: ListenUpDatabase,
    private val contributorRepository: ContributorRepository,
    private val seriesRepository: SeriesRepository,
    private val genreRepository: GenreRepository,
    private val tagRepository: TagRepository?,
    private val moodRepository: MoodRepository?,
    private val bookTagRepository: BookTagRepository?,
    private val bookMoodRepository: BookMoodRepository?,
) {
    /** Reads the parent ids linked to [bookId] while it is still live — the orphan-check candidate set. */
    suspend fun captureParents(bookId: String): LinkedParents {
        val (contributorIds, seriesIds, genreIds) =
            suspendTransaction(db) {
                Triple(
                    db.bookContributorsQueries.contributorIdsForBook(bookId).executeAsList(),
                    db.bookSeriesMembershipsQueries.seriesIdsForBook(bookId).executeAsList(),
                    db.bookGenresQueries.genreIdsForBook(bookId).executeAsList(),
                )
            }
        // Tag/mood ids come from the junction repos' live reads (their `id` column is synthetic).
        val tagIds = bookTagRepository?.findAllForBook(bookId)?.map { it.tagId }.orEmpty()
        val moodIds = bookMoodRepository?.findAllForBook(bookId)?.map { it.moodId }.orEmpty()
        return LinkedParents(contributorIds, seriesIds, genreIds, tagIds, moodIds)
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
