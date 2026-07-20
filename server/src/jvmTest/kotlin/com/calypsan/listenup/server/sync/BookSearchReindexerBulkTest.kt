package com.calypsan.listenup.server.sync

import app.cash.sqldelight.db.QueryResult
import com.calypsan.listenup.api.sync.BookTagSyncPayload
import com.calypsan.listenup.api.sync.Tag
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
 * Characterization + regression tests for [BookSearchReindexer]'s bulk (multi-book)
 * reindex paths.
 *
 * Tests 1–4 pin the *current* per-book-loop behavior before plan 062's chunked-transaction
 * rewrite — they must pass unmodified against both the old and new implementation, proving
 * the batch rewrite preserves per-book isolation, skip-on-missing-map-row, and tombstone
 * filtering across a multi-book sweep. Tests 5–7 exercise the new [BookSearchReindexer.reindexBooks]
 * surface directly (empty input, chunk-boundary equivalence, per-book tag isolation).
 *
 * Modelled on [BookSearchReindexerTest] and [BookSearchReindexerGenreTest] — real
 * in-memory Flyway-migrated SQLite, FTS rows seeded directly, MATCH-based assertions
 * (contentless FTS5 tables cannot be SELECTed).
 */
class BookSearchReindexerBulkTest :
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
         * Checks whether a MATCH query on `book_search.tags` for [searchTerm] returns
         * the row with [rowid]. `book_search` is contentless — content is NOT stored,
         * so we verify the index was updated via a column-scoped MATCH query.
         */
        suspend fun ftsTagsMatch(
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

        /** Column-scoped MATCH on `book_search.genres` for [searchTerm] against [rowid]. */
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

        test("reindexAllBooksForTag reindexes three books with distinct extra tags") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                sql.seedTestBook("book2")
                sql.seedTestBook("book3")
                runTest {
                    seedFtsRow(this@withSqlDatabase, "book1", 1)
                    seedFtsRow(this@withSqlDatabase, "book2", 2)
                    seedFtsRow(this@withSqlDatabase, "book3", 3)

                    val bus = ChangeBus()
                    val registry = SyncRegistry()
                    val tagRepo = TagRepository(db = sql, bus = bus, registry = registry)
                    val bookTagRepo = BookTagRepository(db = sql, bus = bus, registry = registry)
                    val reindexer = makeReindexer(this@withSqlDatabase, bookTagRepo, tagRepo)

                    tagRepo.upsert(Tag(id = "shared", name = "Shared", slug = "shared", revision = 0, updatedAt = 0))
                    tagRepo.upsert(Tag(id = "u1", name = "Unique1", slug = "unique1", revision = 0, updatedAt = 0))
                    tagRepo.upsert(Tag(id = "u2", name = "Unique2", slug = "unique2", revision = 0, updatedAt = 0))
                    tagRepo.upsert(Tag(id = "u3", name = "Unique3", slug = "unique3", revision = 0, updatedAt = 0))

                    bookTagRepo.upsert(
                        BookTagSyncPayload(id = "book1:shared", bookId = "book1", tagId = "shared", createdAt = 1000L, revision = 0L),
                    )
                    bookTagRepo.upsert(
                        BookTagSyncPayload(id = "book1:u1", bookId = "book1", tagId = "u1", createdAt = 1001L, revision = 0L),
                    )
                    bookTagRepo.upsert(
                        BookTagSyncPayload(id = "book2:shared", bookId = "book2", tagId = "shared", createdAt = 1002L, revision = 0L),
                    )
                    bookTagRepo.upsert(
                        BookTagSyncPayload(id = "book2:u2", bookId = "book2", tagId = "u2", createdAt = 1003L, revision = 0L),
                    )
                    bookTagRepo.upsert(
                        BookTagSyncPayload(id = "book3:shared", bookId = "book3", tagId = "shared", createdAt = 1004L, revision = 0L),
                    )
                    bookTagRepo.upsert(
                        BookTagSyncPayload(id = "book3:u3", bookId = "book3", tagId = "u3", createdAt = 1005L, revision = 0L),
                    )

                    reindexer.reindexAllBooksForTag("shared")

                    ftsTagsMatch(this@withSqlDatabase, 1, "Shared") shouldBe true
                    ftsTagsMatch(this@withSqlDatabase, 2, "Shared") shouldBe true
                    ftsTagsMatch(this@withSqlDatabase, 3, "Shared") shouldBe true

                    ftsTagsMatch(this@withSqlDatabase, 1, "Unique1") shouldBe true
                    ftsTagsMatch(this@withSqlDatabase, 2, "Unique2") shouldBe true
                    ftsTagsMatch(this@withSqlDatabase, 3, "Unique3") shouldBe true

                    // No cross-book contamination: book1 must not carry book2/book3's unique tags.
                    ftsTagsMatch(this@withSqlDatabase, 1, "Unique2") shouldBe false
                    ftsTagsMatch(this@withSqlDatabase, 1, "Unique3") shouldBe false
                    ftsTagsMatch(this@withSqlDatabase, 2, "Unique1") shouldBe false
                    ftsTagsMatch(this@withSqlDatabase, 2, "Unique3") shouldBe false
                    ftsTagsMatch(this@withSqlDatabase, 3, "Unique1") shouldBe false
                    ftsTagsMatch(this@withSqlDatabase, 3, "Unique2") shouldBe false
                }
            }
        }

        test("bulk reindex skips books without a book_search_map row and still indexes the rest") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                sql.seedTestBook("book2")
                sql.seedTestBook("book3")
                runTest {
                    // Only book1 and book2 get FTS rows — book3 has no book_search_map row.
                    seedFtsRow(this@withSqlDatabase, "book1", 1)
                    seedFtsRow(this@withSqlDatabase, "book2", 2)

                    val bus = ChangeBus()
                    val registry = SyncRegistry()
                    val tagRepo = TagRepository(db = sql, bus = bus, registry = registry)
                    val bookTagRepo = BookTagRepository(db = sql, bus = bus, registry = registry)
                    val reindexer = makeReindexer(this@withSqlDatabase, bookTagRepo, tagRepo)

                    tagRepo.upsert(Tag(id = "shared", name = "Shared", slug = "shared", revision = 0, updatedAt = 0))
                    bookTagRepo.upsert(
                        BookTagSyncPayload(id = "book1:shared", bookId = "book1", tagId = "shared", createdAt = 1000L, revision = 0L),
                    )
                    bookTagRepo.upsert(
                        BookTagSyncPayload(id = "book2:shared", bookId = "book2", tagId = "shared", createdAt = 1001L, revision = 0L),
                    )
                    bookTagRepo.upsert(
                        BookTagSyncPayload(id = "book3:shared", bookId = "book3", tagId = "shared", createdAt = 1002L, revision = 0L),
                    )

                    // Should complete without throwing despite book3 having no map row.
                    reindexer.reindexAllBooksForTag("shared")

                    ftsTagsMatch(this@withSqlDatabase, 1, "Shared") shouldBe true
                    ftsTagsMatch(this@withSqlDatabase, 2, "Shared") shouldBe true
                }
            }
        }

        test("bulk reindex excludes tombstoned junctions and tombstoned tags across books") {
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

                    tagRepo.upsert(Tag(id = "dead1", name = "DeadJunction", slug = "dead-junction", revision = 0, updatedAt = 0))
                    tagRepo.upsert(Tag(id = "dead2", name = "DeadTag", slug = "dead-tag", revision = 0, updatedAt = 0))
                    tagRepo.upsert(Tag(id = "shared", name = "Shared", slug = "shared", revision = 0, updatedAt = 0))

                    // book1: tombstoned junction to a live tag.
                    bookTagRepo.upsert(
                        BookTagSyncPayload(id = "book1:dead1", bookId = "book1", tagId = "dead1", createdAt = 1000L, revision = 0L),
                    )
                    bookTagRepo.softDelete(bookId = "book1", tagId = "dead1")
                    bookTagRepo.upsert(
                        BookTagSyncPayload(id = "book1:shared", bookId = "book1", tagId = "shared", createdAt = 1001L, revision = 0L),
                    )

                    // book2: live junction to a tombstoned tag.
                    bookTagRepo.upsert(
                        BookTagSyncPayload(id = "book2:dead2", bookId = "book2", tagId = "dead2", createdAt = 1002L, revision = 0L),
                    )
                    tagRepo.softDelete("dead2")
                    bookTagRepo.upsert(
                        BookTagSyncPayload(id = "book2:shared", bookId = "book2", tagId = "shared", createdAt = 1003L, revision = 0L),
                    )

                    reindexer.reindexAllBooksForTag("shared")

                    ftsTagsMatch(this@withSqlDatabase, 1, "DeadJunction") shouldBe false
                    ftsTagsMatch(this@withSqlDatabase, 2, "DeadTag") shouldBe false
                    ftsTagsMatch(this@withSqlDatabase, 1, "Shared") shouldBe true
                    ftsTagsMatch(this@withSqlDatabase, 2, "Shared") shouldBe true
                }
            }
        }

        test("bulk reindex refreshes the genres column for every book in the set") {
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

        test("reindexBooks resolves each book's genres independently") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                sql.seedTestBook("book2")
                runTest {
                    seedFtsRow(this@withSqlDatabase, "book1", 1)
                    seedFtsRow(this@withSqlDatabase, "book2", 2)
                    sql.seedGenre("g1", "Fantasy", "fantasy", "/fantasy")
                    sql.seedGenre("g2", "Mystery", "mystery", "/mystery")
                    sql.bookGenresQueries.insertIfAbsent(book_id = "book1", genre_id = "g1")
                    sql.bookGenresQueries.insertIfAbsent(book_id = "book2", genre_id = "g2")

                    val bus = ChangeBus()
                    val registry = SyncRegistry()
                    val tagRepo = TagRepository(db = sql, bus = bus, registry = registry)
                    val bookTagRepo = BookTagRepository(db = sql, bus = bus, registry = registry)
                    val reindexer = makeReindexer(this@withSqlDatabase, bookTagRepo, tagRepo)

                    reindexer.reindexBooks(listOf("book1", "book2"))

                    ftsGenresMatch(this@withSqlDatabase, 1, "Fantasy") shouldBe true
                    ftsGenresMatch(this@withSqlDatabase, 1, "Mystery") shouldBe false
                    ftsGenresMatch(this@withSqlDatabase, 2, "Mystery") shouldBe true
                    ftsGenresMatch(this@withSqlDatabase, 2, "Fantasy") shouldBe false
                }
            }
        }

        test("bulk reindex excludes tombstoned genres across books") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                runTest {
                    seedFtsRow(this@withSqlDatabase, "book1", 1)
                    sql.seedGenre("g1", "Fantasy", "fantasy", "/fantasy")
                    sql.seedGenre("g2", "Horror", "horror", "/horror")
                    sql.bookGenresQueries.insertIfAbsent(book_id = "book1", genre_id = "g1")
                    sql.bookGenresQueries.insertIfAbsent(book_id = "book1", genre_id = "g2")

                    // Tombstone one of the two genres — the JOIN's deleted_at IS NULL clause excludes it.
                    val now = System.currentTimeMillis()
                    sql.genresQueries.softDeleteById(
                        id = "g2",
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

                    reindexer.reindexBooks(listOf("book1"))

                    ftsGenresMatch(this@withSqlDatabase, 1, "Fantasy") shouldBe true
                    ftsGenresMatch(this@withSqlDatabase, 1, "Horror") shouldBe false
                }
            }
        }

        test("reindexBooks with an empty list is a no-op") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                runTest {
                    val bus = ChangeBus()
                    val registry = SyncRegistry()
                    val tagRepo = TagRepository(db = sql, bus = bus, registry = registry)
                    val bookTagRepo = BookTagRepository(db = sql, bus = bus, registry = registry)
                    val reindexer = makeReindexer(this@withSqlDatabase, bookTagRepo, tagRepo)

                    // No throw is the assertion.
                    reindexer.reindexBooks(emptyList())
                }
            }
        }

        test("reindexBooks spanning multiple transaction chunks matches single-chunk output") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val bookIds = (1..5).map { "book$it" }
                bookIds.forEach { sql.seedTestBook(it) }
                runTest {
                    bookIds.forEachIndexed { index, bookId -> seedFtsRow(this@withSqlDatabase, bookId, index + 1) }

                    val bus = ChangeBus()
                    val registry = SyncRegistry()
                    val tagRepo = TagRepository(db = sql, bus = bus, registry = registry)
                    val bookTagRepo = BookTagRepository(db = sql, bus = bus, registry = registry)
                    val reindexer = makeReindexer(this@withSqlDatabase, bookTagRepo, tagRepo)

                    bookIds.forEachIndexed { index, bookId ->
                        val tagId = "tag$index"
                        tagRepo.upsert(Tag(id = tagId, name = "TagFor$bookId", slug = "tag-for-$bookId", revision = 0, updatedAt = 0))
                        bookTagRepo.upsert(
                            BookTagSyncPayload(id = "${bookId}:${tagId}", bookId = bookId, tagId = tagId, createdAt = 1000L + index, revision = 0L),
                        )
                    }

                    // 5 books, chunk size 2 → 3 transactions (2 + 2 + 1).
                    reindexer.reindexBooks(bookIds, txChunkSize = 2)

                    bookIds.forEachIndexed { index, bookId ->
                        val rowid = index + 1
                        ftsTagsMatch(this@withSqlDatabase, rowid, "TagFor$bookId") shouldBe true
                    }
                }
            }
        }

        test("reindexBooks resolves each book's tags independently") {
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

                    tagRepo.upsert(Tag(id = "t1", name = "AlphaTag", slug = "alpha-tag", revision = 0, updatedAt = 0))
                    tagRepo.upsert(Tag(id = "t2", name = "BetaTag", slug = "beta-tag", revision = 0, updatedAt = 0))
                    bookTagRepo.upsert(
                        BookTagSyncPayload(id = "book1:t1", bookId = "book1", tagId = "t1", createdAt = 1000L, revision = 0L),
                    )
                    bookTagRepo.upsert(
                        BookTagSyncPayload(id = "book2:t2", bookId = "book2", tagId = "t2", createdAt = 1001L, revision = 0L),
                    )

                    reindexer.reindexBooks(listOf("book1", "book2"))

                    ftsTagsMatch(this@withSqlDatabase, 1, "AlphaTag") shouldBe true
                    ftsTagsMatch(this@withSqlDatabase, 2, "BetaTag") shouldBe true
                    ftsTagsMatch(this@withSqlDatabase, 1, "BetaTag") shouldBe false
                    ftsTagsMatch(this@withSqlDatabase, 2, "AlphaTag") shouldBe false
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
