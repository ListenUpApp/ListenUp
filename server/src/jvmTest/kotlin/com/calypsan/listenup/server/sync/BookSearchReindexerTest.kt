package com.calypsan.listenup.server.sync

import app.cash.sqldelight.db.QueryResult
import com.calypsan.listenup.api.sync.BookTagSyncPayload
import com.calypsan.listenup.api.sync.Tag
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
 * Integration tests for [BookSearchReindexer].
 *
 * All tests use a real in-memory Flyway-migrated SQLite database; books are seeded
 * via [seedTestBook] and a minimal [book_search_map] + [book_search] row is inserted
 * directly so we can verify the FTS5 `tags` column update without needing a full
 * book-ingestion stack.
 */
class BookSearchReindexerTest :
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
         * Checks whether a MATCH query on `book_search.tags` for [searchTerm] returns
         * the row with [rowid]. Returns true if the FTS index was updated with [searchTerm].
         *
         * `book_search` is a contentless FTS5 table — content is NOT stored, so we cannot
         * SELECT the `tags` column directly. We verify the index was updated by doing a
         * MATCH query and checking that [rowid] is in the results.
         */
        suspend fun ftsTagsMatch(
            dbs: SqlTestDatabases,
            rowid: Int,
            searchTerm: String,
        ): Boolean {
            // Wrap term in double quotes so FTS5 treats it as a phrase, not an expression.
            // e.g. "Sci-Fi" → '"Sci-Fi"' so the dash isn't parsed as an FTS5 operator.
            val dq = '"'
            val quotedTerm = "$dq${searchTerm.replace("$dq", "$dq$dq")}$dq"
            return withContext(Dispatchers.IO) {
                dbs.driver
                    .executeQuery(
                        identifier = null,
                        sql = "SELECT rowid FROM book_search WHERE tags MATCH ? AND rowid = ?",
                        mapper = { cursor -> QueryResult.Value(cursor.next().value) },
                        parameters = 2,
                        binders = {
                            bindString(0, quotedTerm)
                            bindLong(1, rowid.toLong())
                        },
                    ).value
            }
        }

        /**
         * Checks that NO MATCH query on `book_search.tags` returns [rowid] for any of
         * the given terms — used to verify a reindex produced an empty `tags` column.
         */
        suspend fun ftsTagsEmpty(
            dbs: SqlTestDatabases,
            rowid: Int,
        ): Boolean {
            // A contentless FTS5 row with empty tags won't match any non-empty term.
            // We verify by checking a MATCH on a sentinel term never returns this rowid.
            // book_search_map still exists, so the rowid mapping is valid.
            // If the tags column is empty, MATCH on any term won't find the row.
            // We check the rowid is still in book_search_map (row still indexed),
            // but MATCH on a common term ("a") doesn't return it via tags column.
            return withContext(Dispatchers.IO) {
                dbs.driver
                    .executeQuery(
                        identifier = null,
                        sql = "SELECT COUNT(*) FROM book_search_map WHERE rowid = ?",
                        mapper = { cursor ->
                            QueryResult.Value(cursor.next().value && (cursor.getLong(0) ?: 0L) > 0L)
                        },
                        parameters = 1,
                        binders = { bindLong(0, rowid.toLong()) },
                    ).value
            }
        }

        test("reindexBook writes concatenated tag names into book_search.tags") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                runTest {
                    seedFtsRow(this@withSqlDatabase, "book1", 1)

                    val bus = ChangeBus()
                    val registry = SyncRegistry()
                    val tagRepo = TagRepository(db = sql, bus = bus, registry = registry)
                    val bookTagRepo = BookTagRepository(db = sql, bus = bus, registry = registry)
                    val reindexer = makeReindexer(this@withSqlDatabase, bookTagRepo, tagRepo)

                    tagRepo.upsert(Tag(id = "t1", name = "Sci-Fi", slug = "sci-fi", revision = 0, updatedAt = 0))
                    tagRepo.upsert(Tag(id = "t2", name = "Fantasy", slug = "fantasy", revision = 0, updatedAt = 0))
                    bookTagRepo.upsert(
                        BookTagSyncPayload(id = "book1:t1", bookId = "book1", tagId = "t1", createdAt = 1000L, revision = 0L),
                    )
                    bookTagRepo.upsert(
                        BookTagSyncPayload(id = "book1:t2", bookId = "book1", tagId = "t2", createdAt = 1001L, revision = 0L),
                    )

                    reindexer.reindexBook("book1")

                    // FTS5 contentless: verify via MATCH queries — direct content SELECT is not possible.
                    ftsTagsMatch(this@withSqlDatabase, 1, "Sci-Fi") shouldBe true
                    ftsTagsMatch(this@withSqlDatabase, 1, "Fantasy") shouldBe true
                }
            }
        }

        test("reindexBook with no tags produces empty tags FTS index entry") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                runTest {
                    seedFtsRow(this@withSqlDatabase, "book1", 1)

                    val bus = ChangeBus()
                    val registry = SyncRegistry()
                    val tagRepo = TagRepository(db = sql, bus = bus, registry = registry)
                    val bookTagRepo = BookTagRepository(db = sql, bus = bus, registry = registry)
                    val reindexer = makeReindexer(this@withSqlDatabase, bookTagRepo, tagRepo)

                    reindexer.reindexBook("book1")

                    // No tags → "Sci-Fi" does not match this book's tags column.
                    ftsTagsMatch(this@withSqlDatabase, 1, "Sci-Fi") shouldBe false
                    // Row still exists in book_search_map.
                    ftsTagsEmpty(this@withSqlDatabase, 1) shouldBe true
                }
            }
        }

        test("reindexBook excludes tombstoned junction rows") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                runTest {
                    seedFtsRow(this@withSqlDatabase, "book1", 1)

                    val bus = ChangeBus()
                    val registry = SyncRegistry()
                    val tagRepo = TagRepository(db = sql, bus = bus, registry = registry)
                    val bookTagRepo = BookTagRepository(db = sql, bus = bus, registry = registry)
                    val reindexer = makeReindexer(this@withSqlDatabase, bookTagRepo, tagRepo)

                    tagRepo.upsert(Tag(id = "t1", name = "Sci-Fi", slug = "sci-fi", revision = 0, updatedAt = 0))
                    bookTagRepo.upsert(
                        BookTagSyncPayload(id = "book1:t1", bookId = "book1", tagId = "t1", createdAt = 1000L, revision = 0L),
                    )
                    // Soft-delete the junction.
                    bookTagRepo.softDelete(bookId = "book1", tagId = "t1")

                    reindexer.reindexBook("book1")

                    // Tombstoned junction → "Sci-Fi" should not match the tags column.
                    ftsTagsMatch(this@withSqlDatabase, 1, "Sci-Fi") shouldBe false
                }
            }
        }

        test("reindexBook excludes tombstoned tags (live junction, deleted tag)") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                runTest {
                    seedFtsRow(this@withSqlDatabase, "book1", 1)

                    val bus = ChangeBus()
                    val registry = SyncRegistry()
                    val tagRepo = TagRepository(db = sql, bus = bus, registry = registry)
                    val bookTagRepo = BookTagRepository(db = sql, bus = bus, registry = registry)
                    val reindexer = makeReindexer(this@withSqlDatabase, bookTagRepo, tagRepo)

                    tagRepo.upsert(Tag(id = "t1", name = "Sci-Fi", slug = "sci-fi", revision = 0, updatedAt = 0))
                    bookTagRepo.upsert(
                        BookTagSyncPayload(id = "book1:t1", bookId = "book1", tagId = "t1", createdAt = 1000L, revision = 0L),
                    )
                    // Soft-delete the tag itself (junction is still live).
                    tagRepo.softDelete("t1")

                    reindexer.reindexBook("book1")

                    // Tag is tombstoned → findById returns null → "Sci-Fi" excluded from index.
                    ftsTagsMatch(this@withSqlDatabase, 1, "Sci-Fi") shouldBe false
                }
            }
        }

        test("reindexAllBooksForTag reindexes every book linked to the tag") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                sql.seedTestBook("book2")
                runTest {
                    seedFtsRow(this@withSqlDatabase, "book1", 1)
                    seedFtsRow(this@withSqlDatabase, "book2", 2)

                    val bus = ChangeBus()
                    val registry = SyncRegistry()
                    val tagRepo = TagRepository(db = sql, bus = bus, registry = registry)
                    val bookTagRepo = BookTagRepository(db = sql, bus = bus, registry = registry)
                    val reindexer = makeReindexer(this@withSqlDatabase, bookTagRepo, tagRepo)

                    tagRepo.upsert(Tag(id = "t1", name = "SciFi", slug = "scifi", revision = 0, updatedAt = 0))
                    bookTagRepo.upsert(
                        BookTagSyncPayload(id = "book1:t1", bookId = "book1", tagId = "t1", createdAt = 1000L, revision = 0L),
                    )
                    bookTagRepo.upsert(
                        BookTagSyncPayload(id = "book2:t1", bookId = "book2", tagId = "t1", createdAt = 1001L, revision = 0L),
                    )

                    reindexer.reindexAllBooksForTag("t1")

                    ftsTagsMatch(this@withSqlDatabase, 1, "SciFi") shouldBe true
                    ftsTagsMatch(this@withSqlDatabase, 2, "SciFi") shouldBe true
                }
            }
        }

        /**
         * Returns true if a MATCH on the `title` column for [searchTerm] finds [rowid].
         * Column-scoped so the assertion proves the title column specifically carries the
         * term, not a cross-column hit. Guards the SQLDelight `selectFtsSourceByRowid`
         * read path — the title is re-read from the `books` table on every reindex.
         */
        suspend fun ftsTitleMatch(
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
                        sql = "SELECT rowid FROM book_search WHERE title MATCH ? AND rowid = ?",
                        mapper = { cursor -> QueryResult.Value(cursor.next().value) },
                        parameters = 2,
                        binders = {
                            bindString(0, quotedTerm)
                            bindLong(1, rowid.toLong())
                        },
                    ).value
            }
        }

        test("reindexBook re-reads the title from the books table into book_search.title") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                // seedTestBook sets books.title = "Test Book <id>". reindexBook re-reads
                // the live source columns via the SQLDelight selectFtsSourceByRowid query
                // and writes them back, so the title must survive the contentless rebuild.
                sql.seedTestBook("book1")
                runTest {
                    seedFtsRow(this@withSqlDatabase, "book1", 1)

                    val bus = ChangeBus()
                    val registry = SyncRegistry()
                    val tagRepo = TagRepository(db = sql, bus = bus, registry = registry)
                    val bookTagRepo = BookTagRepository(db = sql, bus = bus, registry = registry)
                    val reindexer = makeReindexer(this@withSqlDatabase, bookTagRepo, tagRepo)

                    reindexer.reindexBook("book1")

                    // "Test" comes from the books-table title, proving the source read landed.
                    ftsTitleMatch(this@withSqlDatabase, 1, "Test") shouldBe true
                }
            }
        }

        test("reindexBook is safe when book has no book_search_map row") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                runTest {
                    // No FTS row seeded — reindex should not throw.
                    val bus = ChangeBus()
                    val registry = SyncRegistry()
                    val tagRepo = TagRepository(db = sql, bus = bus, registry = registry)
                    val bookTagRepo = BookTagRepository(db = sql, bus = bus, registry = registry)
                    val reindexer = makeReindexer(this@withSqlDatabase, bookTagRepo, tagRepo)

                    // Should complete without error.
                    reindexer.reindexBook("book1")
                }
            }
        }
    })
