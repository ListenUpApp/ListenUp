@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.api.dto.scanner.CandidateBook
import com.calypsan.listenup.api.dto.scanner.FileEntry
import com.calypsan.listenup.api.dto.scanner.FileType
import com.calypsan.listenup.api.dto.scanner.TrackEntry
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.domain.embeddedmeta.AudioFormat
import com.calypsan.listenup.domain.embeddedmeta.AudioTags
import com.calypsan.listenup.domain.embeddedmeta.ChapterSource
import com.calypsan.listenup.domain.embeddedmeta.EmbeddedAudioMetadata
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

/**
 * End-to-end proof that a multi-contributor author string survives the full ingest
 * path: [BookRepository.upsertFromAnalyzed] → `buildContributors` →
 * [ContributorRepository.resolveOrCreate] → junction rows → [BookRepository.findById]
 * returns two distinct contributor rows with the right name + role.
 *
 * Also proves the crown-jewel dedup invariant: two books whose author names differ only
 * in display order ("Brandon Sanderson" vs "Sanderson, Brandon") collapse to a single
 * contributor row through derivation alone, and that an embedded `authorsSort` tag
 * can bridge a divergent display name ("B. Sanderson") to the same canonical row.
 */
class ContributorParsingIngestE2ETest :
    FunSpec({

        test("contributor split persists through ingest into two distinct rows") {
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
                        exposedDb = db,
                        bus = bus,
                        registry = registry,
                        contributorRepository = contributors,
                        seriesRepository = series,
                        genreRepository = GenreRepository(db, bus, registry),
                    )
                runTest {
                    val analyzed = analyzedWith(authors = listOf("Stephen King; Joe Hill - Introduction"))

                    val result =
                        bookRepo.upsertFromAnalyzed(
                            BookId("c-parse-1"),
                            LibraryId("test-library"),
                            FolderId("test-folder"),
                            analyzed,
                        )
                    result.shouldBeInstanceOf<AppResult.Success<*>>()

                    val saved = bookRepo.findById(BookId("c-parse-1")).shouldNotBeNull()
                    val byName = saved.contributors.associateBy { it.name }
                    byName.keys shouldBe setOf("Stephen King", "Joe Hill")
                    byName.getValue("Stephen King").role shouldBe "author"
                    byName.getValue("Joe Hill").role shouldBe "introduction"
                }
            }
        }

        // Crown-jewel test 1: derivation-only convergence.
        //
        // "Sanderson, Brandon" normalises to display "Brandon Sanderson" via
        // ContributorParser.normalizeLastFirst, then SortKeys.sortName derives "Sanderson, Brandon"
        // for both books. normalizeForDedup produces "sanderson, brandon" in each case → one row.
        test("two books with cross-order author names share one contributor") {
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
                        exposedDb = db,
                        bus = bus,
                        registry = registry,
                        contributorRepository = contributors,
                        seriesRepository = series,
                        genreRepository = GenreRepository(db, bus, registry),
                    )
                runTest {
                    // Book A: display-order name, sortName derived as "Sanderson, Brandon".
                    bookRepo.upsertFromAnalyzed(
                        BookId("cross-order-a"),
                        LibraryId("test-library"),
                        FolderId("test-folder"),
                        analyzedWith(
                            authors = listOf("Brandon Sanderson"),
                            rootRelPath = "Sanderson/Way of Kings",
                        ),
                    )
                    // Book B: surname-first form; parser normalises display to "Brandon Sanderson",
                    // derivation produces the same "Sanderson, Brandon" sort key → same dedup bucket.
                    bookRepo.upsertFromAnalyzed(
                        BookId("cross-order-b"),
                        LibraryId("test-library"),
                        FolderId("test-folder"),
                        analyzedWith(
                            authors = listOf("Sanderson, Brandon"),
                            rootRelPath = "Sanderson/Words of Radiance",
                        ),
                    )

                    contributors.listLiveIds().size shouldBe 1
                }
            }
        }

        // Regression test: two distinct display names that resolve to the same contributor (via
        // an embedded authorsSort tag) must not crash the ingest transaction with a PK violation on
        // book_contributor(book_id, contributor_id, role).
        //
        // Scenario: authors = ["Brandon Sanderson", "B. Sanderson"] + authorsSort =
        // "Sanderson, Brandon; Sanderson, Brandon". buildContributors produces two payloads with
        // different names but the same sortName. Both pass resolveOrCreate, which deduplicates on
        // sortName and returns the same contributorId for both. replaceContributors must collapse
        // them to one junction row rather than attempting two inserts with identical (book_id,
        // contributor_id, role) — which would trigger SQLITE_CONSTRAINT_PRIMARYKEY.
        test("two author names resolving to one contributor collapse to one membership (no PK crash)") {
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
                        exposedDb = db,
                        bus = bus,
                        registry = registry,
                        contributorRepository = contributors,
                        seriesRepository = series,
                        genreRepository = GenreRepository(db, bus, registry),
                    )
                runTest {
                    // Two display names that both map to sortName "Sanderson, Brandon" via the
                    // embedded authorsSort tag, so resolveOrCreate returns the same id for both.
                    val analyzed =
                        analyzedWith(
                            authors = listOf("Brandon Sanderson", "B. Sanderson"),
                            authorsSort = "Sanderson, Brandon; Sanderson, Brandon",
                            rootRelPath = "Sanderson/Dup Contributors",
                        )

                    bookRepo
                        .upsertFromAnalyzed(
                            BookId("dup-contrib-book"),
                            LibraryId("test-library"),
                            FolderId("test-folder"),
                            analyzed,
                        ).shouldBeInstanceOf<AppResult.Success<*>>()

                    val book = bookRepo.findById(BookId("dup-contrib-book")).shouldNotBeNull()
                    // Exactly one author membership for the deduplicated contributor.
                    book.contributors.count { it.role == "author" } shouldBe 1
                    book.contributors
                        .filter { it.role == "author" }
                        .map { it.id }
                        .distinct()
                        .size shouldBe 1
                }
            }
        }

        // Crown-jewel test 2: embedded-tag path.
        //
        // "B. Sanderson" would derive sortName "Sanderson, B." (not "Sanderson, Brandon"),
        // so derivation alone would create two rows. The embedded authorsSort = "Sanderson, Brandon"
        // overrides derivation and bridges it to the same dedup key as Book A.
        test("embedded authorsSort converges a divergent display name to one contributor") {
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
                        exposedDb = db,
                        bus = bus,
                        registry = registry,
                        contributorRepository = contributors,
                        seriesRepository = series,
                        genreRepository = GenreRepository(db, bus, registry),
                    )
                runTest {
                    // Book A: canonical name, derives sortName "Sanderson, Brandon".
                    bookRepo.upsertFromAnalyzed(
                        BookId("embedded-sort-a"),
                        LibraryId("test-library"),
                        FolderId("test-folder"),
                        analyzedWith(
                            authors = listOf("Brandon Sanderson"),
                            rootRelPath = "Sanderson/Mistborn",
                        ),
                    )
                    // Book B: abbreviated display "B. Sanderson" would resolve to sortName
                    // "Sanderson, B." by derivation alone — wrong bucket. The embedded tag
                    // "Sanderson, Brandon" overrides derivation → same dedup key as Book A.
                    bookRepo.upsertFromAnalyzed(
                        BookId("embedded-sort-b"),
                        LibraryId("test-library"),
                        FolderId("test-folder"),
                        analyzedWith(
                            authors = listOf("B. Sanderson"),
                            authorsSort = "Sanderson, Brandon",
                            rootRelPath = "Sanderson/Elantris",
                        ),
                    )

                    contributors.listLiveIds().size shouldBe 1
                }
            }
        }
    })

