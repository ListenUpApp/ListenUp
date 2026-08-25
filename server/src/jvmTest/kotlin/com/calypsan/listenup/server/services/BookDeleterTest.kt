package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.BookError
import com.calypsan.listenup.api.error.LibraryWriteError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.api.BookAccessPolicy
import com.calypsan.listenup.server.api.BookServiceImpl
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPermissionPolicy
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.cover.CoverStorage
import com.calypsan.listenup.server.librarywrite.LibraryWriteBroker
import com.calypsan.listenup.server.librarywrite.SelfWriteRegistry
import com.calypsan.listenup.server.librarywrite.SqlLibraryRootProvider
import com.calypsan.listenup.server.librarywrite.WriteJournal
import com.calypsan.listenup.server.librarywrite.tempJournalDir
import com.calypsan.listenup.server.sidecar.SidecarWriteStateRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.bookPayloadFixture
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * [BookDeleter] and its [BookServiceImpl.deleteBook] gate — the destructive path.
 *
 * **Every refusal test asserts the files are still on disk**, never merely that a failure came
 * back. A guard that returns an error after unlinking is worse than no guard, and the returned
 * value cannot tell those apart — only the filesystem can.
 *
 * The happy-path test is the control the refusals lean on: it proves this rig really can delete a
 * book, so a refusal below is a guard talking rather than a fixture that was never going to work.
 */
