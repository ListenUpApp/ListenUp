@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.BookUpdate
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.api.dto.scanner.CandidateBook
import com.calypsan.listenup.api.dto.scanner.FileEntry
import com.calypsan.listenup.api.dto.scanner.FileType
import com.calypsan.listenup.api.dto.scanner.TrackEntry
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookContributorPayload
import com.calypsan.listenup.api.sync.BookSeriesPayload
import com.calypsan.listenup.api.sync.CoverPayload
import com.calypsan.listenup.api.sync.CoverSource
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.server.api.BookAccessPolicy
import com.calypsan.listenup.server.api.BookServiceImpl
import com.calypsan.listenup.server.api.ContributorServiceImpl
import com.calypsan.listenup.server.api.SeriesServiceImpl
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.PermissionPolicy
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.cover.CoverImageStore
import com.calypsan.listenup.server.cover.CoverStorage
import com.calypsan.listenup.server.cover.PendingCover
import com.calypsan.listenup.server.media.ImageStore
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.bookPayloadFixture
import com.calypsan.listenup.server.testing.rootPrincipal
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path as IoPath

/**
 * Regression coverage for the cover-path data-loss bug: a write that carries no managed cover
 * (a re-upsert of a freshly-read [com.calypsan.listenup.api.sync.BookSyncPayload], which every
 * series/genre/contributor merge and every user book edit performs under the hood) must not null
 * out a `cover_path` it simply doesn't know — the wire [CoverPayload] never carries one.
 *
 * [coverSourceCases] parameterizes the plain re-upsert and book-edit cases over the sources that
 * matter in production: [CoverSource.FILESYSTEM] and [CoverSource.EMBEDDED] are what the scanner
 * writes (via `BookWriteExtras.managedCover`); [CoverSource.ENRICHED] is kept as a parameter even
 * though nothing writes it in production today, so a future producer inherits the coverage for
 * free. [CoverSource.UPLOADED] is exercised separately — it takes the sticky branch entirely,
 * never reaching [BookRepository.resolveCoverColumns].
 *
 * See [BookRepository.resolveCoverColumns] for the fix; [BookRepositoryManagedCoverTest] covers
 * [BookRepository.setManagedCover]/`clearManagedCover` directly; [BookPersistCoverTest] covers the
 * scan-time managed-cover write itself.
 */
