@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.error.BookError
import com.calypsan.listenup.api.error.CoverError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookChapterPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.CoverPayload
import com.calypsan.listenup.api.sync.CoverSource
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPermissionPolicy
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.cover.CoverImageStore
import com.calypsan.listenup.server.cover.CoverStorage
import com.calypsan.listenup.server.media.ImageStore
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeBytes
import kotlinx.coroutines.test.runTest
import com.calypsan.listenup.server.testing.asSqlDatabase

class BookServiceImplDeleteCoverTest :
    FunSpec({

        test("deleteBookCover nulls cover state and removes the file when a filesystem cover exists") {
            withInMemoryDatabase {
                val db = this
                val libraryRoot = Files.createTempDirectory("listenup-test-library-").toAbsolutePath()
                libraryRoot.toFile().deleteOnExit()
                seedTestLibraryAndFolder(folderPath = libraryRoot.toString())
                val (service, repo) = newService(db)
                runTest {
                    val rootRelPath = "Sanderson/b1"
                    val bookDir = libraryRoot.resolve(rootRelPath).apply { createDirectories() }
                    val coverFile = bookDir.resolve("cover.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
                    repo.upsert(
                        bookFixture(
                            id = "b1",
                            title = "The Way of Kings",
                            rootRelPath = rootRelPath,
                            cover = CoverPayload(source = CoverSource.FILESYSTEM, hash = "abc123"),
                        ),
                    )
                    coverFile.exists() shouldBe true

                    val result = service.deleteBookCover(BookId("b1"))

                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()
                    repo.findById(BookId("b1"))?.cover shouldBe null
                    coverFile.exists() shouldBe false
                }
            }
        }

        test("deleteBookCover nulls cover state and leaves the audio file alone for embedded covers") {
            withInMemoryDatabase {
                val db = this
                val libraryRoot = Files.createTempDirectory("listenup-test-library-").toAbsolutePath()
                libraryRoot.toFile().deleteOnExit()
                seedTestLibraryAndFolder(folderPath = libraryRoot.toString())
                val (service, repo) = newService(db)
                runTest {
                    val rootRelPath = "Sanderson/b1"
                    val bookDir = libraryRoot.resolve(rootRelPath).apply { createDirectories() }
                    val audioFile = bookDir.resolve("01.m4b").apply { writeBytes(byteArrayOf(9, 9, 9)) }
                    repo.upsert(
                        bookFixture(
                            id = "b1",
                            title = "Embedded Cover Book",
                            rootRelPath = rootRelPath,
                            cover = CoverPayload(source = CoverSource.EMBEDDED, hash = "deadbeef"),
                        ),
                    )

                    val result = service.deleteBookCover(BookId("b1"))

                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()
                    repo.findById(BookId("b1"))?.cover shouldBe null
                    // Critical: the audio file (which carried the embedded artwork) must not be touched.
                    audioFile.exists() shouldBe true
                }
            }
        }

        test("deleteBookCover returns CoverError.NotPresent when the book has no cover") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val (service, repo) = newService(db)
                runTest {
                    repo.upsert(bookFixture(id = "b1", title = "Coverless Book", cover = null))

                    val result = service.deleteBookCover(BookId("b1"))

                    val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                    val error = failure.error.shouldBeInstanceOf<CoverError.NotPresent>()
                    (error.debugInfo ?: "") shouldContain "b1"
                }
            }
        }

        test("deleteBookCover returns BookError.NotFound when the book does not exist") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val (service, _) = newService(db)
                runTest {
                    val result = service.deleteBookCover(BookId("does-not-exist"))

                    val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                    val error = failure.error.shouldBeInstanceOf<BookError.NotFound>()
                    (error.debugInfo ?: "") shouldContain "does-not-exist"
                }
            }
        }

        test("deleteBookCover still succeeds when the cover file is already missing") {
            withInMemoryDatabase {
                val db = this
                val libraryRoot = Files.createTempDirectory("listenup-test-library-").toAbsolutePath()
                libraryRoot.toFile().deleteOnExit()
                seedTestLibraryAndFolder(folderPath = libraryRoot.toString())
                val (service, repo) = newService(db)
                runTest {
                    // Seed the book with a FILESYSTEM cover in the DB, but no file on disk.
                    val rootRelPath = "Sanderson/b1"
                    libraryRoot.resolve(rootRelPath).createDirectories()
                    repo.upsert(
                        bookFixture(
                            id = "b1",
                            title = "Cover File Missing",
                            rootRelPath = rootRelPath,
                            cover = CoverPayload(source = CoverSource.FILESYSTEM, hash = "ghost"),
                        ),
                    )

                    val result = service.deleteBookCover(BookId("b1"))

                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()
                    repo.findById(BookId("b1"))?.cover shouldBe null
                }
            }
        }

        test("deleteBookCover removes the managed file and nulls cover columns for an ENRICHED cover") {
            withInMemoryDatabase {
                val db = this
                val home = Files.createTempDirectory("listenup-test-home-").toAbsolutePath()
                home.toFile().deleteOnExit()
                val coversDir = home.resolve("covers").apply { createDirectories() }
                // Write a fake managed cover file at covers/<bookId>.jpg
                val managedFile = coversDir.resolve("b1.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
                seedTestLibraryAndFolder()
                val coverImageStore = CoverImageStore(ImageStore(coversDir, MAX_COVER_BYTES))
                val (service, repo) = newService(db, coverImageStore, homeDir = home)
                runTest {
                    // Seed the book with an ENRICHED cover in the DB
                    repo.upsert(bookFixture(id = "b1", title = "Enriched Cover Book"))
                    repo.setManagedCover(BookId("b1"), "covers/b1.jpg", "sha256abc", CoverSource.ENRICHED)
                    managedFile.exists() shouldBe true

                    val result = service.deleteBookCover(BookId("b1"))

                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()
                    // Managed file must be gone
                    managedFile.exists() shouldBe false
                    // All cover columns must be nulled
                    val after = repo.findById(BookId("b1")).shouldNotBeNull()
                    after.cover.shouldBeNull()
                }
            }
        }

        test("deleteBookCover removes the managed file and nulls cover columns for an UPLOADED cover") {
            withInMemoryDatabase {
                val db = this
                val home = Files.createTempDirectory("listenup-test-home-uploaded-").toAbsolutePath()
                home.toFile().deleteOnExit()
                val coversDir = home.resolve("covers").apply { createDirectories() }
                val managedFile = coversDir.resolve("b2.png").apply { writeBytes(byteArrayOf(4, 5, 6)) }
                seedTestLibraryAndFolder()
                val coverImageStore = CoverImageStore(ImageStore(coversDir, MAX_COVER_BYTES))
                val (service, repo) = newService(db, coverImageStore, homeDir = home)
                runTest {
                    repo.upsert(bookFixture(id = "b2", title = "Uploaded Cover Book"))
                    repo.setManagedCover(BookId("b2"), "covers/b2.png", "sha256xyz", CoverSource.UPLOADED)
                    managedFile.exists() shouldBe true

                    val result = service.deleteBookCover(BookId("b2"))

                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()
                    managedFile.exists() shouldBe false
                    repo.findById(BookId("b2"))?.cover.shouldBeNull()
                }
            }
        }
    })