class BookDeleterTest :
    FunSpec({

        test("CONTROL — deletes the whole folder (audio, PDF and cover), tombstones the book, drains the journal") {
            withSqlDatabase {
                val root = Files.createTempDirectory("book-deleter-happy-")
                sql.seedTestLibraryAndFolder(folderPath = root.toString())
                val bookDir = root.resolve("AJ Sherrill/Rediscovering Christmas").apply { createDirectories() }
                bookDir.resolve("01.m4b").writeText("audio")
                bookDir.resolve("bonus.pdf").writeText("pdf")
                bookDir.resolve("cover.jpg").writeText("cover")

                runTest {
                    val rig = deleterRig(this@withSqlDatabase)
                    rig.seedBook("b1", "Rediscovering Christmas", "AJ Sherrill/Rediscovering Christmas")

                    rig.deleter.delete(BookId("b1")) shouldBe AppResult.Success(Unit)

                    withClue("the non-audio companions go with the book — that is the whole point") {
                        bookDir.exists() shouldBe false
                    }
                    withClue("the emptied author folder goes too — it now describes nothing") {
                        root.resolve("AJ Sherrill").exists() shouldBe false
                    }
                    withClue("the walk stops at the library root, which is never a candidate") {
                        root.exists() shouldBe true
                    }
                    sql.booksQueries
                        .selectById("b1")
                        .executeAsOne()
                        .deleted_at
                        .shouldNotBeNull()
                    withClue("a completed manifest leaves no journal entry behind") {
                        rig.journal.listPending() shouldBe emptyList()
                    }
                }
            }
        }

        test("refuses when a second book resolves to the SAME directory, names it, and deletes NOTHING") {
            withSqlDatabase {
                val root = Files.createTempDirectory("book-deleter-shared-")
                val mirror = root.resolve("mirror").apply { createDirectories() }
                val shared = mirror.resolve("George Orwell").apply { createDirectories() }
                shared.resolve("1984.m4b").writeText("audio-1984")
                shared.resolve("animal-farm.m4b").writeText("audio-af")

                // `idx_book_natural_key` is UNIQUE on (folder_id, root_rel_path), so two books
                // cannot share a directory *within one folder* — the plan's stated version of this
                // hazard turns out to be structurally prevented after all. What is NOT prevented is
                // two library FOLDERS overlapping on disk: distinct (folder_id, root_rel_path)
                // pairs, one absolute directory. That is the reachable shape, and it is why the
                // guard resolves absolute paths and sweeps every live book on the server rather
                // than comparing stored pairs within one library.
                sql.seedTestLibraryAndFolder(folderPath = root.toString())
                sql.seedTestLibraryAndFolder(
                    libraryId = "test-library-2",
                    folderId = "folder-2",
                    folderPath = mirror.toString(),
                )

                runTest {
                    val rig = deleterRig(this@withSqlDatabase)
                    rig.seedBook("b1", "Nineteen Eighty-Four", "mirror/George Orwell", "1984.m4b")
                    rig.seedBook(
                        id = "b2",
                        title = "Animal Farm",
                        rootRelPath = "George Orwell",
                        filename = "animal-farm.m4b",
                        libraryId = "test-library-2",
                        folderId = "folder-2",
                    )

                    val result = rig.deleter.delete(BookId("b1"))

                    val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                    val error = failure.error.shouldBeInstanceOf<BookError.FolderNotExclusive>()
                    withClue("the refusal must name the blocking book — 'another book' is unactionable") {
                        error.otherBookId shouldBe "b2"
                        error.otherBookTitle shouldBe "Animal Farm"
                    }
                    withClue("NOTHING may be removed") {
                        shared.resolve("1984.m4b").readText() shouldBe "audio-1984"
                        shared.resolve("animal-farm.m4b").readText() shouldBe "audio-af"
                    }
                    withClue("and neither book row may be tombstoned") {
                        sql.booksQueries
                            .selectById("b1")
                            .executeAsOne()
                            .deleted_at shouldBe null
                        sql.booksQueries
                            .selectById("b2")
                            .executeAsOne()
                            .deleted_at shouldBe null
                    }
                }
            }
        }

        test("refuses when another book is NESTED inside the directory, and deletes NOTHING") {
            withSqlDatabase {
                val root = Files.createTempDirectory("book-deleter-nested-")
                sql.seedTestLibraryAndFolder(folderPath = root.toString())
                val outer = root.resolve("Collected Works").apply { createDirectories() }
                outer.resolve("omnibus.m4b").writeText("audio-outer")
                val inner = outer.resolve("Volume Two").apply { createDirectories() }
                inner.resolve("vol2.m4b").writeText("audio-inner")

                runTest {
                    val rig = deleterRig(this@withSqlDatabase)
                    rig.seedBook("b1", "Collected Works", "Collected Works", "omnibus.m4b")
                    rig.seedBook("b2", "Volume Two", "Collected Works/Volume Two", "vol2.m4b")

                    val result = rig.deleter.delete(BookId("b1"))

                    val error =
                        result
                            .shouldBeInstanceOf<AppResult.Failure>()
                            .error
                            .shouldBeInstanceOf<BookError.FolderNotExclusive>()
                    error.otherBookId shouldBe "b2"
                    withClue("the nested book's files — and the outer book's — must all survive") {
                        outer.resolve("omnibus.m4b").readText() shouldBe "audio-outer"
                        inner.resolve("vol2.m4b").readText() shouldBe "audio-inner"
                    }
                }
            }
        }

        test("refuses when the book's directory resolves to the library folder root, and deletes NOTHING") {
            withSqlDatabase {
                val root = Files.createTempDirectory("book-deleter-root-")
                sql.seedTestLibraryAndFolder(folderPath = root.toString())
                root.resolve("loose.m4b").writeText("audio")
                val sibling = root.resolve("Another Book").apply { createDirectories() }
                sibling.resolve("01.m4b").writeText("audio-sibling")

                runTest {
                    val rig = deleterRig(this@withSqlDatabase)
                    // An empty root_rel_path — the book IS the library folder. Nothing in the schema
                    // forbids it, and deleting it would take the whole library with it.
                    rig.seedBook("b1", "Loose Book", "", "loose.m4b")

                    val result = rig.deleter.delete(BookId("b1"))

                    result
                        .shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<LibraryWriteError.ProtectedPath>()
                    withClue("the library root and everything under it must be untouched") {
                        root.exists() shouldBe true
                        root.resolve("loose.m4b").readText() shouldBe "audio"
                        sibling.resolve("01.m4b").readText() shouldBe "audio-sibling"
                    }
                    sql.booksQueries
                        .selectById("b1")
                        .executeAsOne()
                        .deleted_at shouldBe null
                }
            }
        }

        test("refuses an already-tombstoned book and touches nothing on disk") {
            withSqlDatabase {
                val root = Files.createTempDirectory("book-deleter-tombstoned-")
                sql.seedTestLibraryAndFolder(folderPath = root.toString())
                val bookDir = root.resolve("A Book").apply { createDirectories() }
                bookDir.resolve("01.m4b").writeText("audio")

                runTest {
                    val rig = deleterRig(this@withSqlDatabase)
                    rig.seedBook("b1", "A Book", "A Book")
                    rig.repo.softDelete(BookId("b1"), clientOpId = null) shouldBe AppResult.Success(Unit)

                    val result = rig.deleter.delete(BookId("b1"))

                    withClue("its files may still be on disk from an interrupted attempt — reporting success would be a lie") {
                        result
                            .shouldBeInstanceOf<AppResult.Failure>()
                            .error
                            .shouldBeInstanceOf<BookError.NotFound>()
                    }
                    bookDir.resolve("01.m4b").readText() shouldBe "audio"
                }
            }
        }

        test("a non-admin caller is denied and deletes NOTHING") {
            withSqlDatabase {
                val root = Files.createTempDirectory("book-deleter-member-")
                sql.seedTestLibraryAndFolder(folderPath = root.toString())
                val bookDir = root.resolve("A Book").apply { createDirectories() }
                bookDir.resolve("01.m4b").writeText("audio")

                runTest {
                    val rig = deleterRig(this@withSqlDatabase)
                    rig.seedBook("b1", "A Book", "A Book")

                    val service = rig.bookService(this@withSqlDatabase, UserRole.MEMBER)
                    val result = service.deleteBook(BookId("b1"))

                    result
                        .shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<AuthError.PermissionDenied>()
                    withClue("the gate must bite before the filesystem is touched") {
                        bookDir.resolve("01.m4b").readText() shouldBe "audio"
                    }
                    sql.booksQueries
                        .selectById("b1")
                        .executeAsOne()
                        .deleted_at shouldBe null
                }
            }
        }

        test("an admin caller deletes through the RPC surface") {
            withSqlDatabase {
                val root = Files.createTempDirectory("book-deleter-admin-")
                sql.seedTestLibraryAndFolder(folderPath = root.toString())
                val bookDir = root.resolve("A Book").apply { createDirectories() }
                bookDir.resolve("01.m4b").writeText("audio")

                runTest {
                    val rig = deleterRig(this@withSqlDatabase)
                    rig.seedBook("b1", "A Book", "A Book")

                    val service = rig.bookService(this@withSqlDatabase, UserRole.ADMIN)
                    service.deleteBook(BookId("b1")) shouldBe AppResult.Success(Unit)

                    bookDir.exists() shouldBe false
                    sql.booksQueries
                        .selectById("b1")
                        .executeAsOne()
                        .deleted_at
                        .shouldNotBeNull()
                }
            }
        }

        test("clears the book's sidecar write-state so a later identical sidecar is not mistaken for our own") {
            withSqlDatabase {
                val root = Files.createTempDirectory("book-deleter-sidecar-")
                sql.seedTestLibraryAndFolder(folderPath = root.toString())
                root
                    .resolve("A Book")
                    .apply { createDirectories() }
                    .resolve("01.m4b")
                    .writeText("audio")

                runTest {
                    val rig = deleterRig(this@withSqlDatabase)
                    rig.seedBook("b1", "A Book", "A Book")
                    rig.sidecarWriteState.save("b1", contentHashHex = "deadbeef", writtenAtMs = 1L)

                    rig.deleter.delete(BookId("b1")) shouldBe AppResult.Success(Unit)

                    rig.sidecarWriteState.findByBookId("b1") shouldBe null
                    rig.sidecarWriteState.isSelfWrittenHash("deadbeef") shouldBe false
                }
            }
        }
        test("prunes an emptied series folder AND the author folder above it") {
            withSqlDatabase {
                val root = Files.createTempDirectory("book-deleter-prune-chain-")
                sql.seedTestLibraryAndFolder(folderPath = root.toString())
                val bookDir =
                    root.resolve("Aleron Kong/Chaos Seeds/Book 1 - The Land Founding").apply { createDirectories() }
                bookDir.resolve("01.m4b").writeText("audio")

                runTest {
                    val rig = deleterRig(this@withSqlDatabase)
                    rig.seedBook("b1", "The Land: Founding", "Aleron Kong/Chaos Seeds/Book 1 - The Land Founding")

                    rig.deleter.delete(BookId("b1")) shouldBe AppResult.Success(Unit)

                    withClue("every level that the book was the last occupant of goes") {
                        bookDir.exists() shouldBe false
                        root.resolve("Aleron Kong/Chaos Seeds").exists() shouldBe false
                        root.resolve("Aleron Kong").exists() shouldBe false
                    }
                    withClue("the library root is not an ancestor the walk may reach") {
                        root.exists() shouldBe true
                    }
                }
            }
        }

        test("stops at the first ancestor still holding another book's directory") {
            withSqlDatabase {
                val root = Files.createTempDirectory("book-deleter-prune-stops-")
                sql.seedTestLibraryAndFolder(folderPath = root.toString())
                val bookDir =
                    root.resolve("Aleron Kong/Chaos Seeds/Book 1 - The Land Founding").apply { createDirectories() }
                bookDir.resolve("01.m4b").writeText("audio")
                val sibling =
                    root.resolve("Aleron Kong/Chaos Seeds/Book 2 - The Land Forging").apply { createDirectories() }
                sibling.resolve("01.m4b").writeText("audio")

                runTest {
                    val rig = deleterRig(this@withSqlDatabase)
                    rig.seedBook("b1", "The Land: Founding", "Aleron Kong/Chaos Seeds/Book 1 - The Land Founding")
                    rig.seedBook("b2", "The Land: Forging", "Aleron Kong/Chaos Seeds/Book 2 - The Land Forging")

                    rig.deleter.delete(BookId("b1")) shouldBe AppResult.Success(Unit)

                    withClue("book 2 still lives here, so the chain stops dead at the series folder") {
                        bookDir.exists() shouldBe false
                        sibling.resolve("01.m4b").exists() shouldBe true
                        root.resolve("Aleron Kong/Chaos Seeds").exists() shouldBe true
                        root.resolve("Aleron Kong").exists() shouldBe true
                    }
                }
            }
        }

        test("leaves an ancestor that holds a file the library never tracked") {
            withSqlDatabase {
                val root = Files.createTempDirectory("book-deleter-prune-untracked-")
                sql.seedTestLibraryAndFolder(folderPath = root.toString())
                val bookDir = root.resolve("AJ Sherrill/Rediscovering Christmas").apply { createDirectories() }
                bookDir.resolve("01.m4b").writeText("audio")
                // The user's own file, sitting beside the book folder rather than inside it.
                root.resolve("AJ Sherrill/author-notes.txt").writeText("mine")

                runTest {
                    val rig = deleterRig(this@withSqlDatabase)
                    rig.seedBook("b1", "Rediscovering Christmas", "AJ Sherrill/Rediscovering Christmas")

                    rig.deleter.delete(BookId("b1")) shouldBe AppResult.Success(Unit)

                    withClue("pruning is best-effort: an ancestor we did not empty is not ours to remove") {
                        bookDir.exists() shouldBe false
                        root.resolve("AJ Sherrill").exists() shouldBe true
                        root.resolve("AJ Sherrill/author-notes.txt").readText() shouldBe "mine"
                    }
                }
            }
        }

        test("prunes nothing when the book sits directly under the library root") {
            withSqlDatabase {
                val root = Files.createTempDirectory("book-deleter-prune-flat-")
                sql.seedTestLibraryAndFolder(folderPath = root.toString())
                val bookDir = root.resolve("A Loose Book").apply { createDirectories() }
                bookDir.resolve("01.m4b").writeText("audio")

                runTest {
                    val rig = deleterRig(this@withSqlDatabase)
                    rig.seedBook("b1", "A Loose Book", "A Loose Book")

                    rig.deleter.delete(BookId("b1")) shouldBe AppResult.Success(Unit)

                    bookDir.exists() shouldBe false
                    withClue("a one-segment path has no ancestors below the root — the root must survive") {
                        root.exists() shouldBe true
                    }
                }
            }
        }
    })

