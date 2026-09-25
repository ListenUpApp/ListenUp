@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.api.dto.scanner.CandidateBook
import com.calypsan.listenup.api.dto.scanner.FileEntry
import com.calypsan.listenup.api.dto.scanner.FileType
import com.calypsan.listenup.api.dto.scanner.SeriesEntry
import com.calypsan.listenup.api.dto.scanner.TrackEntry
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.IngestOutcome
import com.calypsan.listenup.server.services.LibraryRegistry
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.rootPrincipal
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/**
 * A series merge must survive a rescan. The scanner re-derives series names from the audio files on
 * every rescan; a book whose series came from those files (scan tier, not pinned by an edit or by
 * enrichment) is re-resolved by name each time. Drives the REAL scan path
 * ([BookRepository.resolveOrInsert]), because that is the only producer of scan-tier writes.
 */
class SeriesMergeRescanTest :
    FunSpec({

        test("a rescan keeps a merged book in the surviving series") {
            withSqlDatabase {
                val f = rescanFixture(sql, driver)
                runTest {
                    val libraryId = f.libraries.currentLibrary()
                    val scan = scanFor("King/DarkTower01", series = listOf(SeriesEntry("The Dark Tower (Bachman)", "1")))
                    val bookId = f.books.resolveOrInsert(libraryId, TEST_FOLDER, scan).resolved()
                    val sourceId =
                        SeriesId(
                            f.books
                                .findById(bookId)!!
                                .series
                                .single()
                                .id,
                        )
                    val targetId = f.series.resolveOrCreate("The Dark Tower")

                    f.service.mergeSeries(sourceId, targetId).shouldBeInstanceOf<AppResult.Success<Unit>>()
                    f.books.resolveOrInsert(libraryId, TEST_FOLDER, scan) // the same files, scanned again

                    f.books
                        .findById(bookId)!!
                        .series
                        .map { it.id } shouldBe listOf(targetId.value)
                    f.series
                        .findById(sourceId.value)
                        .shouldNotBeNull()
                        .deletedAt
                        .shouldNotBeNull()
                }
            }
        }
    })

private val TEST_FOLDER = FolderId("test-folder")

private fun AppResult<IngestOutcome>.resolved(): BookId =
    when (this) {
        is AppResult.Success -> data.bookId
        is AppResult.Failure -> error("resolveOrInsert failed: ${error.message}")
    }

private class RescanFixture(
    val books: BookRepository,
    val series: SeriesRepository,
    val libraries: LibraryRegistry,
    val service: SeriesServiceImpl,
)

private fun rescanFixture(
    sql: ListenUpDatabase,
    driver: app.cash.sqldelight.db.SqlDriver,
): RescanFixture {
    val bus = ChangeBus()
    val syncRegistry = SyncRegistry()
    val seriesRepo = SeriesRepository(sql, bus, syncRegistry)
    val bookRepo =
        BookRepository(
            db = sql,
            driver = driver,
            bus = bus,
            registry = syncRegistry,
            contributorRepository = ContributorRepository(sql, bus, syncRegistry),
            seriesRepository = seriesRepo,
            genreRepository = GenreRepository(sql, bus, syncRegistry),
        )
    val service =
        SeriesServiceImpl(
            seriesRepo = seriesRepo,
            bookRepo = bookRepo,
            sqlDb = sql,
            accessPolicy = BookAccessPolicy(sql, driver),
            principal = rootPrincipal(),
        )
    return RescanFixture(bookRepo, seriesRepo, LibraryRegistry(sql), service)
}

/** A minimal [AnalyzedBook] at [rootRelPath] carrying [series] as the files' own series tags. */
private fun scanFor(
    rootRelPath: String,
    series: List<SeriesEntry>,
): AnalyzedBook {
    val file =
        FileEntry(
            relPath = "$rootRelPath/01.m4b",
            name = "01.m4b",
            ext = "m4b",
            size = 1024L,
            mtimeMs = 0L,
            inode = rootRelPath.hashCode().toLong(),
            fileType = FileType.AUDIO,
        )
    return AnalyzedBook(
        candidate = CandidateBook(rootRelPath = rootRelPath, isFile = false, files = listOf(file)),
        title = rootRelPath.substringAfterLast('/'),
        series = series,
        tracks = listOf(TrackEntry(file = file)),
    )
}
