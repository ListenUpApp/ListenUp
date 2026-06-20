@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.api.dto.scanner.CandidateBook
import com.calypsan.listenup.api.dto.scanner.FileEntry
import com.calypsan.listenup.api.dto.scanner.FileType
import com.calypsan.listenup.api.dto.scanner.TrackEntry
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.db.BookGenreTable
import com.calypsan.listenup.server.db.GenreAliasTable
import com.calypsan.listenup.server.db.GenreTable
import com.calypsan.listenup.server.db.PendingBookGenreTable
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import com.calypsan.listenup.server.testing.asSqlDatabase

class BookRepositoryScannerGenreIngestTest :
    FunSpec({

        test("single aliased string writes to book_genres and leaves pending empty") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val syncRegistry = SyncRegistry()
                val repo = newRepo(db, bus, syncRegistry)
                runTest {
                    val genreId = "g-fantasy"
                    transaction(db) {
                        seedGenre(genreId, name = "Fantasy", slug = "fantasy", path = "/fantasy")
                        seedAlias("Fantasy", genreId)
                    }

                    val result =
                        repo.upsertFromAnalyzed(
                            BookId("b1"),
                            LibraryId("test-library"),
                            FolderId("test-folder"),
                            analyzedFixture(rootRelPath = "books/b1", genres = listOf("Fantasy")),
                        )

                    result.shouldBeInstanceOf<AppResult.Success<BookSyncPayload>>()
                    transaction(db) {
                        BookGenreTable.genresForBook("b1") shouldContainExactly listOf(genreId)
                        PendingBookGenreTable.bookIdsByRawString("Fantasy").shouldBeEmpty()
                    }
                }
            }
        }

        test("single unaliased string auto-creates a live genre and leaves pending empty") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val syncRegistry = SyncRegistry()
                val repo = newRepo(db, bus, syncRegistry)
                runTest {
                    val result =
                        repo.upsertFromAnalyzed(
                            BookId("b1"),
                            LibraryId("test-library"),
                            FolderId("test-folder"),
                            analyzedFixture(rootRelPath = "books/b1", genres = listOf("Cyberpunk")),
                        )

                    result.shouldBeInstanceOf<AppResult.Success<BookSyncPayload>>()
                    transaction(db) {
                        genreNamesForBook("b1") shouldContainExactly listOf("Cyberpunk")
                        PendingBookGenreTable.bookIdsByRawString("Cyberpunk").shouldBeEmpty()
                    }
                }
            }
        }

        test("mixed aliased + unaliased strings both land in book_genres (auto-create), pending empty") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val syncRegistry = SyncRegistry()
                val repo = newRepo(db, bus, syncRegistry)
                runTest {
                    val genreId = "g-fantasy"
                    transaction(db) {
                        seedGenre(genreId, name = "Fantasy", slug = "fantasy", path = "/fantasy")
                        seedAlias("Fantasy", genreId)
                    }

                    val result =
                        repo.upsertFromAnalyzed(
                            BookId("b1"),
                            LibraryId("test-library"),
                            FolderId("test-folder"),
                            analyzedFixture(
                                rootRelPath = "books/b1",
                                genres = listOf("Fantasy", "Cyberpunk"),
                            ),
                        )

                    result.shouldBeInstanceOf<AppResult.Success<BookSyncPayload>>()
                    transaction(db) {
                        genreNamesForBook("b1") shouldContainExactlyInAnyOrder listOf("Fantasy", "Cyberpunk")
                        PendingBookGenreTable.bookIdsByRawString("Cyberpunk").shouldBeEmpty()
                        PendingBookGenreTable.bookIdsByRawString("Fantasy").shouldBeEmpty()
                    }
                }
            }
        }

        test("rescan with different strings wipes prior book_genres") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val syncRegistry = SyncRegistry()
                val repo = newRepo(db, bus, syncRegistry)
                runTest {
                    val genreA = "g-a"
                    val genreC = "g-c"
                    transaction(db) {
                        seedGenre(genreA, name = "A", slug = "a", path = "/a")
                        seedGenre(genreC, name = "C", slug = "c", path = "/c")
                        seedAlias("A", genreA)
                        seedAlias("C", genreC)
                    }

                    // Scan 1: ["A", "B"] — A aliased to genreA, B auto-created as a live genre.
                    repo.upsertFromAnalyzed(
                        BookId("b1"),
                        LibraryId("test-library"),
                        FolderId("test-folder"),
                        analyzedFixture(rootRelPath = "books/b1", genres = listOf("A", "B")),
                    )
                    transaction(db) {
                        genreNamesForBook("b1") shouldContainExactlyInAnyOrder listOf("A", "B")
                    }

                    // Scan 2: ["C"] only — A and B should be wiped from the junction.
                    repo.upsertFromAnalyzed(
                        BookId("b1"),
                        LibraryId("test-library"),
                        FolderId("test-folder"),
                        analyzedFixture(rootRelPath = "books/b1", genres = listOf("C")),
                    )
                    transaction(db) {
                        genreNamesForBook("b1") shouldContainExactly listOf("C")
                    }
                }
            }
        }

        test("alias resolution is case-insensitive via NOCASE collation on raw_string") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val syncRegistry = SyncRegistry()
                val repo = newRepo(db, bus, syncRegistry)
                runTest {
                    val genreId = "g-scifi"
                    transaction(db) {
                        seedGenre(genreId, name = "Sci-Fi", slug = "sci-fi", path = "/sci-fi")
                        seedAlias("Sci-Fi", genreId)
                    }

                    repo.upsertFromAnalyzed(
                        BookId("b1"),
                        LibraryId("test-library"),
                        FolderId("test-folder"),
                        analyzedFixture(rootRelPath = "books/b1", genres = listOf("sci-fi")),
                    )

                    transaction(db) {
                        BookGenreTable.genresForBook("b1") shouldContainExactly listOf(genreId)
                        PendingBookGenreTable.bookIdsByRawString("sci-fi").shouldBeEmpty()
                    }
                }
            }
        }

        test("case-distinct duplicates de-dupe to a single book_genres row") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val syncRegistry = SyncRegistry()
                val repo = newRepo(db, bus, syncRegistry)
                runTest {
                    val genreId = "g-fantasy"
                    transaction(db) {
                        seedGenre(genreId, name = "Fantasy", slug = "fantasy", path = "/fantasy")
                        seedAlias("Fantasy", genreId)
                    }

                    repo.upsertFromAnalyzed(
                        BookId("b1"),
                        LibraryId("test-library"),
                        FolderId("test-folder"),
                        analyzedFixture(
                            rootRelPath = "books/b1",
                            genres = listOf("Fantasy", "fantasy", "FANTASY"),
                        ),
                    )

                    transaction(db) {
                        BookGenreTable.genresForBook("b1") shouldContainExactly listOf(genreId)
                    }
                }
            }
        }

        test("readPayload populates BookSyncPayload.genres via JOIN on linked genres") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val syncRegistry = SyncRegistry()
                val repo = newRepo(db, bus, syncRegistry)
                runTest {
                    val gFantasy = "g-fantasy"
                    val gEpic = "g-epic"
                    transaction(db) {
                        seedGenre(gFantasy, name = "Fantasy", slug = "fantasy", path = "/fantasy")
                        seedGenre(gEpic, name = "Epic Fantasy", slug = "epic-fantasy", path = "/fantasy/epic")
                        seedAlias("Fantasy", gFantasy)
                        seedAlias("Epic Fantasy", gEpic)
                    }

                    repo.upsertFromAnalyzed(
                        BookId("b1"),
                        LibraryId("test-library"),
                        FolderId("test-folder"),
                        analyzedFixture(
                            rootRelPath = "books/b1",
                            genres = listOf("Fantasy", "Epic Fantasy"),
                        ),
                    )

                    val payload = repo.findById(BookId("b1"))!!
                    payload.genres shouldHaveSize 2
                    payload.genres.map { it.id } shouldContainExactlyInAnyOrder listOf(gFantasy, gEpic)
                    payload.genres.map { it.name } shouldContainExactlyInAnyOrder listOf("Fantasy", "Epic Fantasy")
                    payload.genres.map { it.path } shouldContainExactlyInAnyOrder
                        listOf("/fantasy", "/fantasy/epic")
                }
            }
        }

        test("readPayload skips tombstoned genres in the genres JOIN") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val syncRegistry = SyncRegistry()
                val repo = newRepo(db, bus, syncRegistry)
                runTest {
                    val gLive = "g-live"
                    val gDead = "g-dead"
                    transaction(db) {
                        seedGenre(gLive, name = "Live", slug = "live", path = "/live")
                        seedGenre(
                            gDead,
                            name = "Dead",
                            slug = "dead",
                            path = "/dead",
                            deletedAt = 1_730_000_000_000L,
                        )
                        seedAlias("Live", gLive)
                        seedAlias("Dead", gDead)
                    }

                    repo.upsertFromAnalyzed(
                        BookId("b1"),
                        LibraryId("test-library"),
                        FolderId("test-folder"),
                        analyzedFixture(rootRelPath = "books/b1", genres = listOf("Live", "Dead")),
                    )

                    val payload = repo.findById(BookId("b1"))!!
                    payload.genres.map { it.id } shouldContainExactly listOf(gLive)
                }
            }
        }
    })

