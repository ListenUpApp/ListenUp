package com.calypsan.listenup.server.matching.apply

import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.api.dto.match.BookCandidateKey
import com.calypsan.listenup.api.dto.match.BookMatchApply
import com.calypsan.listenup.api.dto.match.BookMatchReview
import com.calypsan.listenup.api.dto.match.ExternalRef
import com.calypsan.listenup.api.dto.match.FieldDecision
import com.calypsan.listenup.api.dto.match.ImageChoice
import com.calypsan.listenup.api.dto.match.LabelSetChange
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookChapterPayload
import com.calypsan.listenup.api.sync.BookContributorPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.cover.CoverImageStore
import com.calypsan.listenup.server.matching.review.AUDIBLE
import com.calypsan.listenup.server.matching.review.BookReviewer
import com.calypsan.listenup.server.matching.review.FakeCatalogProvider
import com.calypsan.listenup.server.matching.review.GenreLabelIdentity
import com.calypsan.listenup.server.matching.review.HARDCOVER
import com.calypsan.listenup.server.matching.review.core
import com.calypsan.listenup.server.matching.undo.MatchReceiptStore
import com.calypsan.listenup.server.matching.undo.MatchUndoer
import com.calypsan.listenup.server.media.ImageStore
import com.calypsan.listenup.server.metadata.EnrichmentCoordinator
import com.calypsan.listenup.server.metadata.ImageStorage
import com.calypsan.listenup.server.metadata.spi.ChapterListMeta
import com.calypsan.listenup.server.metadata.spi.ChapterMeta
import com.calypsan.listenup.server.metadata.spi.CoverMeta
import com.calypsan.listenup.server.metadata.spi.EnrichmentRoutes
import com.calypsan.listenup.server.metadata.spi.MetadataProviderRegistry
import com.calypsan.listenup.server.services.BookMoodWriter
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.BookTagWriter
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.GenreAutoCreator
import com.calypsan.listenup.server.services.GenreHierarchyFromLadder
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.sync.BookMoodRepository
import com.calypsan.listenup.server.sync.BookTagRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.MoodRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.sync.TagRepository
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.bookPayloadFixture
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.shouldSucceed
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.nio.file.Files
import kotlin.time.Clock
import kotlinx.io.files.Path

internal val US = MetadataLocale("us")
internal const val BOOK = "phm"
internal val KEY = BookCandidateKey(listOf(ExternalRef("audible", "B0X", "us"), ExternalRef("hardcover", "77")))
internal const val AUDIBLE_COVER = "https://example.test/audible.jpg"

/** A JPEG's magic number and enough padding to pass the cover store's sniff. */
internal val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(32) { it.toByte() }

/**
 * Match details' Apply and Undo over a real database: real repositories, real catalogues of contributors,
 * series, genres and moods, fake Audible and Hardcover catalogues, and covers served by a mock HTTP engine
 * (any URL containing "broken" answers 404).
 */
