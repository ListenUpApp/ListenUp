package com.calypsan.listenup.server.di

import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.currentEpochMilliseconds
import com.calypsan.listenup.server.api.MatchDetails
import com.calypsan.listenup.server.cover.CoverImageStore
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.matching.apply.BookMatchApplier
import com.calypsan.listenup.server.matching.apply.BookMatchWriter
import com.calypsan.listenup.server.matching.apply.MatchCatalogs
import com.calypsan.listenup.server.matching.apply.MatchCoverFiles
import com.calypsan.listenup.server.matching.apply.MatchPreparer
import com.calypsan.listenup.server.matching.review.BookReviewer
import com.calypsan.listenup.server.matching.review.GenreLabelIdentity
import com.calypsan.listenup.server.matching.undo.MatchReceiptStore
import com.calypsan.listenup.server.matching.undo.MatchUndoer
import com.calypsan.listenup.server.metadata.EnrichmentCoordinator
import com.calypsan.listenup.server.metadata.ImageStorage
import com.calypsan.listenup.server.metadata.itunes.ImageDimensionProbe
import com.calypsan.listenup.server.ratings.ExternalRatingsFetcher
import com.calypsan.listenup.server.services.BookMoodWriter
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.GenreAutoCreator
import com.calypsan.listenup.server.services.GenreHierarchyFromLadder
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.SeriesRepository
import org.koin.core.module.Module

/**
 * Match details' Review, Apply and Undo (matching redesign PR 3): the reviewer, the one-transaction applier and
 * writer, the receipts and the undoer, bundled as [MatchDetails] for the matching service.
 */
internal fun Module.matchDetailsBindings() {
    single { MatchReceiptStore(get<ListenUpDatabase>()) }
    single {
        val db = get<ListenUpDatabase>()
        val moodWriter = get<BookMoodWriter>()
        val probe = get<ImageDimensionProbe>()
        BookReviewer(
            coordinator = get<EnrichmentCoordinator>(),
            probe = { url -> probe.probe(url) },
            genreIdentity = { book -> GenreLabelIdentity(db, book.genres.map { it.id }.toSet()) },
            currentMoods = { id -> moodWriter.currentMoods(BookId(id)) },
            displayName = { userId ->
                suspendTransaction(db) { db.usersQueries.selectDisplayNameById(userId).executeAsOneOrNull() }
            },
        )
    }
    single {
        val db = get<ListenUpDatabase>()
        val books = get<BookRepository>()
        val moodWriter = get<BookMoodWriter>()
        val contributors = get<ContributorRepository>()
        val series = get<SeriesRepository>()
        val genres = get<GenreRepository>()
        val ladders = GenreHierarchyFromLadder(db, genres, GenreAutoCreator(genres))
        val ratings = get<ExternalRatingsFetcher>()
        val receipts = get<MatchReceiptStore>()
        MatchDetails(
            reviewer = get<BookReviewer>(),
            applier =
                BookMatchApplier(
                    reviewer = get<BookReviewer>(),
                    coordinator = get<EnrichmentCoordinator>(),
                    preparer =
                        MatchPreparer(
                            catalogs =
                                MatchCatalogs(
                                    contributorId = { contributors.resolveOrCreate(it, sortName = null).value },
                                    seriesId = { series.resolveOrCreate(it).value },
                                    genreIds = { books.resolveGenreIds(it) },
                                    ladderRungs = { ladders.ensureLadder(it) },
                                    moodId = { moodWriter.resolveMoodId(it) },
                                ),
                            covers = MatchCoverFiles(get<ImageStorage>(), get<CoverImageStore>()),
                            now = ::currentEpochMilliseconds,
                        ),
                    writer = BookMatchWriter(db, books, moodWriter.bookMoodRepository, receipts, ::currentEpochMilliseconds),
                    ratingsRefresh = { id, locale -> ratings.fetch(id, locale, refresh = true) },
                ),
            undoer = MatchUndoer(db, books, moodWriter.bookMoodRepository, receipts, ::currentEpochMilliseconds),
            receipts = receipts,
        )
    }
}