class BookRepositoryCoverPathTest :
    FunSpec({

        coverSourceCases.forEach { case ->
            test("re-upserting a re-read book keeps its ${case.label} cover path") {
                withSqlDatabase {
                    val db = this
                    sql.seedTestLibraryAndFolder()
                    withCoverStore { coverStore, homeDir ->
                        val repo = makeBookRepo(db, coverStore, homeDir)
                        runTest {
                            val (bookId, storedPath) = seedManagedCover(repo, db, case.source)

                            // The exact operation series/genre/contributor merges perform on every
                            // affected book: read the current aggregate back and upsert it unchanged.
                            val reread = repo.findById(bookId) ?: error("book not found")
                            repo.upsert(reread)

                            val row =
                                db.sql.booksQueries
                                    .selectById(bookId.value)
                                    .executeAsOneOrNull()
                                    ?: error("book not found")
                            row.cover_source shouldBe case.source.name.lowercase()
                            row.cover_path shouldBe storedPath
                        }
                    }
                }
            }
        }

        coverSourceCases.forEach { case ->
            test("a book edit keeps its ${case.label} cover path") {
                withSqlDatabase {
                    val db = this
                    sql.seedTestLibraryAndFolder()
                    withCoverStore { coverStore, homeDir ->
                        val (service, repo) = makeBookServiceAndRepo(db, coverStore, homeDir)
                        runTest {
                            val (bookId, storedPath) = seedManagedCover(repo, db, case.source)

                            service
                                .updateBook(bookId, BookUpdate(title = "Edited Title"))
                                .shouldBeInstanceOf<AppResult.Success<Unit>>()

                            val row =
                                db.sql.booksQueries
                                    .selectById(bookId.value)
                                    .executeAsOneOrNull()
                                    ?: error("book not found")
                            row.title shouldBe "Edited Title"
                            row.cover_source shouldBe case.source.name.lowercase()
                            row.cover_path shouldBe storedPath
                        }
                    }
                }
            }
        }

        test("a series merge keeps the moved book's cover path") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                val repo = makeBookRepo(db)
                val seriesRepo = SeriesRepository(db = db.sql, bus = ChangeBus(), registry = SyncRegistry())
                val service =
                    SeriesServiceImpl(
                        seriesRepo = seriesRepo,
                        bookRepo = repo,
                        sqlDb = db.sql,
                        accessPolicy = BookAccessPolicy(db.sql, db.driver),
                        principal = rootPrincipal(),
                    )
                runTest {
                    val sourceId = seriesRepo.resolveOrCreate("Source Series")
                    val targetId = seriesRepo.resolveOrCreate("Target Series")
                    repo.upsert(
                        bookPayloadFixture(
                            id = "b1",
                            title = "Book One",
                            series = listOf(BookSeriesPayload(id = sourceId.value, name = "Source Series", sequence = 1.0)),
                            cover = CoverPayload(source = CoverSource.EMBEDDED, hash = "abc123"),
                        ),
                    )
                    repo
                        .setManagedCover(
                            id = BookId("b1"),
                            relPath = "covers/b1/cover.jpg",
                            hash = "abc123",
                            source = CoverSource.EMBEDDED,
                        ).shouldBeInstanceOf<AppResult.Success<Unit>>()

                    service.mergeSeries(sourceId, targetId).shouldBeInstanceOf<AppResult.Success<Unit>>()

                    val row = sql.booksQueries.selectById("b1").executeAsOneOrNull() ?: error("book b1 not found")
                    row.cover_source shouldBe "embedded"
                    row.cover_path shouldBe "covers/b1/cover.jpg"
                    row.cover_hash shouldBe "abc123"
                }
            }
        }

        test("a contributor merge keeps the moved book's cover path") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                val repo = makeBookRepo(db)
                val contributorRepo = ContributorRepository(db = db.sql, bus = ChangeBus(), registry = SyncRegistry())
                val service =
                    ContributorServiceImpl(
                        contributorRepo = contributorRepo,
                        bookRepo = repo,
                        sqlDb = db.sql,
                        accessPolicy = BookAccessPolicy(db.sql, db.driver),
                        principal = rootPrincipal(),
                    )
                runTest {
                    val sourceId = contributorRepo.resolveOrCreate("Source Person", sortName = null)
                    val targetId = contributorRepo.resolveOrCreate("Target Person", sortName = null)
                    repo.upsert(
                        bookPayloadFixture(
                            id = "b1",
                            title = "Book One",
                            contributors =
                                listOf(
                                    BookContributorPayload(
                                        id = sourceId.value,
                                        name = "Source Person",
                                        sortName = null,
                                        role = "author",
                                        creditedAs = null,
                                    ),
                                ),
                            cover = CoverPayload(source = CoverSource.EMBEDDED, hash = "abc123"),
                        ),
                    )
                    repo
                        .setManagedCover(
                            id = BookId("b1"),
                            relPath = "covers/b1/cover.jpg",
                            hash = "abc123",
                            source = CoverSource.EMBEDDED,
                        ).shouldBeInstanceOf<AppResult.Success<Unit>>()

                    service.mergeContributors(sourceId, targetId).shouldBeInstanceOf<AppResult.Success<Unit>>()

                    val row = sql.booksQueries.selectById("b1").executeAsOneOrNull() ?: error("book b1 not found")
                    row.cover_source shouldBe "embedded"
                    row.cover_path shouldBe "covers/b1/cover.jpg"
                    row.cover_hash shouldBe "abc123"
                }
            }
        }

        test("a changed cover hash without a managed cover clears the path") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val repo = makeBookRepo(this)
                runTest {
                    repo.upsert(
                        bookPayloadFixture(
                            id = "b1",
                            title = "The Way of Kings",
                            cover = CoverPayload(source = CoverSource.ENRICHED, hash = "abc123"),
                        ),
                    )
                    repo.setManagedCover(
                        id = BookId("b1"),
                        relPath = "covers/b1/cover.jpg",
                        hash = "abc123",
                        source = CoverSource.ENRICHED,
                    )

                    // A new cover with a DIFFERENT hash, and no managed cover written for it yet —
                    // the old path cannot possibly be the right one, so it must be nulled.
                    repo.upsert(
                        bookPayloadFixture(
                            id = "b1",
                            title = "The Way of Kings",
                            cover = CoverPayload(source = CoverSource.ENRICHED, hash = "different-hash"),
                        ),
                    )

                    val row = sql.booksQueries.selectById("b1").executeAsOneOrNull() ?: error("book b1 not found")
                    row.cover_source shouldBe "enriched"
                    row.cover_path.shouldBeNull()
                    row.cover_hash shouldBe "different-hash"
                }
            }
        }

        test("the same bytes under a different source clear the path") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val repo = makeBookRepo(this)
                runTest {
                    repo.upsert(
                        bookPayloadFixture(
                            id = "b1",
                            title = "The Way of Kings",
                            cover = CoverPayload(source = CoverSource.EMBEDDED, hash = "H"),
                        ),
                    )
                    repo.setManagedCover(
                        id = BookId("b1"),
                        relPath = "covers/b1/cover.jpg",
                        hash = "H",
                        source = CoverSource.EMBEDDED,
                    )

                    // Same hash, but a DIFFERENT source: an EMBEDDED path can't be assumed correct
                    // for what the payload now claims is a FILESYSTEM cover, even though the bytes'
                    // hash happens to match — pins the source half of the comparison, not just hash.
                    repo.upsert(
                        bookPayloadFixture(
                            id = "b1",
                            title = "The Way of Kings",
                            cover = CoverPayload(source = CoverSource.FILESYSTEM, hash = "H"),
                        ),
                    )

                    val row = sql.booksQueries.selectById("b1").executeAsOneOrNull() ?: error("book b1 not found")
                    row.cover_source shouldBe "filesystem"
                    row.cover_path.shouldBeNull()
                    row.cover_hash shouldBe "H"
                }
            }
        }

        test("removing the cover clears the path") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val repo = makeBookRepo(this)
                runTest {
                    repo.upsert(
                        bookPayloadFixture(
                            id = "b1",
                            title = "The Way of Kings",
                            cover = CoverPayload(source = CoverSource.ENRICHED, hash = "abc123"),
                        ),
                    )
                    repo.setManagedCover(
                        id = BookId("b1"),
                        relPath = "covers/b1/cover.jpg",
                        hash = "abc123",
                        source = CoverSource.ENRICHED,
                    )

                    repo.upsert(
                        bookPayloadFixture(id = "b1", title = "The Way of Kings", cover = null),
                    )

                    val row = sql.booksQueries.selectById("b1").executeAsOneOrNull() ?: error("book b1 not found")
                    row.cover_source.shouldBeNull()
                    row.cover_path.shouldBeNull()
                    row.cover_hash.shouldBeNull()
                }
            }
        }

        test("an uploaded cover stays sticky") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val repo = makeBookRepo(this)
                runTest {
                    repo.upsert(
                        bookPayloadFixture(
                            id = "b1",
                            title = "The Way of Kings",
                            cover = CoverPayload(source = CoverSource.UPLOADED, hash = "up1"),
                        ),
                    )
                    repo.setManagedCover(
                        id = BookId("b1"),
                        relPath = "covers/b1/cover.jpg",
                        hash = "up1",
                        source = CoverSource.UPLOADED,
                    )

                    // A rescan sending a completely different (embedded) cover must not disturb the
                    // user-uploaded one — the pre-existing sticky-UPLOADED guard.
                    repo.upsert(
                        bookPayloadFixture(
                            id = "b1",
                            title = "The Way of Kings: Rescanned",
                            cover = CoverPayload(source = CoverSource.EMBEDDED, hash = "embedded-hash"),
                        ),
                    )

                    val row = sql.booksQueries.selectById("b1").executeAsOneOrNull() ?: error("book b1 not found")
                    row.cover_source shouldBe "uploaded"
                    row.cover_path shouldBe "covers/b1/cover.jpg"
                    row.cover_hash shouldBe "up1"
                }
            }
        }
    })

// ── Parameterisation ──────────────────────────────────────────────────────────

/** One production cover source to drive the parameterized re-upsert/edit cases over. */
private data class CoverSourceCase(
    val label: String,
    val source: CoverSource,
)

/**
 * [CoverSource.FILESYSTEM] and [CoverSource.EMBEDDED] are what the scanner actually writes today
 * (via `BookWriteExtras.managedCover`, see [BookPersister.extractPendingCover]). [CoverSource.ENRICHED]
 * has no production writer yet — kept as a parameter anyway per the fix's own KDoc, which frames the
 * rule in terms of the general "no managedCover, matching stored source+hash" case, not a specific
 * source.
 */
private val coverSourceCases =
    listOf(
        CoverSourceCase("filesystem", CoverSource.FILESYSTEM),
        CoverSourceCase("embedded", CoverSource.EMBEDDED),
        CoverSourceCase("enriched", CoverSource.ENRICHED),
    )