// --- Fixtures ---------------------------------------------------------------

private fun newRepo(
    db: org.jetbrains.exposed.v1.jdbc.Database,
    bus: ChangeBus,
    syncRegistry: SyncRegistry,
): BookRepository =
    BookRepository(
        db = db.asSqlDatabase(),
        exposedDb = db,
        bus = bus,
        registry = syncRegistry,
        contributorRepository = ContributorRepository(db.asSqlDatabase(), bus, syncRegistry),
        seriesRepository = SeriesRepository(db.asSqlDatabase(), bus, syncRegistry),
        genreRepository = GenreRepository(db, bus, syncRegistry),
    )

private fun genreNamesForBook(bookId: String): List<String> =
    BookGenreTable.genresForBook(bookId).map { genreId ->
        GenreTable
            .selectAll()
            .where { GenreTable.id eq genreId }
            .single()[GenreTable.name]
    }

private fun seedGenre(
    id: String,
    name: String,
    slug: String,
    path: String,
    deletedAt: Long? = null,
) {
    GenreTable.insert {
        it[GenreTable.id] = id
        it[GenreTable.name] = name
        it[GenreTable.slug] = slug
        it[GenreTable.path] = path
        it[GenreTable.parentId] = null
        it[GenreTable.depth] = 0
        it[GenreTable.sortOrder] = 0
        it[GenreTable.color] = null
        it[GenreTable.description] = null
        it[GenreTable.revision] = 0L
        it[GenreTable.createdAt] = 0L
        it[GenreTable.updatedAt] = 0L
        it[GenreTable.deletedAt] = deletedAt
        it[GenreTable.clientOpId] = null
    }
}

private fun seedAlias(
    rawString: String,
    genreId: String,
) {
    GenreAliasTable.insert {
        it[GenreAliasTable.rawString] = rawString
        it[GenreAliasTable.genreId] = genreId
    }
}

private fun analyzedFixture(
    rootRelPath: String,
    genres: List<String> = emptyList(),
): AnalyzedBook {
    val file =
        FileEntry(
            relPath = "$rootRelPath/01.m4b",
            name = "01.m4b",
            ext = "m4b",
            size = 1024L,
            mtimeMs = 0L,
            inode = null,
            fileType = FileType.AUDIO,
        )
    return AnalyzedBook(
        candidate =
            CandidateBook(
                rootRelPath = rootRelPath,
                isFile = false,
                files = listOf(file),
            ),
        title = rootRelPath.substringAfterLast('/'),
        tracks = listOf(TrackEntry(file = file)),
        genres = genres,
    )
}
