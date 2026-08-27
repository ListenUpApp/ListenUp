@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.api.dto.scanner.CandidateBook
import com.calypsan.listenup.api.dto.scanner.FileEntry
import com.calypsan.listenup.api.dto.scanner.FileType
import com.calypsan.listenup.api.dto.scanner.SidecarCuration
import com.calypsan.listenup.api.dto.scanner.SidecarCurationChapter
import com.calypsan.listenup.api.dto.scanner.TrackEntry
import com.calypsan.listenup.api.metadata.BookField
import com.calypsan.listenup.api.metadata.FieldProvenance
import com.calypsan.listenup.api.metadata.FieldSourceKind
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.ChapterSource
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/**
 * `BookRepository.withSidecarCuration` — how a re-ingested `listenup.json` folds into the stored
 * provenance map.
 *
 * The rule is the same one the rest of the pipeline obeys, applied per field: **max tier wins.**
 * That is what makes the sidecar correct in both directions at once. After a database wipe the scan
 * payload carries only tier-0 entries, so every recorded USER/ENRICHMENT entry wins and the curation
 * comes back *at the tier it was recorded at* — which is what stops the very next scan from
 * re-deriving the field from the files. Against a live database, a stale sidecar can never demote a
 * value that already out-ranks it.
 *
 * A restore-at-tier-0 would satisfy the first half and fail the second; a restore-at-USER would
 * satisfy the second and let a stale file beat a fresh edit from another device. Neither shortcut
 * survives both tests below.
 */
class SidecarCurationMergeTest :
    FunSpec({

        test("a DB-wipe restore brings a USER field back at USER tier, and the next scan can't overwrite it") {
            withSqlDatabase {
                val (repo, registry) = curationRepository(sql, driver)
                runTest {
                    val libId = registry.currentLibrary()
                    val path = "Sanderson/Mistborn"

                    // Fresh database: the first scan sees the sidecar's title AND its recorded provenance.
                    val id =
                        repo
                            .resolveOrInsert(
                                libId,
                                TEST_FOLDER,
                                scanFor(
                                    path,
                                    title = "The Final Empire",
                                    curation = curation(BookField.TITLE to userAt(7L)),
                                ),
                            ).resolved()

                    repo.findById(id)!!.fieldProvenance[BookField.TITLE] shouldBe userAt(7L)

                    // The next scan re-derives a different title from the files — the USER tier holds.
                    repo.resolveOrInsert(libId, TEST_FOLDER, scanFor(path, title = "Mistborn 01"))

                    repo.findById(id)!!.title shouldBe "The Final Empire"
                }
            }
        }

        test("a stale sidecar does NOT beat a higher-tier stored value") {
            withSqlDatabase {
                val (repo, registry) = curationRepository(sql, driver)
                runTest {
                    val libId = registry.currentLibrary()
                    val path = "Sanderson/Stale"
                    val id =
                        repo.resolveOrInsert(libId, TEST_FOLDER, scanFor(path, publisher = "Tor V1")).resolved()

                    // The user edits the title in the app — the top tier, stamped now.
                    repo.upsert(
                        repo.findById(id)!!.copy(
                            title = "User Title",
                            fieldProvenance = mapOf(BookField.TITLE to userAt(100L)),
                        ),
                    )

                    // A stale sidecar on disk still claims the title at the ENRICHMENT tier. The
                    // publisher change makes the rescan a real write, so nothing is proved by a skip.
                    repo.resolveOrInsert(
                        libId,
                        TEST_FOLDER,
                        scanFor(
                            path,
                            title = "Files Title",
                            publisher = "Tor V2",
                            curation =
                                curation(
                                    BookField.TITLE to
                                        FieldProvenance(FieldSourceKind.ENRICHMENT, provider = "audible", at = 5L),
                                ),
                        ),
                    )

                    val readback = repo.findById(id)!!
                    readback.title shouldBe "User Title"
                    readback.fieldProvenance[BookField.TITLE] shouldBe userAt(100L)
                    readback.publisher shouldBe "Tor V2" // unprotected — the write really happened
                }
            }
        }

        test("a sidecar entry that out-ranks the scan's tier-0 entry wins, and protects the field after") {
            withSqlDatabase {
                val (repo, registry) = curationRepository(sql, driver)
                runTest {
                    val libId = registry.currentLibrary()
                    val path = "Sanderson/Enriched"
                    val id = repo.resolveOrInsert(libId, TEST_FOLDER, scanFor(path, title = "Scan Title")).resolved()

                    val enriched = FieldProvenance(FieldSourceKind.ENRICHMENT, provider = "audible", at = 5L)
                    repo.resolveOrInsert(
                        libId,
                        TEST_FOLDER,
                        scanFor(path, title = "Sidecar Title", curation = curation(BookField.TITLE to enriched)),
                    )

                    repo.findById(id)!!.fieldProvenance[BookField.TITLE] shouldBe enriched

                    // Tier 1 out-ranks a scan, so a later pass over the files leaves the title alone.
                    repo.resolveOrInsert(libId, TEST_FOLDER, scanFor(path, title = "Files Title"))

                    repo.findById(id)!!.title shouldBe "Sidecar Title"
                }
            }
        }

        test("sidecar USER chapters persist with chapterSource USER") {
            withSqlDatabase {
                val (repo, registry) = curationRepository(sql, driver)
                runTest {
                    val libId = registry.currentLibrary()
                    val path = "Sanderson/Chapters"
                    val id =
                        repo
                            .resolveOrInsert(
                                libId,
                                TEST_FOLDER,
                                scanFor(
                                    path,
                                    curation =
                                        SidecarCuration(
                                            userChapters =
                                                listOf(
                                                    SidecarCurationChapter(title = "Prelude", startMs = 0L),
                                                    SidecarCurationChapter(title = "Chapter One", startMs = 4_000L),
                                                ),
                                        ),
                                ),
                            ).resolved()

                    val readback = repo.findById(id)!!
                    readback.chapterSource shouldBe ChapterSource.USER
                    readback.chapters.map { it.title } shouldBe listOf("Prelude", "Chapter One")
                }
            }
        }

        test("no sidecar leaves the merged provenance exactly as the scan produced it") {
            withSqlDatabase {
                val (repo, registry) = curationRepository(sql, driver)
                runTest {
                    val libId = registry.currentLibrary()
                    val id =
                        repo.resolveOrInsert(libId, TEST_FOLDER, scanFor("Sanderson/NoSidecar")).resolved()

                    repo.findById(id)!!.fieldProvenance shouldBe emptyMap()
                }
            }
        }
    })

