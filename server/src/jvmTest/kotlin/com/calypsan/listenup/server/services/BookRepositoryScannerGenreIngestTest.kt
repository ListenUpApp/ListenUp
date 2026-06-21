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
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

class BookRepositoryScannerGenreIngestTest :
    FunSpec({

        test("single aliased string writes to book_genres and leaves pending empty") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val repo = newRepo()
                runTest {
                    val genreId = "g-fantasy"
                    sql.transaction {
                        sql.seedGenre(genreId, name = "Fantasy", slug = "fantasy", path = "/fantasy")
                        sql.seedAlias("Fantasy", genreId)
                    }

                    val result =
                        repo.upsertFromAnalyzed(
                            BookId("b1"),
                            LibraryId("test-library"),
                            FolderId("test-folder"),
                            analyzedFixture(rootRelPath = "books/b1", genres = listOf("Fantasy")),
                        )

                    result.shouldBeInstanceOf<AppResult.Success<BookSyncPayload>>()
                    val bookGenreIds =
                        sql.bookGenresQueries
                            .genresForBook("b1")
                            .executeAsList()
                            .map { it.id }
                    bookGenreIds shouldContainExactly listOf(genreId)
                    sql.pendingBookGenresQueries
                        .bookIdsByRawString("Fantasy")
                        .executeAsList()
                        .shouldBeEmpty()
                }
            }
        }

        test("single unaliased string auto-creates a live genre and leaves pending empty") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val repo = newRepo()
                runTest {
                    val result =
                        repo.upsertFromAnalyzed(
                            BookId("b1"),
                            LibraryId("test-library"),
                            FolderId("test-folder"),
                            analyzedFixture(rootRelPath = "books/b1", genres = listOf("Cyberpunk")),
                        )

                    result.shouldBeInstanceOf<AppResult.Success<BookSyncPayload>>()
                    val names =
                        sql.bookGenresQueries
                            .genresForBook("b1")
                            .executeAsList()
                            .map { it.name }
                    names shouldContainExactly listOf("Cyberpunk")
                    sql.pendingBookGenresQueries
                        .bookIdsByRawString("Cyberpunk")
                        .executeAsList()
                        .shouldBeEmpty()
                }
            }
        }

        test("mixed aliased + unaliased strings both land in book_genres (auto-create), pending empty") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val repo = newRepo()
                runTest {
                    val genreId = "g-fantasy"
                    sql.transaction {
                        sql.seedGenre(genreId, name = "Fantasy", slug = "fantasy", path = "/fantasy")
                        sql.seedAlias("Fantasy", genreId)
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
                    val names =
                        sql.bookGenresQueries
                            .genresForBook("b1")
                            .executeAsList()
                            .map { it.name }
                    names shouldContainExactlyInAnyOrder listOf("Fantasy", "Cyberpunk")
                    sql.pendingBookGenresQueries
                        .bookIdsByRawString("Cyberpunk")
                        .executeAsList()
                        .shouldBeEmpty()
                    sql.pendingBookGenresQueries
                        .bookIdsByRawString("Fantasy")
                        .executeAsList()
                        .shouldBeEmpty()
                }
            }
        }

        test("rescan with different strings wipes prior book_genres") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val repo = newRepo()
                runTest {
                    val genreA = "g-a"
                    val genreC = "g-c"
                    sql.transaction {
                        sql.seedGenre(genreA, name = "A", slug = "a", path = "/a")
                        sql.seedGenre(genreC, name = "C", slug = "c", path = "/c")
                        sql.seedAlias("A", genreA)
                        sql.seedAlias("C", genreC)
                    }

                    // Scan 1: ["A", "B"] — A aliased to genreA, B auto-created as a live genre.
                    repo.upsertFromAnalyzed(
                        BookId("b1"),
                        LibraryId("test-library"),
                        FolderId("test-folder"),
                        analyzedFixture(rootRelPath = "books/b1", genres = listOf("A", "B")),
                    )
                    sql.bookGenresQueries
                        .genresForBook("b1")
                        .executeAsList()
                        .map { it.name } shouldContainExactlyInAnyOrder
                        listOf("A", "B")

                    // Scan 2: ["C"] only — A and B should be wiped from the junction.
                    repo.upsertFromAnalyzed(
                        BookId("b1"),
                        LibraryId("test-library"),
                        FolderId("test-folder"),
                        analyzedFixture(rootRelPath = "books/b1", genres = listOf("C")),
                    )
                    sql.bookGenresQueries
                        .genresForBook("b1")
                        .executeAsList()
                        .map { it.name } shouldContainExactly
                        listOf("C")
                }
            }
        }

        test("alias resolution is case-insensitive via NOCASE collation on raw_string") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val repo = newRepo()
                runTest {
                    val genreId = "g-scifi"
                    sql.transaction {
                        sql.seedGenre(genreId, name = "Sci-Fi", slug = "sci-fi", path = "/sci-fi")
                        sql.seedAlias("Sci-Fi", genreId)
                    }

                    repo.upsertFromAnalyzed(
                        BookId("b1"),
                        LibraryId("test-library"),
                        FolderId("test-folder"),
                        analyzedFixture(rootRelPath = "books/b1", genres = listOf("sci-fi")),
                    )

                    val bookGenreIds =
                        sql.bookGenresQueries
                            .genresForBook("b1")
                            .executeAsList()
                            .map { it.id }
                    bookGenreIds shouldContainExactly listOf(genreId)
                    sql.pendingBookGenresQueries
                        .bookIdsByRawString("sci-fi")
                        .executeAsList()
                        .shouldBeEmpty()
                }
            }
        }

        test("case-distinct duplicates de-dupe to a single book_genres row") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val repo = newRepo()
                runTest {
                    val genreId = "g-fantasy"
                    sql.transaction {
                        sql.seedGenre(genreId, name = "Fantasy", slug = "fantasy", path = "/fantasy")
                        sql.seedAlias("Fantasy", genreId)
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

                    val bookGenreIds =
                        sql.bookGenresQueries
                            .genresForBook("b1")
                            .executeAsList()
                            .map { it.id }
                    bookGenreIds shouldContainExactly listOf(genreId)
                }
            }
        }

        test("readPayload populates BookSyncPayload.genres via JOIN on linked genres") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val repo = newRepo()
                runTest {
                    val gFantasy = "g-fantasy"
                    val gEpic = "g-epic"
                    sql.transaction {
                        sql.seedGenre(gFantasy, name = "Fantasy", slug = "fantasy", path = "/fantasy")
                        sql.seedGenre(gEpic, name = "Epic Fantasy", slug = "epic-fantasy", path = "/fantasy/epic")
                        sql.seedAlias("Fantasy", gFantasy)
                        sql.seedAlias("Epic Fantasy", gEpic)
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
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val repo = newRepo()
                runTest {
                    val gLive = "g-live"
                    val gDead = "g-dead"
                    sql.transaction {
                        sql.seedGenre(gLive, name = "Live", slug = "live", path = "/live")
                        sql.seedGenre(
                            gDead,
                            name = "Dead",
                            slug = "dead",
                            path = "/dead",
                            deletedAt = 1_730_000_000_000L,
                        )
                        sql.seedAlias("Live", gLive)
                        sql.seedAlias("Dead", gDead)
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

private fun SqlTestDatabases.newRepo(): BookRepository {
    val bus = ChangeBus()
    val syncRegistry = SyncRegistry()
    return BookRepository(
        db = sql,
        driver = driver,
        bus = bus,
        registry = syncRegistry,
        contributorRepository = ContributorRepository(sql, bus, syncRegistry),
        seriesRepository = SeriesRepository(sql, bus, syncRegistry),
        genreRepository = GenreRepository(sql, bus, syncRegistry),
    )
}

private fun ListenUpDatabase.seedGenre(
    id: String,
    name: String,
    slug: String,
    path: String,
    deletedAt: Long? = null,
) {
    genresQueries.insert(
        id = id,
        name = name,
        slug = slug,
        path = path,
        parent_id = null,
        depth = 0,
        sort_order = 0,
        color = null,
        description = null,
        revision = 0L,
        created_at = 0L,
        updated_at = 0L,
        deleted_at = deletedAt,
        client_op_id = null,
    )
}

private fun ListenUpDatabase.seedAlias(
    rawString: String,
    genreId: String,
) {
    genreAliasesQueries.deleteByRawString(rawString)
    genreAliasesQueries.insert(raw_string = rawString, genre_id = genreId)
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
