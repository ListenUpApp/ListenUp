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
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.domain.TierLabelLimits
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
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/**
 * `setBookTierLabels` — a book naming its own structure.
 *
 * The load-bearing case is the last one: a tier name must survive a rescan. That is the entire
 * reason this is a targeted two-column update instead of a read-then-upsert of the aggregate, and
 * it is invisible in every other test — a read-then-upsert would pass all of them and still lose
 * the user's word the next time the scanner touched the row.
 */
class BookServiceImplSetTierLabelsTest :
    FunSpec({

        test("names both tiers and reads them back") {
            withTierRig { service, repo ->
                repo.upsert(bookFixture(id = "b1"))

                service
                    .setBookTierLabels(BookId("b1"), bookTierLabel = "Volume", partTierLabel = "Sequence")
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()

                val updated = repo.findById(BookId("b1"))!!
                updated.bookTierLabel shouldBe "Volume"
                updated.partTierLabel shouldBe "Sequence"
            }
        }

        test("null clears a name rather than leaving the old one") {
            // A coalescing UPDATE would make a cleared tier impossible to express — the user could
            // rename but never un-name. Both columns are always written.
            withTierRig { service, repo ->
                repo.upsert(bookFixture(id = "b1"))
                service.setBookTierLabels(BookId("b1"), "Volume", "Sequence")

                service
                    .setBookTierLabels(BookId("b1"), bookTierLabel = null, partTierLabel = "Part")
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()

                val updated = repo.findById(BookId("b1"))!!
                updated.bookTierLabel shouldBe null
                updated.partTierLabel shouldBe "Part"
            }
        }

        test("a blank name is refused, not stored") {
            withTierRig { service, repo ->
                repo.upsert(bookFixture(id = "b1"))

                val result = service.setBookTierLabels(BookId("b1"), bookTierLabel = "   ", partTierLabel = null)

                withClue("blank would render as an unnamed tier while claiming to be named") {
                    result.shouldBeInstanceOf<AppResult.Failure>().error.shouldBeInstanceOf<BookError.InvalidInput>()
                }
                repo.findById(BookId("b1"))!!.bookTierLabel shouldBe null
            }
        }

        test("a name past the shared ceiling is refused") {
            withTierRig { service, repo ->
                repo.upsert(bookFixture(id = "b1"))

                val result =
                    service.setBookTierLabels(
                        BookId("b1"),
                        bookTierLabel = "V".repeat(TierLabelLimits.MAX_LENGTH + 1),
                        partTierLabel = null,
                    )

                result.shouldBeInstanceOf<AppResult.Failure>().error.shouldBeInstanceOf<BookError.InvalidInput>()
            }
        }

        test("an unknown book is NotFound") {
            withTierRig { service, _ ->
                val result = service.setBookTierLabels(BookId("nope"), "Volume", null)
                result.shouldBeInstanceOf<AppResult.Failure>()
            }
        }

        test("naming a tier bumps the revision so clients see it") {
            withTierRig { service, repo ->
                repo.upsert(bookFixture(id = "b1"))
                val before = repo.findById(BookId("b1"))!!.revision

                service.setBookTierLabels(BookId("b1"), "Volume", null)

                repo.findById(BookId("b1"))!!.revision shouldNotBe before
            }
        }

        test("A RESCAN DOES NOT CLOBBER A TIER NAME") {
            withTierRig { service, repo ->
                repo.upsert(bookFixture(id = "b1"))
                service.setBookTierLabels(BookId("b1"), bookTierLabel = "Volume", partTierLabel = "Sequence")

                // What the scanner does: re-upsert the whole aggregate from what it found on disk.
                // The payload it builds knows nothing about tiers, so both fields are null.
                repo.upsert(bookFixture(id = "b1", title = "The Way of Kings (rescanned)"))

                val updated = repo.findById(BookId("b1"))!!
                withClue("a word the user chose must outlive the scanner re-reading the files") {
                    updated.bookTierLabel shouldBe "Volume"
                    updated.partTierLabel shouldBe "Sequence"
                }
                updated.title shouldBe "The Way of Kings (rescanned)"
            }
        }

        test("per-chapter section headers survive setBookChapters and read back") {
            withTierRig { service, repo ->
                repo.upsert(bookFixture(id = "b1"))

                service
                    .setBookChapters(
                        BookId("b1"),
                        listOf(
                            ChapterInput(
                                id = "ch-1",
                                title = "Prologue",
                                startTime = 0L,
                                duration = 300_000L,
                                bookTitle = "Book One",
                                partTitle = "The Way of Kings",
                            ),
                            ChapterInput(id = "ch-2", title = "Chapter 1", startTime = 300_000L, duration = 900_000L),
                        ),
                    ).shouldBeInstanceOf<AppResult.Success<Unit>>()

                val chapters = repo.findById(BookId("b1"))!!.chapters
                chapters[0].bookTitle shouldBe "Book One"
                chapters[0].partTitle shouldBe "The Way of Kings"
                withClue("a header opens a group; every other chapter carries none") {
                    chapters[1].bookTitle shouldBe null
                    chapters[1].partTitle shouldBe null
                }
            }
        }
    })

/**
 * Stands up the real service over a real in-memory database.
 *
 * Factored rather than repeated per test: the wiring is eight collaborators deep, and a rig copied
 * eight times is eight places for a future constructor change to rot.
 */
private fun withTierRig(body: suspend (BookServiceImpl, BookRepository) -> Unit) {
    withSqlDatabase {
        val db: SqlTestDatabases = this
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
        runTest { body(service, repo) }
    }
}

private fun bookFixture(
    id: String,
    title: String = "The Way of Kings",
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
        rootRelPath = "Sanderson/$id",
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
