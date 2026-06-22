@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.ChapterInput
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.error.BookError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookChapterPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.ChapterSource
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
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

class BookServiceImplSetChaptersTest :
    FunSpec({

        test("setBookChapters replaces chapters, sets chapterSource to USER, and readback reflects new titles") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
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
                runTest {
                    repo.upsert(bookFixture(id = "b1", title = "The Way of Kings"))

                    val result =
                        service.setBookChapters(
                            BookId("b1"),
                            listOf(
                                ChapterInput(id = "ch-1", title = "Prologue", startTime = 0L, duration = 300_000L),
                                ChapterInput(id = "ch-2", title = "Chapter 1", startTime = 300_000L, duration = 600_000L),
                                ChapterInput(id = "ch-3", title = "Chapter 2", startTime = 900_000L, duration = 300_000L),
                            ),
                        )

                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()

                    val updated = repo.findById(BookId("b1"))!!
                    updated.chapters.size shouldBe 3
                    updated.chapters[0].title shouldBe "Prologue"
                    updated.chapters[1].title shouldBe "Chapter 1"
                    updated.chapters[2].title shouldBe "Chapter 2"
                    updated.chapterSource shouldBe ChapterSource.USER
                }
            }
        }

        test("setBookChapters returns BookError.InvalidInput when chapter starts are not strictly increasing") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
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
                runTest {
                    repo.upsert(bookFixture(id = "b1", title = "The Way of Kings"))

                    // startTime goes 0 → 600_000 → 300_000 — not increasing
                    val result =
                        service.setBookChapters(
                            BookId("b1"),
                            listOf(
                                ChapterInput(id = "ch-1", title = "Prologue", startTime = 0L, duration = 600_000L),
                                ChapterInput(id = "ch-2", title = "Chapter 1", startTime = 600_000L, duration = 300_000L),
                                ChapterInput(id = "ch-3", title = "Chapter 2", startTime = 300_000L, duration = 300_000L),
                            ),
                        )

                    val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                    val error = failure.error.shouldBeInstanceOf<BookError.InvalidInput>()
                    (error.debugInfo ?: "") shouldContain "strictly increasing"
                }
            }
        }

        test("setBookChapters returns BookError.NotFound when the book does not exist") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
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
                runTest {
                    val result =
                        service.setBookChapters(
                            BookId("does-not-exist"),
                            listOf(ChapterInput(id = "ch-1", title = "Prologue", startTime = 0L, duration = 300_000L)),
                        )

                    val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                    val error = failure.error.shouldBeInstanceOf<BookError.NotFound>()
                    (error.debugInfo ?: "") shouldContain "does-not-exist"
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
        chapters =
            listOf(
                BookChapterPayload(id = "ch-$id", title = "Prologue", duration = 1_200_000L, startTime = 0L),
            ),
        revision = 0L,
        updatedAt = 0L,
        createdAt = 0L,
        deletedAt = null,
    )
