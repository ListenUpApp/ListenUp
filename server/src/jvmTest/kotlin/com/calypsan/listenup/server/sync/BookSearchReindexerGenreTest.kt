package com.calypsan.listenup.server.sync

import app.cash.sqldelight.db.QueryResult
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/**
 * Integration tests for [BookSearchReindexer]'s genre population.
 *
 * Covers:
 *  - [BookSearchReindexer.reindexBook] writes live genre names into the
 *    `book_search.genres` FTS5 column (single + multiple, tombstoned exclusion).
 *  - [BookSearchReindexer.reindexAllBooksForGenre] reindexes every book linked
 *    to a given genre id.
 *  - [BookSearchReindexer.reindexAllBooksForSubtree] reindexes books linked to
 *    the prefix root or any descendant via the collision-safe
 *    `path = ? OR path LIKE ? || '/%'` predicate. The `/fic` vs `/fiction`
 *    collision case is explicitly verified.
 *
 * Modelled on [BookSearchReindexerSeriesTest] — uses a real in-memory
 * Flyway-migrated SQLite database; books, genres and junction rows are seeded
 * directly via SQLDelight queries.
 */
class BookSearchReindexerGenreTest :
    FunSpec({

        fun makeReindexer(
            dbs: SqlTestDatabases,
            bookTagRepo: BookTagRepository,
            tagRepo: TagRepository,
        ) = BookSearchReindexer(bookTagRepo, tagRepo, dbs.sql, dbs.driver)

        /** Seeds a minimal book_search_map + book_search FTS row for [bookId]. */
        suspend fun seedFtsRow(
            dbs: SqlTestDatabases,
            bookId: String,
            rowid: Int,
        ) {
            withContext(Dispatchers.IO) {
                dbs.driver.execute(
                    identifier = null,
                    sql = "INSERT INTO book_search_map(book_id, rowid) VALUES (?, ?)",
                    parameters = 2,
                    binders = {
                        bindString(0, bookId)
                        bindLong(1, rowid.toLong())
                    },
                )
                dbs.driver.execute(
                    identifier = null,
                    sql =
                        "INSERT INTO book_search(rowid, title, subtitle, description, contributor_names, series_names, tags, genres) " +
                            "VALUES ($rowid, ?, '', '', '', '', '', '')",
                    parameters = 1,
                    binders = { bindString(0, "Test Book $bookId") },
                )
            }
        }

        /**
         * Returns true if a MATCH on `genres` for [searchTerm] finds [rowid].
         * Column-specific MATCH so the assertion is scoped to genres only —
         * not a cross-column hit on title/tags/etc.
         */
        suspend fun ftsGenresMatch(
            dbs: SqlTestDatabases,
            rowid: Int,
            searchTerm: String,
        ): Boolean {
            val dq = '"'
            val quotedTerm = "$dq${searchTerm.replace("$dq", "$dq$dq")}$dq"
            return withContext(Dispatchers.IO) {
                dbs.driver
                    .executeQuery(
                        identifier = null,
                        sql = "SELECT rowid FROM book_search WHERE genres MATCH ? AND rowid = ?",
                        mapper = { cursor -> QueryResult.Value(cursor.next().value) },
                        parameters = 2,
                        binders = {
                            bindString(0, quotedTerm)
                            bindLong(1, rowid.toLong())
                        },
                    ).value
            }
        }

        test("reindexBook writes a single genre name into book_search.genres") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                runTest {
                    seedFtsRow(this@withSqlDatabase, "book1", 1)
                    sql.seedGenre("g1", "Fantasy", "fantasy", "/fantasy")
                    sql.bookGenresQueries.insertIfAbsent(book_id = "book1", genre_id = "g1")

                    val bus = ChangeBus()
                    val registry = SyncRegistry()
                    val tagRepo = TagRepository(db = sql, bus = bus, registry = registry)
                    val bookTagRepo = BookTagRepository(db = sql, bus = bus, registry = registry)
                    val reindexer = makeReindexer(this@withSqlDatabase, bookTagRepo, tagRepo)

                    reindexer.reindexBook("book1")

                    ftsGenresMatch(this@withSqlDatabase, 1, "Fantasy") shouldBe true
                }
            }
        }

        test("reindexBook writes multiple genre names into book_search.genres") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                runTest {
                    seedFtsRow(this@withSqlDatabase, "book1", 1)
                    sql.seedGenre("g1", "Fantasy", "fantasy", "/fantasy")
                    sql.seedGenre("g2", "Adventure", "adventure", "/adventure")
                    sql.bookGenresQueries.insertIfAbsent(book_id = "book1", genre_id = "g1")
                    sql.bookGenresQueries.insertIfAbsent(book_id = "book1", genre_id = "g2")

                    val bus = ChangeBus()
                    val registry = SyncRegistry()
                    val tagRepo = TagRepository(db = sql, bus = bus, registry = registry)
                    val bookTagRepo = BookTagRepository(db = sql, bus = bus, registry = registry)
                    val reindexer = makeReindexer(this@withSqlDatabase, bookTagRepo, tagRepo)

                    reindexer.reindexBook("book1")

                    ftsGenresMatch(this@withSqlDatabase, 1, "Fantasy") shouldBe true
                    ftsGenresMatch(this@withSqlDatabase, 1, "Adventure") shouldBe true
                }
            }
        }

        test("reindexBook excludes tombstoned genres") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                runTest {
                    seedFtsRow(this@withSqlDatabase, "book1", 1)
                    sql.seedGenre("g1", "Fantasy", "fantasy", "/fantasy")
                    sql.bookGenresQueries.insertIfAbsent(book_id = "book1", genre_id = "g1")

                    // Tombstone the genre — the JOIN's deleted_at IS NULL clause excludes it.
                    val now = System.currentTimeMillis()
                    sql.genresQueries.softDeleteById(
                        id = "g1",
                        revision = 2L,
                        updated_at = now,
                        deleted_at = now,
                        client_op_id = null,
                    )

                    val bus = ChangeBus()
                    val registry = SyncRegistry()
                    val tagRepo = TagRepository(db = sql, bus = bus, registry = registry)
                    val bookTagRepo = BookTagRepository(db = sql, bus = bus, registry = registry)
                    val reindexer = makeReindexer(this@withSqlDatabase, bookTagRepo, tagRepo)

                    reindexer.reindexBook("book1")

                    ftsGenresMatch(this@withSqlDatabase, 1, "Fantasy") shouldBe false
                }
            }
        }

        test("reindexAllBooksForGenre reindexes every book linked to the genre") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                sql.seedTestBook("book2")
                runTest {
                    seedFtsRow(this@withSqlDatabase, "book1", 1)
                    seedFtsRow(this@withSqlDatabase, "book2", 2)
                    sql.seedGenre("g1", "Fantasy", "fantasy", "/fantasy")
                    sql.bookGenresQueries.insertIfAbsent(book_id = "book1", genre_id = "g1")
                    sql.bookGenresQueries.insertIfAbsent(book_id = "book2", genre_id = "g1")

                    val bus = ChangeBus()
                    val registry = SyncRegistry()
                    val tagRepo = TagRepository(db = sql, bus = bus, registry = registry)
                    val bookTagRepo = BookTagRepository(db = sql, bus = bus, registry = registry)
                    val reindexer = makeReindexer(this@withSqlDatabase, bookTagRepo, tagRepo)

                    reindexer.reindexAllBooksForGenre("g1")

                    ftsGenresMatch(this@withSqlDatabase, 1, "Fantasy") shouldBe true
                    ftsGenresMatch(this@withSqlDatabase, 2, "Fantasy") shouldBe true
                }
            }
        }

        test("reindexAllBooksForSubtree covers descendants via path-prefix join") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                sql.seedTestBook("book2")
                sql.seedTestBook("book3")
                runTest {
                    seedFtsRow(this@withSqlDatabase, "book1", 1)
                    seedFtsRow(this@withSqlDatabase, "book2", 2)
                    seedFtsRow(this@withSqlDatabase, "book3", 3)

                    // Subtree:
                    //   /fiction          ← book1
                    //   /fiction/fantasy  ← book2
                    //   /fiction/sci-fi   ← book3
                    sql.seedGenre("g1", "Fiction", "fiction", "/fiction", depth = 1)
                    sql.seedGenre("g2", "Fantasy", "fantasy", "/fiction/fantasy", parentId = "g1", depth = 2)
                    sql.seedGenre("g3", "Sci-Fi", "sci-fi", "/fiction/sci-fi", parentId = "g1", depth = 2)
                    sql.bookGenresQueries.insertIfAbsent(book_id = "book1", genre_id = "g1")
                    sql.bookGenresQueries.insertIfAbsent(book_id = "book2", genre_id = "g2")
                    sql.bookGenresQueries.insertIfAbsent(book_id = "book3", genre_id = "g3")

                    val bus = ChangeBus()
                    val registry = SyncRegistry()
                    val tagRepo = TagRepository(db = sql, bus = bus, registry = registry)
                    val bookTagRepo = BookTagRepository(db = sql, bus = bus, registry = registry)
                    val reindexer = makeReindexer(this@withSqlDatabase, bookTagRepo, tagRepo)

                    reindexer.reindexAllBooksForSubtree("/fiction")

                    ftsGenresMatch(this@withSqlDatabase, 1, "Fiction") shouldBe true
                    ftsGenresMatch(this@withSqlDatabase, 2, "Fantasy") shouldBe true
                    ftsGenresMatch(this@withSqlDatabase, 3, "Sci-Fi") shouldBe true
                }
            }
        }

        test("reindexAllBooksForSubtree does not touch /fic when prefix is /fiction") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("bookFic")
                sql.seedTestBook("bookFiction")
                runTest {
                    seedFtsRow(this@withSqlDatabase, "bookFic", 1)
                    seedFtsRow(this@withSqlDatabase, "bookFiction", 2)

                    // Two sibling roots whose paths share a leading prefix only —
                    // the collision case `/fic` vs `/fiction`. The reindex must
                    // touch only the `/fiction` book, never the `/fic` book.
                    sql.seedGenre("g-fic", "Fic Genre", "fic", "/fic", depth = 1)
                    sql.seedGenre("g-fiction", "Fiction", "fiction", "/fiction", depth = 1)
                    sql.bookGenresQueries.insertIfAbsent(book_id = "bookFic", genre_id = "g-fic")
                    sql.bookGenresQueries.insertIfAbsent(book_id = "bookFiction", genre_id = "g-fiction")

                    val bus = ChangeBus()
                    val registry = SyncRegistry()
                    val tagRepo = TagRepository(db = sql, bus = bus, registry = registry)
                    val bookTagRepo = BookTagRepository(db = sql, bus = bus, registry = registry)
                    val reindexer = makeReindexer(this@withSqlDatabase, bookTagRepo, tagRepo)

                    reindexer.reindexAllBooksForSubtree("/fiction")

                    // The /fiction book was reindexed — its genres column now has "Fiction".
                    ftsGenresMatch(this@withSqlDatabase, 2, "Fiction") shouldBe true
                    // The /fic book was NOT reindexed — its genres column stays empty;
                    // "Fic Genre" never made it into the index.
                    ftsGenresMatch(this@withSqlDatabase, 1, "Fic") shouldBe false
                    ftsGenresMatch(this@withSqlDatabase, 1, "Fic Genre") shouldBe false
                }
            }
        }
    })

/** Seeds a `genres` row into [this] database. */
private fun ListenUpDatabase.seedGenre(
    genreId: String,
    name: String,
    slug: String,
    path: String,
    parentId: String? = null,
    depth: Int = 0,
) {
    val now = System.currentTimeMillis()
    genresQueries.insert(
        id = genreId,
        name = name,
        slug = slug,
        path = path,
        parent_id = parentId,
        depth = depth.toLong(),
        sort_order = 0L,
        color = null,
        description = null,
        revision = 1L,
        created_at = now,
        updated_at = now,
        deleted_at = null,
        client_op_id = null,
    )
}
