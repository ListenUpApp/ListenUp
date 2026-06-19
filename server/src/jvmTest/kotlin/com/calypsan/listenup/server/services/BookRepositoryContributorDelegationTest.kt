@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.api.dto.scanner.CandidateBook
import com.calypsan.listenup.api.dto.scanner.FileEntry
import com.calypsan.listenup.api.dto.scanner.FileType
import com.calypsan.listenup.api.dto.scanner.SeriesEntry
import com.calypsan.listenup.api.dto.scanner.TrackEntry
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import com.calypsan.listenup.server.testing.asSqlDatabase

class BookRepositoryContributorDelegationTest :
    FunSpec({

        test("upsertFromAnalyzed resolves the author through ContributorRepository") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val contributors = ContributorRepository(db.asSqlDatabase(), bus, registry)
                val series = SeriesRepository(db.asSqlDatabase(), bus, registry)
                val bookRepo =
                    BookRepository(
                        db = db,
                        bus = bus,
                        registry = registry,
                        contributorRepository = contributors,
                        seriesRepository = series,
                        genreRepository = GenreRepository(db, bus, registry),
                    )
                runTest {
                    val analyzed = analyzedFor("Sanderson/Way of Kings", author = "Brandon Sanderson")

                    val result =
                        bookRepo.upsertFromAnalyzed(
                            BookId("b1"),
                            LibraryId("test-library"),
                            FolderId("test-folder"),
                            analyzed,
                        )
                    result.shouldBeInstanceOf<AppResult.Success<BookSyncPayload>>()

                    contributors
                        .findById(contributors.resolveOrCreate("Brandon Sanderson", sortName = null).value)
                        .shouldNotBeNull()
                }
            }
        }

        test("upsertFromAnalyzed resolves the series through SeriesRepository") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val contributors = ContributorRepository(db.asSqlDatabase(), bus, registry)
                val series = SeriesRepository(db.asSqlDatabase(), bus, registry)
                val bookRepo =
                    BookRepository(
                        db = db,
                        bus = bus,
                        registry = registry,
                        contributorRepository = contributors,
                        seriesRepository = series,
                        genreRepository = GenreRepository(db, bus, registry),
                    )
                runTest {
                    val analyzed =
                        analyzedFor(
                            "Sanderson/Way of Kings",
                            author = "Brandon Sanderson",
                            seriesName = "The Stormlight Archive",
                        )

                    bookRepo.upsertFromAnalyzed(
                        BookId("b1"),
                        LibraryId("test-library"),
                        FolderId("test-folder"),
                        analyzed,
                    )

                    series
                        .findById(series.resolveOrCreate("The Stormlight Archive").value)
                        .shouldNotBeNull()

                    val saved = bookRepo.findById(BookId("b1")).shouldNotBeNull()
                    saved.series.single().name shouldBe "The Stormlight Archive"
                }
            }
        }
    })

/**
 * Builds a minimal [AnalyzedBook] anchored at [rootRelPath] with one audio file,
 * one [author], and optionally one series membership ([seriesName]).
 */
private fun analyzedFor(
    rootRelPath: String,
    author: String,
    seriesName: String? = null,
): AnalyzedBook {
    val file =
        FileEntry(
            relPath = "$rootRelPath/01.m4b",
            name = "01.m4b",
            ext = "m4b",
            size = 1024L,
            mtimeMs = 0L,
            inode = null,
            fileType = FileType.AUDIO,
        )
    return AnalyzedBook(
        candidate =
            CandidateBook(
                rootRelPath = rootRelPath,
                isFile = false,
                files = listOf(file),
            ),
        title = rootRelPath.substringAfterLast('/'),
        authors = listOf(author),
        series = seriesName?.let { listOf(SeriesEntry(name = it, sequence = "1")) } ?: emptyList(),
        tracks = listOf(TrackEntry(file = file)),
    )
}
