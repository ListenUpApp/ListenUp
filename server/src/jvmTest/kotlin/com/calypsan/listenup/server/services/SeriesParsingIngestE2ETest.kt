@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.api.dto.scanner.CandidateBook
import com.calypsan.listenup.api.dto.scanner.FileEntry
import com.calypsan.listenup.api.dto.scanner.FileType
import com.calypsan.listenup.api.dto.scanner.SeriesEntry
import com.calypsan.listenup.api.dto.scanner.TrackEntry
import com.calypsan.listenup.api.result.AppResult
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
import com.calypsan.listenup.server.testing.asSqlDriver

/**
 * End-to-end proof that a book tagged with multiple series survives the full
 * ingest path: [BookRepository.upsertFromAnalyzed] → `buildSeries` →
 * [SeriesRepository.resolveOrCreate] → junction rows →
 * [BookRepository.findById] returns two distinct series memberships with the
 * correct names and sequence numbers.
 */
class SeriesParsingIngestE2ETest :
    FunSpec({

        test("a book tagged with the same series twice collapses to one membership (no PK crash)") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val contributors = ContributorRepository(db.asSqlDatabase(), bus, registry)
                val series = SeriesRepository(db.asSqlDatabase(), bus, registry)
                val bookRepo =
                    BookRepository(
                        db = db.asSqlDatabase(),
                        driver = db.asSqlDriver(),
                        exposedDb = db,
                        bus = bus,
                        registry = registry,
                        contributorRepository = contributors,
                        seriesRepository = series,
                        genreRepository = GenreRepository(db.asSqlDatabase(), bus, registry),
                    )
                runTest {
                    val analyzed =
                        analyzedWithSeries(
                            series =
                                listOf(
                                    SeriesEntry("Cosmere", "1"),
                                    SeriesEntry("Cosmere", "2"),
                                ),
                            rootRelPath = "Sanderson/Cosmere Dup",
                        )

                    val result =
                        bookRepo.upsertFromAnalyzed(
                            BookId("dup-series-book"),
                            LibraryId("test-library"),
                            FolderId("test-folder"),
                            analyzed,
                        )
                    result.shouldBeInstanceOf<AppResult.Success<*>>()

                    val saved = bookRepo.findById(BookId("dup-series-book")).shouldNotBeNull()
                    saved.series.size shouldBe 1
                    saved.series.single().name shouldBe "Cosmere"
                    saved.series.single().sequence shouldBe "1"
                }
            }
        }

        test("a book tagged with two series resolves to two series memberships") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val contributors = ContributorRepository(db.asSqlDatabase(), bus, registry)
                val series = SeriesRepository(db.asSqlDatabase(), bus, registry)
                val bookRepo =
                    BookRepository(
                        db = db.asSqlDatabase(),
                        driver = db.asSqlDriver(),
                        exposedDb = db,
                        bus = bus,
                        registry = registry,
                        contributorRepository = contributors,
                        seriesRepository = series,
                        genreRepository = GenreRepository(db.asSqlDatabase(), bus, registry),
                    )
                runTest {
                    val analyzed =
                        analyzedWithSeries(
                            series =
                                listOf(
                                    SeriesEntry("Cosmere", "3"),
                                    SeriesEntry("Stormlight", "4"),
                                ),
                        )

                    val result =
                        bookRepo.upsertFromAnalyzed(
                            BookId("multi-series-book"),
                            LibraryId("test-library"),
                            FolderId("test-folder"),
                            analyzed,
                        )
                    result.shouldBeInstanceOf<AppResult.Success<*>>()

                    val saved = bookRepo.findById(BookId("multi-series-book")).shouldNotBeNull()
                    val byName = saved.series.associateBy { it.name }
                    byName.keys shouldBe setOf("Cosmere", "Stormlight")
                    byName.getValue("Cosmere").sequence shouldBe "3"
                    byName.getValue("Stormlight").sequence shouldBe "4"
                }
            }
        }
    })

/**
 * Builds a minimal [AnalyzedBook] with one audio file carrying the supplied
 * [series] list — left unresolved so the ingest path's `buildSeries` does
 * the resolution. [rootRelPath] must be unique per book within a library to
 * avoid the `(library_id, root_rel_path)` unique constraint on the books table.
 */
private fun analyzedWithSeries(
    series: List<SeriesEntry>,
    rootRelPath: String = "Sanderson/Cosmere Omnibus",
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
        series = series,
        tracks = listOf(TrackEntry(file = file)),
    )
}
