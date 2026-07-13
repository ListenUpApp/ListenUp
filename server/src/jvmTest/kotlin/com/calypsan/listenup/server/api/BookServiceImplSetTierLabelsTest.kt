package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.error.BookError
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPermissionPolicy
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.cover.CoverStorage
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

class BookServiceImplSetTierLabelsTest :
    FunSpec({

        fun buildService(db: SqlTestDatabases): Pair<BookServiceImpl, BookRepository> {
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

        test("setBookTierLabels persists both labels and readback reflects them") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                val (service, repo) = buildService(db)
                runTest {
                    repo.upsert(bookFixture(id = "b1", title = "The Way of Kings"))

                    val result = service.setBookTierLabels(BookId("b1"), "Book", "Part")

                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()
                    val updated = repo.findById(BookId("b1"))!!
                    updated.bookTierLabel shouldBe "Book"
                    updated.partTierLabel shouldBe "Part"
                }
            }
        }

        test("setBookTierLabels returns BookError.InvalidInput when a label is blank") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                val (service, repo) = buildService(db)
                runTest {
                    repo.upsert(bookFixture(id = "b1", title = "The Way of Kings"))

                    val result = service.setBookTierLabels(BookId("b1"), "  ", null)

                    val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                    val error = failure.error.shouldBeInstanceOf<BookError.InvalidInput>()
                    (error.debugInfo ?: "") shouldContain "must not be blank"
                }
            }
        }

        test("setBookTierLabels returns BookError.InvalidInput when a label exceeds MAX_TIER_LABEL") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                val (service, repo) = buildService(db)
                runTest {
                    repo.upsert(bookFixture(id = "b1", title = "The Way of Kings"))

                    val result = service.setBookTierLabels(BookId("b1"), "x".repeat(65), null)

                    val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                    val error = failure.error.shouldBeInstanceOf<BookError.InvalidInput>()
                    (error.debugInfo ?: "") shouldContain "<= 64"
                }
            }
        }

        test("setBookTierLabels returns SyncError.NotFound when the book does not exist") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                val (service, _) = buildService(db)
                runTest {
                    val result = service.setBookTierLabels(BookId("does-not-exist"), "Book", "Part")

                    val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                    val error = failure.error.shouldBeInstanceOf<SyncError.NotFound>()
                    (error.entityId) shouldBe "does-not-exist"
                }
            }
        }
    })

private fun bookFixture(
    id: String,
    title: String,
    rootRelPath: String = "Sanderson/$id",
): BookSyncPayload =
    BookSyncPayload(
        id = id,
        libraryId = LibraryId("test-library"),
        folderId = FolderId("test-folder"),
        title = title,
        sortTitle = title,
        subtitle = null,
        description = null,
        publishYear = null,
        publisher = null,
        language = null,
        isbn = null,
        asin = null,
        abridged = false,
        explicit = false,
        totalDuration = 1_200_000L,
        cover = null,
        rootRelPath = rootRelPath,
        inode = null,
        scannedAt = 1_730_000_000_000L,
        contributors = emptyList(),
        series = emptyList(),
        audioFiles =
            listOf(
                BookAudioFilePayload(
                    id = "af-$id",
                    index = 0,
                    filename = "01.m4b",
                    format = "m4b",
                    codec = "aac",
                    duration = 1_200_000L,
                    size = 500_000_000L,
                ),
            ),
        chapters = emptyList(),
        revision = 0L,
        updatedAt = 0L,
        createdAt = 0L,
        deletedAt = null,
    )