/** Everything a delete test needs, wired against one migrated test database. */
private class DeleterRig(
    val repo: BookRepository,
    val deleter: BookDeleter,
    val journal: WriteJournal,
    val sidecarWriteState: SidecarWriteStateRepository,
) {
    suspend fun seedBook(
        id: String,
        title: String,
        rootRelPath: String,
        filename: String = "01.m4b",
        libraryId: String = "test-library",
        folderId: String = "test-folder",
    ) {
        val written =
            repo.upsert(
                bookPayloadFixture(
                    id = id,
                    title = title,
                    rootRelPath = rootRelPath,
                    audioFiles =
                        listOf(
                            BookAudioFilePayload(
                                id = "af-$id",
                                index = 0,
                                filename = filename,
                                format = "m4b",
                                codec = "aac",
                                duration = 1_000L,
                                size = 1_000L,
                            ),
                        ),
                ).copy(libraryId = LibraryId(libraryId), folderId = FolderId(folderId)),
            )
        written.shouldBeInstanceOf<AppResult.Success<*>>()
    }

    /** The RPC surface over the same rig, scoped to a caller with [role] — for the admin gate. */
    fun bookService(
        dbs: SqlTestDatabases,
        role: UserRole,
    ): BookServiceImpl {
        val bus = ChangeBus()
        val registry = SyncRegistry()
        return BookServiceImpl(
            repo = repo,
            contributorRepo = ContributorRepository(dbs.sql, bus, registry),
            seriesRepo = SeriesRepository(dbs.sql, bus, registry),
            coverStorage = CoverStorage(),
            sql = dbs.sql,
            genreRepo = GenreRepository(dbs.sql, bus, registry),
            accessPolicy = BookAccessPolicy(db = dbs.sql, driver = dbs.driver),
            permissionPolicy = UserPermissionPolicy(dbs.sql),
            principal = PrincipalProvider { UserPrincipal(UserId("u1"), SessionId("s1"), role) },
            bookDeleter = deleter,
        )
    }
}

private fun deleterRig(dbs: SqlTestDatabases): DeleterRig {
    val bus = ChangeBus()
    val registry = SyncRegistry()
    val repo =
        BookRepository(
            db = dbs.sql,
            driver = dbs.driver,
            bus = bus,
            registry = registry,
            contributorRepository = ContributorRepository(dbs.sql, bus, registry),
            seriesRepository = SeriesRepository(dbs.sql, bus, registry),
            genreRepository = GenreRepository(dbs.sql, bus, registry),
        )
    val journal = WriteJournal(tempJournalDir())
    // The REAL root provider over the test database — not a hand-written allow-list. Containment
    // and the library-root refusal are what these tests are about; stubbing the roots would prove
    // the guards against a fixture rather than against the library.
    val broker = LibraryWriteBroker(SelfWriteRegistry { 0L }, journal, SqlLibraryRootProvider(dbs.sql))
    val sidecarWriteState = SidecarWriteStateRepository(dbs.sql)
    return DeleterRig(
        repo = repo,
        deleter = BookDeleter(dbs.sql, repo, broker, sidecarWriteState),
        journal = journal,
        sidecarWriteState = sidecarWriteState,
    )
}