internal class MatchRig(
    val db: SqlTestDatabases,
) {
    val bus = ChangeBus()
    private val sync = SyncRegistry()
    val contributors = ContributorRepository(db.sql, bus, sync)
    val series = SeriesRepository(db.sql, bus, sync)
    val genres = GenreRepository(db.sql, bus, sync)
    val books = BookRepository(db.sql, bus, sync, db.driver, contributors, series, genres)
    val moods = MoodRepository(db.sql, bus, sync)
    val bookMoods = BookMoodRepository(db.sql, bus, sync, driver = db.driver)
    val tags = TagRepository(db.sql, bus, sync)
    val bookTags = BookTagRepository(db.sql, bus, sync, driver = db.driver)
    val moodWriter = BookMoodWriter(Clock.System, moods, bookMoods)
    val home: String = Files.createTempDirectory("match-home-").also { it.toFile().deleteOnExit() }.toString()
    val coversDir: String = "$home/covers"
    val coverStore = CoverImageStore(ImageStore(Path(coversDir), 10L * 1024 * 1024))

    val audible =
        FakeCatalogProvider(
            AUDIBLE,
            core =
                AppResult.Success(
                    core(
                        title = "Project Hail Mary",
                        description = "New description.",
                        publisher = "Ballantine",
                        releaseDate = "2021-05-04",
                        authors = listOf("Andy Weir"),
                        narrators = listOf("Ray Porter"),
                    ),
                ),
            covers = listOf(CoverMeta(AUDIBLE_COVER, sourceKey = "B0X")),
            genres = listOf("Science Fiction", "Fantasy"),
            ladders = listOf(listOf("Fiction", "Science Fiction")),
            chapters =
                ChapterListMeta(
                    listOf(ChapterMeta("Opening", 0), ChapterMeta("Chapter 2", 1_000), ChapterMeta("Finale", 2_000)),
                    accurate = true,
                ),
        )
    val hardcover = FakeCatalogProvider(HARDCOVER, moods = listOf("Tense", "Hopeful"))
    val coordinator =
        EnrichmentCoordinator(MetadataProviderRegistry(listOf(audible, hardcover)), EnrichmentRoutes.DEFAULT)
    val reviewer =
        BookReviewer(
            coordinator = coordinator,
            probe = { null },
            genreIdentity = { book -> GenreLabelIdentity(db.sql, book.genres.map { it.id }.toSet()) },
            currentMoods = { moodWriter.currentMoods(BookId(it)) },
            displayName = { null },
        )
    val receipts = MatchReceiptStore(db.sql)
    var fault: () -> Unit = {}
    private val imageStorage =
        ImageStorage(
            HttpClient(
                MockEngine { request ->
                    if ("broken" in request.url.toString()) {
                        respond(ByteArray(0), HttpStatusCode.NotFound)
                    } else {
                        respond(JPEG, HttpStatusCode.OK, headersOf("Content-Type", "image/jpeg"))
                    }
                },
            ),
        )
    val preparer =
        MatchPreparer(
            catalogs =
                MatchCatalogs(
                    contributorId = { contributors.resolveOrCreate(it, sortName = null).value },
                    seriesId = { series.resolveOrCreate(it).value },
                    genreIds = { books.resolveGenreIds(it) },
                    ladderRungs = { GenreHierarchyFromLadder(db.sql, genres, GenreAutoCreator(genres)).ensureLadder(it) },
                    moodId = { moodWriter.resolveMoodId(it) },
                ),
            covers = MatchCoverFiles(imageStorage, coverStore),
            now = { 1_000L },
        )
    val writer = BookMatchWriter(db.sql, books, bookMoods, receipts, { 1_000L }, beforeCommit = { fault() })
    val applier = BookMatchApplier(reviewer, coordinator, preparer, writer)
    val undoer = MatchUndoer(db.sql, books, bookMoods, receipts) { 2_000L }

    /** Project Hail Mary as you have it: an old description, two authors, Fantasy, Hopeful, a Heist tag. */
    suspend fun seedBook(): BookSyncPayload {
        db.sql.seedTestLibraryAndFolder()
        val weir = contributors.resolveOrCreate("Andy Weir", null).value
        val other = contributors.resolveOrCreate("Ghost Writer", null).value
        val narrator = contributors.resolveOrCreate("Old Narrator", null).value
        books.upsert(
            bookPayloadFixture(
                id = BOOK,
                title = "Project Hail Mary",
                contributors =
                    listOf(
                        BookContributorPayload(weir, "Andy Weir", null, ContributorRole.AUTHOR.apiValue, null),
                        BookContributorPayload(other, "Ghost Writer", null, ContributorRole.AUTHOR.apiValue, null),
                        BookContributorPayload(narrator, "Old Narrator", null, ContributorRole.NARRATOR.apiValue, null),
                    ),
                chapters =
                    listOf(
                        BookChapterPayload("c1", "Chapter 1", 1_000, 0),
                        BookChapterPayload("c2", "Chapter 2", 1_000, 1_000),
                        BookChapterPayload("c3", "Chapter 3", 1_000, 2_000),
                    ),
            ).copy(description = "Old description.", publishYear = 2020, asin = "B0OLD"),
        ).shouldSucceed()
        books.setBookGenres(BookId(BOOK), listOf("Fantasy")).shouldSucceed()
        moodWriter.writeMoods(BookId(BOOK), listOf("Hopeful"))
        BookTagWriter(Clock.System, tags, bookTags).setBookTags(BookId(BOOK), listOf("Heist"))
        books.touchRevision(BookId(BOOK)).shouldSucceed()
        return book()
    }

    suspend fun book(): BookSyncPayload = books.findById(BookId(BOOK))!!

    suspend fun review(): BookMatchReview = reviewer.review(book(), KEY, US).shouldSucceed().review

    /** Everything Review ticks by default, the Audible cover, Science Fiction for Fantasy, Tense for Hopeful, two chapters. */
    suspend fun fullRequest(coverUrl: String = AUDIBLE_COVER): BookMatchApply {
        val review = review()
        return BookMatchApply(
            candidate = KEY,
            region = US,
            basedOnRevision = review.basedOnRevision,
            fields = review.fields.map { FieldDecision(it.field, it.defaultChoice) },
            cover = ImageChoice.Candidate(review.cover.options.first { it.url == coverUrl }.optionId),
            genres = LabelSetChange(add = listOf("Science Fiction"), remove = listOf("Fantasy")),
            moods = LabelSetChange(add = listOf("Tense"), remove = listOf("Hopeful")),
            chapterOrdinals = listOf(0, 2),
        )
    }

    suspend fun moodNames(): List<String> = moodWriter.currentMoods(BookId(BOOK)).map { it.name }

    suspend fun tagNames(): List<String> = bookTags.findAllForBook(BOOK).mapNotNull { tags.findById(it.tagId)?.name }

    /** The rig's Review, Apply and Undo, as the matching service takes them. */
    fun details() =
        com.calypsan.listenup.server.api.MatchDetails(
            reviewer,
            applier,
            undoer,
            receipts,
            com.calypsan.listenup.server.matching.person
                .PersonRig(db)
                .people(),
        )

    fun coverColumns() = db.sql.booksQueries.selectCoverColumnsById(BOOK).executeAsOne()
}
