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
 * Integration tests for [BookSearchReindexer.reindexAllBooksForContributor].
 *
 * All tests use a real in-memory Flyway-migrated SQLite database. Books, contributors,
 * and junction rows are seeded directly via SQLDelight queries so the FTS5 reindex path
 * can be exercised without a full ingestion stack.
 *
 * Modelled on [BookSearchReindexerTest].
 */
class BookSearchReindexerContributorTest :
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
         * Returns true if a MATCH on `contributor_names` for [searchTerm] finds [rowid].
         *
         * Uses a column-specific MATCH so the assertion is scoped to contributor_names
         * only — not a cross-column hit.
         */
        suspend fun ftsContributorNamesMatch(
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
                        sql = "SELECT rowid FROM book_search WHERE contributor_names MATCH ? AND rowid = ?",
                        mapper = { cursor -> QueryResult.Value(cursor.next().value) },
                        parameters = 2,
                        binders = {
                            bindString(0, quotedTerm)
                            bindLong(1, rowid.toLong())
                        },
                    ).value
            }
        }

        test("should reindex book_search.contributor_names for all linked books when contributor is renamed") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                sql.seedTestBook("book2")
                runTest {
                    seedFtsRow(this@withSqlDatabase, "book1", 1)
                    seedFtsRow(this@withSqlDatabase, "book2", 2)

                    sql.seedContributor("c1", "Brandon Sanderson")
                    sql.bookContributorsQueries.insert(
                        book_id = "book1",
                        contributor_id = "c1",
                        role = "author",
                        credited_as = null,
                        ordinal = 0L,
                    )
                    sql.bookContributorsQueries.insert(
                        book_id = "book2",
                        contributor_id = "c1",
                        role = "author",
                        credited_as = null,
                        ordinal = 0L,
                    )

                    val bus = ChangeBus()
                    val registry = SyncRegistry()
                    val tagRepo = TagRepository(db = sql, bus = bus, registry = registry)
                    val bookTagRepo = BookTagRepository(db = sql, bus = bus, registry = registry)
                    val reindexer = makeReindexer(this@withSqlDatabase, bookTagRepo, tagRepo)

                    // Rename the contributor directly (bypassing the service layer).
                    sql.contributorsQueries.update(
                        id = "c1",
                        name = "B. Sanderson",
                        sort_name = null,
                        asin = null,
                        description = null,
                        image_path = null,
                        birth_date = null,
                        death_date = null,
                        website = null,
                        revision = 2L,
                        updated_at = System.currentTimeMillis(),
                        deleted_at = null,
                        client_op_id = null,
                    )

                    reindexer.reindexAllBooksForContributor("c1")

                    // Both books should now match the new contributor name.
                    ftsContributorNamesMatch(this@withSqlDatabase, 1, "B. Sanderson") shouldBe true
                    ftsContributorNamesMatch(this@withSqlDatabase, 2, "B. Sanderson") shouldBe true
                }
            }
        }

        test("should be no-op when no books are linked to the contributor") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                runTest {
                    sql.seedContributor("c1", "Brandon Sanderson")

                    val bus = ChangeBus()
                    val registry = SyncRegistry()
                    val tagRepo = TagRepository(db = sql, bus = bus, registry = registry)
                    val bookTagRepo = BookTagRepository(db = sql, bus = bus, registry = registry)
                    val reindexer = makeReindexer(this@withSqlDatabase, bookTagRepo, tagRepo)

                    // No junction rows → should complete without error.
                    reindexer.reindexAllBooksForContributor("c1")
                }
            }
        }

        test("should not affect unlinked books when contributor is reindexed") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                sql.seedTestBook("book2")
                runTest {
                    seedFtsRow(this@withSqlDatabase, "book1", 1)
                    seedFtsRow(this@withSqlDatabase, "book2", 2)

                    // book1 is linked to c1; book2 is NOT linked.
                    sql.seedContributor("c1", "Brandon Sanderson")
                    sql.bookContributorsQueries.insert(
                        book_id = "book1",
                        contributor_id = "c1",
                        role = "author",
                        credited_as = null,
                        ordinal = 0L,
                    )

                    val bus = ChangeBus()
                    val registry = SyncRegistry()
                    val tagRepo = TagRepository(db = sql, bus = bus, registry = registry)
                    val bookTagRepo = BookTagRepository(db = sql, bus = bus, registry = registry)
                    val reindexer = makeReindexer(this@withSqlDatabase, bookTagRepo, tagRepo)

                    reindexer.reindexAllBooksForContributor("c1")

                    // book1's contributor_names should match the name from source tables.
                    ftsContributorNamesMatch(this@withSqlDatabase, 1, "Brandon Sanderson") shouldBe true
                    // book2 is unlinked — its contributor_names column is empty and unchanged.
                    ftsContributorNamesMatch(this@withSqlDatabase, 2, "Brandon Sanderson") shouldBe false
                }
            }
        }
    })

/** Seeds a `contributors` row with [contributorId] and [name] into [this] database. */
private fun ListenUpDatabase.seedContributor(
    contributorId: String,
    name: String,
) {
    val now = System.currentTimeMillis()
    contributorsQueries.insert(
        id = contributorId,
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
        image_path = null,
        birth_date = null,
        death_date = null,
        website = null,
    )
}
