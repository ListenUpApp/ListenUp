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
 * Integration tests for [BookSearchReindexer.reindexAllBooksForSeries].
 *
 * All tests use a real in-memory Flyway-migrated SQLite database. Books, series,
 * and junction rows are seeded directly via SQLDelight queries so the FTS5 reindex
 * path can be exercised without a full ingestion stack.
 *
 * Modelled on [BookSearchReindexerTest].
 */
class BookSearchReindexerSeriesTest :
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
                        "INSERT INTO book_search(rowid, title, subtitle, description, contributor_names, series_names, tags) " +
                            "VALUES ($rowid, ?, '', '', '', '', '')",
                    parameters = 1,
                    binders = { bindString(0, "Test Book $bookId") },
                )
            }
        }

        /**
         * Returns true if a MATCH on `series_names` for [searchTerm] finds [rowid].
         *
         * Uses a column-specific MATCH so the assertion is scoped to series_names
         * only — not a cross-column hit.
         */
        suspend fun ftsSeriesNamesMatch(
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
                        sql = "SELECT rowid FROM book_search WHERE series_names MATCH ? AND rowid = ?",
                        mapper = { cursor -> QueryResult.Value(cursor.next().value) },
                        parameters = 2,
                        binders = {
                            bindString(0, quotedTerm)
                            bindLong(1, rowid.toLong())
                        },
                    ).value
            }
        }

        test("should reindex book_search.series_names for all linked books when series is renamed") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                sql.seedTestBook("book2")
                runTest {
                    seedFtsRow(this@withSqlDatabase, "book1", 1)
                    seedFtsRow(this@withSqlDatabase, "book2", 2)

                    sql.seedSeries("s1", "Stormlight Archive")
                    sql.bookSeriesMembershipsQueries.insert(
                        book_id = "book1",
                        series_id = "s1",
                        sequence = null,
                        ordinal = 0L,
                    )
                    sql.bookSeriesMembershipsQueries.insert(
                        book_id = "book2",
                        series_id = "s1",
                        sequence = null,
                        ordinal = 1L,
                    )

                    val bus = ChangeBus()
                    val registry = SyncRegistry()
                    val tagRepo = TagRepository(db = sql, bus = bus, registry = registry)
                    val bookTagRepo = BookTagRepository(db = sql, bus = bus, registry = registry)
                    val reindexer = makeReindexer(this@withSqlDatabase, bookTagRepo, tagRepo)

                    // Rename the series directly (bypassing the service layer).
                    sql.seriesQueries.update(
                        id = "s1",
                        normalized_name = "the stormlight archive",
                        name = "The Stormlight Archive",
                        sort_name = null,
                        asin = null,
                        description = null,
                        cover_path = null,
                        revision = 2L,
                        updated_at = System.currentTimeMillis(),
                        deleted_at = null,
                        client_op_id = null,
                    )

                    reindexer.reindexAllBooksForSeries("s1")

                    // Both books should now match the new series name.
                    ftsSeriesNamesMatch(this@withSqlDatabase, 1, "The Stormlight Archive") shouldBe true
                    ftsSeriesNamesMatch(this@withSqlDatabase, 2, "The Stormlight Archive") shouldBe true
                }
            }
        }

        test("should be no-op when no books are linked to the series") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                runTest {
                    sql.seedSeries("s1", "Stormlight Archive")

                    val bus = ChangeBus()
                    val registry = SyncRegistry()
                    val tagRepo = TagRepository(db = sql, bus = bus, registry = registry)
                    val bookTagRepo = BookTagRepository(db = sql, bus = bus, registry = registry)
                    val reindexer = makeReindexer(this@withSqlDatabase, bookTagRepo, tagRepo)

                    // No junction rows → should complete without error.
                    reindexer.reindexAllBooksForSeries("s1")
                }
            }
        }

        test("should not affect unlinked books when series is reindexed") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                sql.seedTestBook("book2")
                runTest {
                    seedFtsRow(this@withSqlDatabase, "book1", 1)
                    seedFtsRow(this@withSqlDatabase, "book2", 2)

                    // book1 is linked to s1; book2 is NOT linked.
                    sql.seedSeries("s1", "Stormlight Archive")
                    sql.bookSeriesMembershipsQueries.insert(
                        book_id = "book1",
                        series_id = "s1",
                        sequence = null,
                        ordinal = 0L,
                    )

                    val bus = ChangeBus()
                    val registry = SyncRegistry()
                    val tagRepo = TagRepository(db = sql, bus = bus, registry = registry)
                    val bookTagRepo = BookTagRepository(db = sql, bus = bus, registry = registry)
                    val reindexer = makeReindexer(this@withSqlDatabase, bookTagRepo, tagRepo)

                    reindexer.reindexAllBooksForSeries("s1")

                    // book1's series_names should match the series name from source tables.
                    ftsSeriesNamesMatch(this@withSqlDatabase, 1, "Stormlight Archive") shouldBe true
                    // book2 is unlinked — its series_names column is empty and unchanged.
                    ftsSeriesNamesMatch(this@withSqlDatabase, 2, "Stormlight Archive") shouldBe false
                }
            }
        }
    })

/** Seeds a `book_series` row with [seriesId] and [name] into [this] database. */
private fun ListenUpDatabase.seedSeries(
    seriesId: String,
    name: String,
) {
    val now = System.currentTimeMillis()
    seriesQueries.insert(
        id = seriesId,
        normalized_name = name.lowercase(),
        name = name,
        sort_name = null,
        revision = 1L,
        created_at = now,
        updated_at = now,
        deleted_at = null,
        client_op_id = null,
        asin = null,
        description = null,
        cover_path = null,
    )
}