/**
 * Builds a minimal [AnalyzedBook] with one audio file carrying the supplied raw
 * [authors] strings — left unsplit so the ingest path's `buildContributors` does
 * the splitting. When [authorsSort] is provided it is threaded into the embedded
 * metadata to exercise the sort-tag override path.
 *
 * [rootRelPath] must be unique per book within a library to avoid the
 * `(library_id, root_rel_path)` unique constraint on the books table.
 */
private fun analyzedWith(
    authors: List<String>,
    authorsSort: String? = null,
    rootRelPath: String = "King/Contributor Split",
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
        authors = authors,
        tracks = listOf(TrackEntry(file = file)),
        embedded =
            authorsSort?.let {
                EmbeddedAudioMetadata(
                    format = AudioFormat.Mp3,
                    durationMs = 0L,
                    tags =
                        AudioTags(
                            title = null,
                            subtitle = null,
                            authors = emptyList(),
                            narrators = emptyList(),
                            series = emptyList(),
                            genres = emptyList(),
                            description = null,
                            publisher = null,
                            publishedYear = null,
                            asin = null,
                            isbn = null,
                            language = null,
                            trackNumber = null,
                            discNumber = null,
                            custom = emptyMap(),
                            titleSort = null,
                            authorsSort = it,
                        ),
                    chapters = emptyList(),
                    chaptersSource = ChapterSource.None,
                    artwork = null,
                )
            },
    )
}
