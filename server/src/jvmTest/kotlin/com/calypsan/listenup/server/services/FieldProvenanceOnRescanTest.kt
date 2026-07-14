@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.api.dto.scanner.CandidateBook
import com.calypsan.listenup.api.dto.scanner.FileEntry
import com.calypsan.listenup.api.dto.scanner.FileType
import com.calypsan.listenup.api.dto.scanner.SeriesEntry
import com.calypsan.listenup.api.dto.scanner.TrackEntry
import com.calypsan.listenup.api.metadata.BookField
import com.calypsan.listenup.api.metadata.FieldProvenance
import com.calypsan.listenup.api.metadata.FieldSourceKind
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookContributorPayload
import com.calypsan.listenup.api.sync.BookSeriesPayload
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
 * The rescan tier matrix — the compliance oracle for `BookRepository.mergeByProvenance`.
 *
 * THE ONE WRITE RULE: a write may replace a field iff its tier `>=` the stored provenance's tier
 * (scan `0` < enrichment `1` < user `2`). Each test drives the REAL scan path
 * ([BookRepository.resolveOrInsert] → `upsertFromAnalyzed` → `mergeByProvenance`), where the merge
 * lives — a raw `repo.upsert` bypasses it (the scanner is the only producer of scan-tier payloads,
 * so that is the only path that can clobber a higher tier). The scan write's authority is tier 0, so
 * a stored field is preserved exactly when its recorded tier is above scan.
 *
 * Compliance table (vs. the old `userEditedFields` behaviour):
 *  - SCAN vs USER      → preserved (0 < 2), unchanged from before.
 *  - SCAN vs ENRICHMENT → preserved (0 < 1) AND the value records ENRICHMENT/provider, not a fake USER
 *    (the A7 workaround, done honestly).
 *  - SCAN vs SCAN      → replaced (0 >= 0): a rescan still updates an unedited field.
 */
