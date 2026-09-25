@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.BookUpdate
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookSeriesPayload
import com.calypsan.listenup.api.sync.CoverPayload
import com.calypsan.listenup.api.sync.CoverSource
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.api.BookAccessPolicy
import com.calypsan.listenup.server.api.BookServiceImpl
import com.calypsan.listenup.server.api.SeriesServiceImpl
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPermissionPolicy
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.cover.CoverStorage
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
import kotlinx.coroutines.test.runTest

/**
 * Regression coverage for the enriched-cover-path data-loss bug: a write that carries no managed
 * cover (a re-upsert of a freshly-read [com.calypsan.listenup.api.sync.BookSyncPayload], which
 * every series/genre/contributor merge and every user book edit performs under the hood) must not
 * null out a `cover_path` it simply doesn't know — the wire [CoverPayload] never carries one.
 *
 * See [BookRepository.resolveCoverColumns] for the fix; [BookRepositoryManagedCoverTest] covers
 * [BookRepository.setManagedCover]/`clearManagedCover` directly.
 */
class BookRepositoryCoverPathTest :
    FunSpec({

        test("re-upserting a re-read book keeps its enriched cover path") {
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
                    repo
                        .setManagedCover(
                            id = BookId("b1"),
                            relPath = "covers/b1/cover.jpg",
                            hash = "abc123",
                            source = CoverSource.ENRICHED,
                        ).shouldBeInstanceOf<AppResult.Success<Unit>>()

                    // The exact operation series/genre/contributor merges perform on every affected
                    // book: read the current aggregate back and upsert it unchanged.
                    val reread = repo.findById(BookId("b1")) ?: error("book b1 not found")
                    repo.upsert(reread)

                    val row = sql.booksQueries.selectById("b1").executeAsOneOrNull() ?: error("book b1 not found")
                    row.cover_source shouldBe "enriched"
                    row.cover_path shouldBe "covers/b1/cover.jpg"
                    row.cover_hash shouldBe "abc123"
                }
            }
        }

        test("a book edit keeps its enriched cover path") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                val (service, repo) = makeBookServiceAndRepo(db)
                runTest {
                    repo.upsert(
                        bookPayloadFixture(
                            id = "b1",
                            title = "The Way of Kings",
                            cover = CoverPayload(source = CoverSource.ENRICHED, hash = "abc123"),
                        ),
                    )
                    repo
                        .setManagedCover(
                            id = BookId("b1"),
                            relPath = "covers/b1/cover.jpg",
                            hash = "abc123",
                            source = CoverSource.ENRICHED,
                        ).shouldBeInstanceOf<AppResult.Success<Unit>>()

                    service
                        .updateBook(BookId("b1"), BookUpdate(title = "Words of Radiance"))
                        .shouldBeInstanceOf<AppResult.Success<Unit>>()

                    val row = sql.booksQueries.selectById("b1").executeAsOneOrNull() ?: error("book b1 not found")
                    row.title shouldBe "Words of Radiance"
                    row.cover_source shouldBe "enriched"
                    row.cover_path shouldBe "covers/b1/cover.jpg"
                    row.cover_hash shouldBe "abc123"
                }
            }
        }

        test("a series merge keeps the moved book's enriched cover path") {
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
                            cover = CoverPayload(source = CoverSource.ENRICHED, hash = "abc123"),
                        ),
                    )
                    repo
                        .setManagedCover(
                            id = BookId("b1"),
                            relPath = "covers/b1/cover.jpg",
                            hash = "abc123",
                            source = CoverSource.ENRICHED,
                        ).shouldBeInstanceOf<AppResult.Success<Unit>>()

                    service.mergeSeries(sourceId, targetId).shouldBeInstanceOf<AppResult.Success<Unit>>()

                    val row = sql.booksQueries.selectById("b1").executeAsOneOrNull() ?: error("book b1 not found")
                    row.cover_source shouldBe "enriched"
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

private fun makeBookRepo(db: SqlTestDatabases): BookRepository {
    val bus = ChangeBus()
    val syncRegistry = SyncRegistry()
    return BookRepository(
        db = db.sql,
        driver = db.driver,
        bus = bus,
        registry = syncRegistry,
        contributorRepository = ContributorRepository(db.sql, bus, syncRegistry),
        seriesRepository = SeriesRepository(db.sql, bus, syncRegistry),
        genreRepository = GenreRepository(db.sql, bus, syncRegistry),
    )
}

/** Mirrors [com.calypsan.listenup.server.api.BookServiceImplUpdateTest]'s `bookServiceFor` helper. */
private fun makeBookServiceAndRepo(db: SqlTestDatabases): Pair<BookServiceImpl, BookRepository> {
    val bus = ChangeBus()
    val syncRegistry = SyncRegistry()
    val contributorRepo = ContributorRepository(db.sql, bus, syncRegistry)
    val seriesRepo = SeriesRepository(db.sql, bus, syncRegistry)
    val genreRepo = GenreRepository(db.sql, bus, syncRegistry)
    val repo =
        BookRepository(
            db = db.sql,
            driver = db.driver,
            bus = bus,
            registry = syncRegistry,
            contributorRepository = contributorRepo,
            seriesRepository = seriesRepo,
            genreRepository = genreRepo,
        )
    val service =
        BookServiceImpl(
            repo = repo,
            contributorRepo = contributorRepo,
            seriesRepo = seriesRepo,
            coverStorage = CoverStorage(),
            sql = db.sql,
            genreRepo = genreRepo,
            accessPolicy = BookAccessPolicy(db.sql, db.driver),
            permissionPolicy = UserPermissionPolicy(db.sql),
            principal = PrincipalProvider { UserPrincipal(UserId("test-admin"), SessionId("s"), UserRole.ROOT) },
        )
    return service to repo
}