private const val MAX_COVER_BYTES = 10L * 1024 * 1024

private fun newService(
    db: org.jetbrains.exposed.v1.jdbc.Database,
    coverImageStore: CoverImageStore? = null,
    homeDir: java.nio.file.Path? = null,
): Pair<BookServiceImpl, BookRepository> {
    val bus = ChangeBus()
    val syncRegistry = SyncRegistry()
    val contributorRepo = ContributorRepository(db.asSqlDatabase(), bus, syncRegistry)
    val seriesRepo = SeriesRepository(db.asSqlDatabase(), bus, syncRegistry)
    val genreRepo = GenreRepository(db.asSqlDatabase(), bus, syncRegistry)
    val repo =
        BookRepository(
            db = db.asSqlDatabase(),
            exposedDb = db,
            bus = bus,
            registry = syncRegistry,
            contributorRepository = contributorRepo,
            seriesRepository = seriesRepo,
            genreRepository = genreRepo,
            homeDir = homeDir,
            coverImageStore = coverImageStore,
        )
    val service =
        BookServiceImpl(
            repo = repo,
            contributorRepo = contributorRepo,
            seriesRepo = seriesRepo,
            coverStorage = CoverStorage(),
            db = db,
            genreRepo = genreRepo,
            accessPolicy = BookAccessPolicy(db),
            permissionPolicy = UserPermissionPolicy(db),
            principal = PrincipalProvider { UserPrincipal(UserId("test-admin"), SessionId("s"), UserRole.ROOT) },
            coverImageStore = coverImageStore,
        )
    return service to repo
}

private fun bookFixture(
    id: String,
    title: String,
    rootRelPath: String = "Sanderson/$id",
    cover: CoverPayload? = null,
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
        totalDuration = 3_600_000L,
        cover = cover,
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