class FieldProvenanceOnRescanTest :
    FunSpec({

        test("USER title survives a rescan that re-derives a different title from the files") {
            withSqlDatabase {
                val (repo, registry) = provenanceRepository(sql, driver)
                runTest {
                    val libId = registry.currentLibrary()
                    val path = "Sanderson/Mistborn"
                    val id = repo.resolveOrInsert(libId, TEST_FOLDER, scanFor(path)).resolved()

                    // User edits the title in the app: write the user value + USER provenance.
                    val current = repo.findById(id)!!
                    repo.upsert(current.copy(title = "The Final Empire", fieldProvenance = userMap(BookField.TITLE)))

                    // Rescan re-derives a different title from the files.
                    repo.resolveOrInsert(libId, TEST_FOLDER, scanFor(path, title = "Mistborn 01"))

                    repo.findById(id)!!.title shouldBe "The Final Empire"
                }
            }
        }

        test("USER description survives a rescan that re-derives a different description") {
            withSqlDatabase {
                val (repo, registry) = provenanceRepository(sql, driver)
                runTest {
                    val libId = registry.currentLibrary()
                    val path = "Sanderson/WayOfKings"
                    val id = repo.resolveOrInsert(libId, TEST_FOLDER, scanFor(path)).resolved()

                    val current = repo.findById(id)!!
                    repo.upsert(
                        current.copy(
                            description = "A hand-written blurb.",
                            fieldProvenance = userMap(BookField.DESCRIPTION),
                        ),
                    )

                    repo.resolveOrInsert(libId, TEST_FOLDER, scanFor(path, description = "Sidecar blurb."))

                    repo.findById(id)!!.description shouldBe "A hand-written blurb."
                }
            }
        }

        test("USER subtitle survives a rescan that re-derives a different subtitle") {
            withSqlDatabase {
                val (repo, registry) = provenanceRepository(sql, driver)
                runTest {
                    val libId = registry.currentLibrary()
                    val path = "Sanderson/Elantris"
                    val id = repo.resolveOrInsert(libId, TEST_FOLDER, scanFor(path)).resolved()

                    val current = repo.findById(id)!!
                    repo.upsert(
                        current.copy(subtitle = "User Subtitle", fieldProvenance = userMap(BookField.SUBTITLE)),
                    )

                    repo.resolveOrInsert(libId, TEST_FOLDER, scanFor(path, subtitle = "File Subtitle"))

                    repo.findById(id)!!.subtitle shouldBe "User Subtitle"
                }
            }
        }

        test("USER contributors survive a rescan that re-derives different contributors") {
            withSqlDatabase {
                val (repo, registry, contributorRepo) = provenanceRepository(sql, driver)
                runTest {
                    val libId = registry.currentLibrary()
                    val path = "Sanderson/Warbreaker"
                    val id = repo.resolveOrInsert(libId, TEST_FOLDER, scanFor(path)).resolved()

                    // User curates the contributor list in the app — stamps both contributor roles USER.
                    val curatedId = contributorRepo.resolveOrCreate("Brandon Sanderson", null)
                    val current = repo.findById(id)!!
                    repo.upsert(
                        current.copy(
                            contributors =
                                listOf(
                                    BookContributorPayload(
                                        id = curatedId.value,
                                        name = "Brandon Sanderson",
                                        sortName = null,
                                        role = "author",
                                        creditedAs = null,
                                    ),
                                ),
                            fieldProvenance = userMap(BookField.AUTHORS, BookField.NARRATORS),
                        ),
                    )

                    // Rescan finds a sloppy embedded author tag.
                    repo.resolveOrInsert(libId, TEST_FOLDER, scanFor(path, authors = listOf("B. Sanderson (Author)")))

                    repo.findById(id)!!.contributors.map { it.name } shouldBe listOf("Brandon Sanderson")
                }
            }
        }

        test("USER series survive a rescan that re-derives different series") {
            withSqlDatabase {
                val fixture = provenanceRepository(sql, driver)
                val repo = fixture.repo
                val registry = fixture.registry
                val seriesRepo = fixture.series
                runTest {
                    val libId = registry.currentLibrary()
                    val path = "Sanderson/Stormlight01"
                    val id = repo.resolveOrInsert(libId, TEST_FOLDER, scanFor(path)).resolved()

                    val curatedId = seriesRepo.resolveOrCreate("The Stormlight Archive")
                    val current = repo.findById(id)!!
                    repo.upsert(
                        current.copy(
                            series =
                                listOf(
                                    BookSeriesPayload(id = curatedId.value, name = "The Stormlight Archive", sequence = "1"),
                                ),
                            fieldProvenance = userMap(BookField.SERIES),
                        ),
                    )

                    repo.resolveOrInsert(libId, TEST_FOLDER, scanFor(path, series = listOf(SeriesEntry("Stormlight", "1.0"))))

                    repo.findById(id)!!.series.map { it.name } shouldBe listOf("The Stormlight Archive")
                }
            }
        }

        test("ENRICHMENT description survives a rescan AND keeps its ENRICHMENT/provider provenance (A7 done right)") {
            withSqlDatabase {
                val (repo, registry) = provenanceRepository(sql, driver)
                runTest {
                    val libId = registry.currentLibrary()
                    val path = "Sanderson/Enriched"
                    val id = repo.resolveOrInsert(libId, TEST_FOLDER, scanFor(path, publisher = "Tor V1")).resolved()

                    // A prior wizard apply enriched the description (ENRICHMENT, provider = audible).
                    val current = repo.findById(id)!!
                    repo.upsert(
                        current.copy(
                            description = "Enriched blurb.",
                            fieldProvenance =
                                mapOf(
                                    BookField.DESCRIPTION to
                                        FieldProvenance(FieldSourceKind.ENRICHMENT, provider = "audible", at = 5L),
                                ),
                        ),
                    )

                    // Rescan re-derives a different description AND a new publisher (an unprotected field),
                    // so the write actually happens — proving the enriched value + provenance are sticky.
                    repo.resolveOrInsert(
                        libId,
                        TEST_FOLDER,
                        scanFor(path, description = "Re-derived blurb.", publisher = "Tor V2"),
                    )

                    val readback = repo.findById(id)!!
                    readback.description shouldBe "Enriched blurb." // preserved (0 < 1)
                    readback.publisher shouldBe "Tor V2" // unprotected — updated
                    // Recorded honestly as ENRICHMENT/provider, NOT masquerading as a user edit.
                    readback.fieldProvenance[BookField.DESCRIPTION] shouldBe
                        FieldProvenance(FieldSourceKind.ENRICHMENT, provider = "audible", at = 5L)
                }
            }
        }

        test("SCAN vs SCAN: a rescan replaces an unedited field (tier tie 0 >= 0)") {
            withSqlDatabase {
                val (repo, registry) = provenanceRepository(sql, driver)
                runTest {
                    val libId = registry.currentLibrary()
                    val path = "Sanderson/PlainScan"
                    val id = repo.resolveOrInsert(libId, TEST_FOLDER, scanFor(path, title = "Old Title")).resolved()

                    // No user/enrichment edit — a rescan with a different title overwrites it.
                    repo.resolveOrInsert(libId, TEST_FOLDER, scanFor(path, title = "New Title"))

                    repo.findById(id)!!.title shouldBe "New Title"
                }
            }
        }

        test("a rescan that only disagrees on a protected field skips — no revision bump") {
            withSqlDatabase {
                val (repo, registry) = provenanceRepository(sql, driver)
                runTest {
                    val libId = registry.currentLibrary()
                    val path = "Sanderson/Skip"
                    val id = repo.resolveOrInsert(libId, TEST_FOLDER, scanFor(path)).resolved()

                    // Description edit changes no other stored field, so the merged rescan matches stored
                    // in every column and the idempotency check skips it cleanly.
                    repo.upsert(
                        repo.findById(id)!!.copy(
                            description = "Curated blurb.",
                            fieldProvenance = userMap(BookField.DESCRIPTION),
                        ),
                    )
                    val revisionAfterEdit = repo.findById(id)!!.revision

                    repo.resolveOrInsert(libId, TEST_FOLDER, scanFor(path, description = "Re-derived blurb."))

                    repo.findById(id)!!.revision shouldBe revisionAfterEdit
                }
            }
        }

        test("a non-protected field is still updated by a rescan, even alongside a protected one") {
            withSqlDatabase {
                val (repo, registry) = provenanceRepository(sql, driver)
                runTest {
                    val libId = registry.currentLibrary()
                    val path = "Sanderson/Publisher"
                    val id =
                        repo.resolveOrInsert(libId, TEST_FOLDER, scanFor(path, publisher = "Tor V1")).resolved()

                    repo.upsert(
                        repo.findById(id)!!.copy(title = "Curated", fieldProvenance = userMap(BookField.TITLE)),
                    )

                    // Rescan changes BOTH the protected title and the unprotected publisher.
                    repo.resolveOrInsert(
                        libId,
                        TEST_FOLDER,
                        scanFor(path, title = "Re-derived", publisher = "Tor V2"),
                    )

                    val readback = repo.findById(id)!!
                    readback.title shouldBe "Curated" // protected — preserved
                    readback.publisher shouldBe "Tor V2" // not protected — updated
                }
            }
        }

        test("fieldProvenance round-trips through the row (serialize → column → deserialize)") {
            withSqlDatabase {
                val (repo, registry) = provenanceRepository(sql, driver)
                runTest {
                    val libId = registry.currentLibrary()
                    val id =
                        repo.resolveOrInsert(libId, TEST_FOLDER, scanFor("Sanderson/RoundTrip")).resolved()

                    val provenance =
                        mapOf(
                            BookField.DESCRIPTION to FieldProvenance(FieldSourceKind.USER, at = 1L),
                            BookField.TITLE to FieldProvenance(FieldSourceKind.USER, at = 2L),
                            BookField.SERIES to
                                FieldProvenance(FieldSourceKind.ENRICHMENT, provider = "audnexus", at = 3L),
                        )
                    repo.upsert(repo.findById(id)!!.copy(fieldProvenance = provenance))

                    repo.findById(id)!!.fieldProvenance shouldBe provenance
                }
            }
        }
    })

