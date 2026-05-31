@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.BookUpdate
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.BookError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookChapterPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPermissionPolicy
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.cover.CoverStorage
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.Database

class BookServiceImplUpdateTest :
    FunSpec({

        /**
         * Builds a [BookServiceImpl] bound to the given caller plus the backing [BookRepository]
         * (so tests can seed a book through the same db) — used by the canEdit gate tests.
         */
        fun bookServiceFor(
            db: Database,
            userId: String,
            role: UserRole,
        ): Pair<BookServiceImpl, BookRepository> {
            val bus = ChangeBus()
            val syncRegistry = SyncRegistry()
            val contributorRepo = ContributorRepository(db, bus, syncRegistry)
            val seriesRepo = SeriesRepository(db, bus, syncRegistry)
            val repo =
                BookRepository(
                    db = db,
                    bus = bus,
                    registry = syncRegistry,
                    contributorRepository = contributorRepo,
                    seriesRepository = seriesRepo,
                )
            val service =
                BookServiceImpl(
                    repo = repo,
                    contributorRepo = contributorRepo,
                    seriesRepo = seriesRepo,
                    coverStorage = CoverStorage(),
                    db = db,
                    genreRepo = GenreRepository(db, bus, syncRegistry),
                    accessPolicy = BookAccessPolicy(db),
                    permissionPolicy = UserPermissionPolicy(db),
                    principal = PrincipalProvider { UserPrincipal(UserId(userId), SessionId("s-$userId"), role) },
                )
            return service to repo
        }

        test("updateBook by a MEMBER without canEdit is denied with PermissionDenied") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("m1", UserRoleColumn.MEMBER, canEdit = false)
                val (service, _) = bookServiceFor(db, "m1", UserRole.MEMBER)
                runTest {
                    service
                        .updateBook(BookId("b1"), BookUpdate(title = "Nope"))
                        .shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<AuthError.PermissionDenied>()
                }
            }
        }

        test("updateBook by an ADMIN succeeds even with canEdit=false") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("a1", UserRoleColumn.ADMIN, canEdit = false)
                val (service, repo) = bookServiceFor(db, "a1", UserRole.ADMIN)
                runTest {
                    repo.upsert(bookFixture(id = "b1", title = "The Way of Kings"))

                    service
                        .updateBook(BookId("b1"), BookUpdate(title = "Words of Radiance"))
                        .shouldBeInstanceOf<AppResult.Success<Unit>>()
                    repo.findById(BookId("b1"))?.title shouldBe "Words of Radiance"
                }
            }
        }

        test("updateBook by a MEMBER granted canEdit succeeds") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestUser("m2", UserRoleColumn.MEMBER, canEdit = true)
                val (service, repo) = bookServiceFor(db, "m2", UserRole.MEMBER)
                runTest {
                    repo.upsert(bookFixture(id = "b1", title = "The Way of Kings"))

                    service
                        .updateBook(BookId("b1"), BookUpdate(title = "Words of Radiance"))
                        .shouldBeInstanceOf<AppResult.Success<Unit>>()
                    repo.findById(BookId("b1"))?.title shouldBe "Words of Radiance"
                }
            }
        }

        test("updateBook applies the title patch and bumps the revision") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val syncRegistry = SyncRegistry()
                val contributorRepo = ContributorRepository(db, bus, syncRegistry)
                val seriesRepo = SeriesRepository(db, bus, syncRegistry)
                val repo =
                    BookRepository(
                        db = db,
                        bus = bus,
                        registry = syncRegistry,
                        contributorRepository = contributorRepo,
                        seriesRepository = seriesRepo,
                    )
                val service =
                    BookServiceImpl(
                        repo = repo,
                        contributorRepo = contributorRepo,
                        seriesRepo = seriesRepo,
                        coverStorage = CoverStorage(),
                        db = db,
                        genreRepo = GenreRepository(db, bus, syncRegistry),
                        accessPolicy = BookAccessPolicy(db),
                        permissionPolicy = UserPermissionPolicy(db),
                        principal = PrincipalProvider { UserPrincipal(UserId("test-admin"), SessionId("s"), UserRole.ROOT) },
                    )
                runTest {
                    val initialUpsert =
                        repo.upsert(bookFixture(id = "b1", title = "The Way of Kings"))
                    val initialRevision =
                        initialUpsert.shouldBeInstanceOf<AppResult.Success<BookSyncPayload>>().data.revision

                    val result = service.updateBook(BookId("b1"), BookUpdate(title = "Words of Radiance"))

                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()

                    val updated = repo.findById(BookId("b1"))
                    updated?.title shouldBe "Words of Radiance"
                    (updated?.revision ?: -1L) shouldBe initialRevision + 1L
                }
            }
        }

        test("updateBook preserves unchanged fields when patch carries only one field") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val syncRegistry = SyncRegistry()
                val contributorRepo = ContributorRepository(db, bus, syncRegistry)
                val seriesRepo = SeriesRepository(db, bus, syncRegistry)
                val repo =
                    BookRepository(
                        db = db,
                        bus = bus,
                        registry = syncRegistry,
                        contributorRepository = contributorRepo,
                        seriesRepository = seriesRepo,
                    )
                val service =
                    BookServiceImpl(
                        repo = repo,
                        contributorRepo = contributorRepo,
                        seriesRepo = seriesRepo,
                        coverStorage = CoverStorage(),
                        db = db,
                        genreRepo = GenreRepository(db, bus, syncRegistry),
                        accessPolicy = BookAccessPolicy(db),
                        permissionPolicy = UserPermissionPolicy(db),
                        principal = PrincipalProvider { UserPrincipal(UserId("test-admin"), SessionId("s"), UserRole.ROOT) },
                    )
                runTest {
                    repo.upsert(
                        bookFixture(
                            id = "b2",
                            title = "Original Title",
                            subtitle = "Original Subtitle",
                            description = "Original Description",
                        ),
                    )

                    val result = service.updateBook(BookId("b2"), BookUpdate(publishYear = 2026))

                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()

                    val updated = repo.findById(BookId("b2"))
                    updated?.title shouldBe "Original Title"
                    updated?.subtitle shouldBe "Original Subtitle"
                    updated?.description shouldBe "Original Description"
                    updated?.publishYear shouldBe 2026
                }
            }
        }

        test("updateBook returns BookError.NotFound when the book does not exist") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val syncRegistry = SyncRegistry()
                val contributorRepo = ContributorRepository(db, bus, syncRegistry)
                val seriesRepo = SeriesRepository(db, bus, syncRegistry)
                val repo =
                    BookRepository(
                        db = db,
                        bus = bus,
                        registry = syncRegistry,
                        contributorRepository = contributorRepo,
                        seriesRepository = seriesRepo,
                    )
                val service =
                    BookServiceImpl(
                        repo = repo,
                        contributorRepo = contributorRepo,
                        seriesRepo = seriesRepo,
                        coverStorage = CoverStorage(),
                        db = db,
                        genreRepo = GenreRepository(db, bus, syncRegistry),
                        accessPolicy = BookAccessPolicy(db),
                        permissionPolicy = UserPermissionPolicy(db),
                        principal = PrincipalProvider { UserPrincipal(UserId("test-admin"), SessionId("s"), UserRole.ROOT) },
                    )
                runTest {
                    val result =
                        service.updateBook(BookId("does-not-exist"), BookUpdate(title = "Anything"))

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
    subtitle: String? = null,
    description: String? = null,
    rootRelPath: String = "Sanderson/$id",
): BookSyncPayload =
    BookSyncPayload(
        id = id,
        libraryId = LibraryId("test-library"),
        folderId = FolderId("test-folder"),
        title = title,
        sortTitle = title,
        subtitle = subtitle,
        description = description,
        publishYear = null,
        publisher = null,
        language = null,
        isbn = null,
        asin = null,
        abridged = false,
        explicit = false,
        totalDuration = 3_600_000L,
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
                    duration = 3_600_000L,
                    size = 500_000_000L,
                ),
            ),
        chapters =
            listOf(
                BookChapterPayload(id = "ch-$id", title = "Prologue", duration = 1_000_000L, startTime = 0L),
            ),
        revision = 0L,
        updatedAt = 0L,
        createdAt = 0L,
        deletedAt = null,
    )
