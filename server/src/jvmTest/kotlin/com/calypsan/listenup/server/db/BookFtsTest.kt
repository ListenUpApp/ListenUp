package com.calypsan.listenup.server.db

import app.cash.sqldelight.db.QueryResult
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * FTS5 round-trip coverage for the `book_search` virtual table.
 *
 * Note on rowids: FTS5's `rowid` column is strictly INTEGER (SQLite rejects string
 * rowids with SQLITE_MISMATCH). This test uses synthetic integer rowids (1, 2);
 * `BookRepository.searchFts` must therefore maintain a stable book-id ↔ integer-rowid
 * mapping when populating the index. Options for the repository: a dedicated
 * `book_search_map(book_id TEXT PRIMARY KEY, rowid INTEGER UNIQUE)` side table, or a
 * deterministic hash of the book UUID into a 63-bit integer. Either way, this
 * detail is invisible to callers — the public `searchFts(query)` API returns
 * `List<BookId>` via a JOIN.
 */
class BookFtsTest :
    FunSpec({

        test("FTS index returns matching book rowids in rank order") {
            withSqlDatabase {
                runBlocking {
                    withContext(Dispatchers.IO) {
                        driver.execute(
                            identifier = null,
                            sql = "INSERT INTO libraries(id, name, created_at, updated_at, revision) VALUES ('lib1', 'Default', 0, 0, 0)",
                            parameters = 0,
                            binders = null,
                        )
                        driver.execute(
                            identifier = null,
                            sql =
                                """
                                INSERT INTO books(
                                    id, library_id, title, total_duration, root_rel_path, scanned_at,
                                    revision, created_at, updated_at
                                ) VALUES
                                    ('b1', 'lib1', 'Way of Kings',   0, 'a', 0, 1, 0, 0),
                                    ('b2', 'lib1', 'Words of Radiance', 0, 'b', 0, 2, 0, 0)
                                """.trimIndent(),
                            parameters = 0,
                            binders = null,
                        )
                        driver.execute(
                            identifier = null,
                            sql =
                                """
                                INSERT INTO book_search(rowid, title) VALUES
                                    (1, 'Way of Kings'),
                                    (2, 'Words of Radiance')
                                """.trimIndent(),
                            parameters = 0,
                            binders = null,
                        )

                        val results =
                            driver
                                .executeQuery(
                                    identifier = null,
                                    sql = "SELECT rowid FROM book_search WHERE book_search MATCH 'Kings' ORDER BY rank",
                                    mapper = { cursor ->
                                        val list = mutableListOf<Long>()
                                        while (cursor.next().value) {
                                            list += cursor.getLong(0)!!
                                        }
                                        QueryResult.Value(list)
                                    },
                                    parameters = 0,
                                    binders = null,
                                ).value
                        results shouldBe listOf(1L)
                    }
                }
            }
        }

        test("FTS index searches multiple columns and ranks results") {
            withSqlDatabase {
                runBlocking {
                    withContext(Dispatchers.IO) {
                        driver.execute(
                            identifier = null,
                            sql = "INSERT INTO libraries(id, name, created_at, updated_at, revision) VALUES ('lib1', 'Default', 0, 0, 0)",
                            parameters = 0,
                            binders = null,
                        )
                        driver.execute(
                            identifier = null,
                            sql =
                                """
                                INSERT INTO books(id, library_id, title, total_duration, root_rel_path, scanned_at,
                                                  revision, created_at, updated_at)
                                VALUES ('b1', 'lib1', 'The Stormlight Archive', 0, 'a', 0, 1, 0, 0),
                                       ('b2', 'lib1', 'Way of Kings', 0, 'b', 0, 2, 0, 0)
                                """.trimIndent(),
                            parameters = 0,
                            binders = null,
                        )
                        driver.execute(
                            identifier = null,
                            sql =
                                """
                                INSERT INTO book_search(rowid, title, contributor_names, series_names)
                                VALUES (1, 'The Stormlight Archive', 'Brandon Sanderson', 'Stormlight'),
                                       (2, 'Way of Kings', 'Brandon Sanderson, Michael Kramer', 'Stormlight')
                                """.trimIndent(),
                            parameters = 0,
                            binders = null,
                        )

                        val byAuthor =
                            driver
                                .executeQuery(
                                    identifier = null,
                                    sql = "SELECT rowid FROM book_search WHERE book_search MATCH 'Sanderson' ORDER BY rank",
                                    mapper = { cursor ->
                                        val list = mutableListOf<Long>()
                                        while (cursor.next().value) {
                                            list += cursor.getLong(0)!!
                                        }
                                        QueryResult.Value(list)
                                    },
                                    parameters = 0,
                                    binders = null,
                                ).value
                        byAuthor.toSet() shouldBe setOf(1L, 2L)

                        val bySeries =
                            driver
                                .executeQuery(
                                    identifier = null,
                                    sql = "SELECT rowid FROM book_search WHERE book_search MATCH 'Stormlight' ORDER BY rank",
                                    mapper = { cursor ->
                                        val list = mutableListOf<Long>()
                                        while (cursor.next().value) {
                                            list += cursor.getLong(0)!!
                                        }
                                        QueryResult.Value(list)
                                    },
                                    parameters = 0,
                                    binders = null,
                                ).value
                        bySeries.toSet() shouldBe setOf(1L, 2L)
                    }
                }
            }
        }
    })