private val TEST_FOLDER = FolderId("test-folder")

/** A USER-tier stamp at [at] — the shape a hand edit records. */
private fun userAt(at: Long) = FieldProvenance(FieldSourceKind.USER, at = at)

/** A [SidecarCuration] carrying just the given per-field provenance entries. */
private fun curation(vararg entries: Pair<BookField, FieldProvenance>) = SidecarCuration(fieldProvenance = entries.toMap())

private fun AppResult<IngestOutcome>.resolved(): BookId =
    when (this) {
        is AppResult.Success -> data.bookId
        is AppResult.Failure -> error("resolveOrInsert failed: ${error.message}")
    }

private fun curationRepository(
    sql: ListenUpDatabase,
    driver: app.cash.sqldelight.db.SqlDriver,
): Pair<BookRepository, LibraryRegistry> {
    val registry = LibraryRegistry(sql)
    val bus = ChangeBus()
    val syncRegistry = SyncRegistry()
    val repo =
        BookRepository(
            db = sql,
            driver = driver,
            bus = bus,
            registry = syncRegistry,
            contributorRepository = ContributorRepository(sql, bus, syncRegistry),
            seriesRepository = SeriesRepository(sql, bus, syncRegistry),
            genreRepository = GenreRepository(sql, bus, syncRegistry),
        )
    return repo to registry
}

/**
 * A minimal [AnalyzedBook] anchored at [rootRelPath], optionally carrying the [curation] a
 * `listenup.json` beside the book would have produced. Its own [AnalyzedBook.fieldProvenance] is
 * empty — the scan write's authority is tier 0 regardless, which is exactly the state a
 * post-wipe first scan is in.
 */
private fun scanFor(
    rootRelPath: String,
    title: String = rootRelPath.substringAfterLast('/'),
    publisher: String? = null,
    curation: SidecarCuration? = null,
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
        title = title,
        publisher = publisher,
        tracks = listOf(TrackEntry(file = file)),
        sidecarCuration = curation,
    )
}