private val TEST_FOLDER = FolderId("test-folder")

/** A USER-tier provenance map for [fields] (the app's hand-edit stamp). */
private fun userMap(vararg fields: BookField) = fields.associateWith { FieldProvenance(FieldSourceKind.USER, at = 1L) }

private fun AppResult<IngestOutcome>.resolved(): BookId =
    when (this) {
        is AppResult.Success -> data.bookId
        is AppResult.Failure -> error("resolveOrInsert failed: ${error.message}")
    }

/** A [BookRepository] plus the catalogues a provenance test plants curated contributor/series rows through. */
private data class ProvenanceFixture(
    val repo: BookRepository,
    val registry: LibraryRegistry,
    val contributors: ContributorRepository,
    val series: SeriesRepository,
)

private fun provenanceRepository(
    sql: ListenUpDatabase,
    driver: app.cash.sqldelight.db.SqlDriver,
): ProvenanceFixture {
    val registry = LibraryRegistry(sql)
    val bus = ChangeBus()
    val syncRegistry = SyncRegistry()
    val contributorRepo = ContributorRepository(sql, bus, syncRegistry)
    val seriesRepo = SeriesRepository(sql, bus, syncRegistry)
    val repo =
        BookRepository(
            db = sql,
            driver = driver,
            bus = bus,
            registry = syncRegistry,
            contributorRepository = contributorRepo,
            seriesRepository = seriesRepo,
            genreRepository = GenreRepository(sql, bus, syncRegistry),
        )
    return ProvenanceFixture(repo, registry, contributorRepo, seriesRepo)
}

/**
 * A minimal [AnalyzedBook] anchored at [rootRelPath] (the natural key a rescan resolves by), with the
 * file-derived metadata a scanner would produce. Its [AnalyzedBook.fieldProvenance] is empty — the
 * scan write's authority is tier 0 regardless, so protection derives from the *stored* provenance the
 * test plants via `repo.upsert`. Override a field to simulate the files disagreeing with an edit.
 */
private fun scanFor(
    rootRelPath: String,
    title: String = rootRelPath.substringAfterLast('/'),
    subtitle: String? = null,
    description: String? = null,
    publisher: String? = null,
    authors: List<String> = emptyList(),
    series: List<SeriesEntry> = emptyList(),
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
        subtitle = subtitle,
        description = description,
        publisher = publisher,
        authors = authors,
        series = series,
        tracks = listOf(TrackEntry(file = file)),
    )
}
