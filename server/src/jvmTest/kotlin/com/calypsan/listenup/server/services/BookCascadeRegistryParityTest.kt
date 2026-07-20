package com.calypsan.listenup.server.services

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.calypsan.listenup.api.sync.BookMoodSyncPayload
import com.calypsan.listenup.api.sync.BookTagSyncPayload
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.Mood
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.sync.BookMoodRepository
import com.calypsan.listenup.server.sync.BookTagRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.CollectionBookRepository
import com.calypsan.listenup.server.sync.MoodRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.sync.TagRepository
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/**
 * The **cascade-registry parity guard** — the check that would have caught the moods/collection_books
 * cascade gap directly. Instead of a hand-maintained list, it discovers the truth by **schema
 * introspection** (`sqlite_master` + `PRAGMA table_info`) and holds it against the declared
 * [bookIdTableDispositions] registry, in both directions. A new `book_id` table therefore cannot slip
 * in without a considered [RemovalDisposition] — and every [RemovalDisposition.CASCADE_TOMBSTONED]
 * table must actually tombstone-on-remove and revive-on-readd, proven behaviourally below.
 */
class BookCascadeRegistryParityTest :
    FunSpec({

        // ── Assertion 1: the declared registry matches the live schema, both directions ──
        test("every book_id table is declared in the disposition registry (and vice versa)") {
            withSqlDatabase {
                val introspected = driver.tablesWithBookIdColumn()
                val declared = bookIdTableDispositions.keys

                val undeclared = introspected - declared
                val stale = declared - introspected

                withClue(
                    "book_id tables missing from bookIdTableDispositions: $undeclared — decide each one's " +
                        "cascade behaviour and, if CASCADE_TOMBSTONED, wire softDelete/reviveByIds for it",
                ) {
                    undeclared shouldBe emptySet()
                }
                withClue(
                    "bookIdTableDispositions names a table with no book_id column (schema drift): $stale",
                ) {
                    stale shouldBe emptySet()
                }
            }
        }

        // ── Assertion 2: every CASCADE_TOMBSTONED table is tombstoned by remove and revived by re-add ──
        test("every CASCADE_TOMBSTONED table is tombstoned by softDelete and revived by reviveByIds") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                val now = System.currentTimeMillis()
                sql.collectionsQueries.insert(
                    id = "c1",
                    library_id = "test-library",
                    owner_id = "owner1",
                    name = "Faves",
                    type = "NORMAL",
                    created_at = now,
                    updated_at = now,
                    revision = 0L,
                    deleted_at = null,
                    client_op_id = null,
                )
                runTest {
                    val bus = ChangeBus()
                    val registry = SyncRegistry()
                    val moodRepo = MoodRepository(db = sql, bus = bus, registry = registry)
                    val tagRepo = TagRepository(db = sql, bus = bus, registry = registry)
                    val bookMoodRepo = BookMoodRepository(db = sql, bus = bus, registry = registry)
                    val bookTagRepo = BookTagRepository(db = sql, bus = bus, registry = registry)
                    val collectionBookRepo =
                        CollectionBookRepository(db = sql, bus = bus, registry = registry, driver = driver)

                    val bookRepo =
                        BookRepository(
                            db = sql,
                            driver = driver,
                            bus = bus,
                            registry = registry,
                            contributorRepository = ContributorRepository(sql, bus, registry),
                            seriesRepository = SeriesRepository(sql, bus, registry),
                            genreRepository = GenreRepository(sql, bus, registry),
                            collectionBookRepository = collectionBookRepo,
                            bookTagRepository = bookTagRepo,
                            bookMoodRepository = bookMoodRepo,
                        )

                    // A live row per CASCADE_TOMBSTONED table, keyed to a book-liveness lambda so a new
                    // cascade table forces a probe here (the map key set must equal the registry's
                    // CASCADE_TOMBSTONED set — checked below).
                    tagRepo.upsert(Tag(id = "t1", name = "Sci-Fi", slug = "sci-fi", revision = 0, updatedAt = 0))
                    moodRepo.upsert(Mood(id = "m1", name = "Tense", slug = "tense", revision = 0, updatedAt = 0))
                    bookTagRepo.upsert(BookTagSyncPayload(id = "book1:t1", bookId = "book1", tagId = "t1", createdAt = 1000L, revision = 0L))
                    bookMoodRepo.upsert(BookMoodSyncPayload(id = "book1:m1", bookId = "book1", moodId = "m1", createdAt = 1000L, revision = 0L))
                    collectionBookRepo.upsert(
                        CollectionBookSyncPayload(id = "c1:book1", collectionId = "c1", bookId = "book1", createdAt = 1000L, revision = 0L),
                    )

                    // live-row count for "book1" per CASCADE_TOMBSTONED table.
                    val liveCount: Map<String, suspend () -> Int> =
                        mapOf(
                            "book_tags" to { bookTagRepo.findAllForBook("book1").size },
                            "book_moods" to { bookMoodRepo.findAllForBook("book1").size },
                            "collection_books" to { collectionBookRepo.findCollectionIdsForBook("book1").size },
                        )

                    val cascadeTables =
                        bookIdTableDispositions
                            .filterValues { it == RemovalDisposition.CASCADE_TOMBSTONED }
                            .keys
                    withClue("a CASCADE_TOMBSTONED table has no behavioural probe wired in this test") {
                        liveCount.keys shouldBe cascadeTables
                    }

                    // Precondition: every probe row is live.
                    for ((table, count) in liveCount) {
                        withClue("$table should have a live row before removal") { count() shouldBe 1 }
                    }

                    // Remove → every CASCADE_TOMBSTONED table's live row is tombstoned.
                    bookRepo.softDelete(BookId("book1"), clientOpId = null)
                    for ((table, count) in liveCount) {
                        withClue("$table must be tombstoned by BookRepository.softDelete") { count() shouldBe 0 }
                    }

                    // Re-add → every CASCADE_TOMBSTONED table's row is revived (floor 0 covers the removal).
                    bookRepo.reviveByIds(listOf(BookId("book1")), cascadeFloor = 0L)
                    for ((table, count) in liveCount) {
                        withClue("$table must be revived by BookRepository.reviveByIds") { count() shouldBe 1 }
                    }
                }
            }
        }
    })

/** The set of tables that carry a `book_id` column, by live schema introspection (excludes `sqlite_%`). */
private fun SqlDriver.tablesWithBookIdColumn(): Set<String> =
    queryStrings(
        "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'",
        column = 0,
    ).filter { table -> "book_id" in columnNames(table) }
        .toSet()

/** Column names of [table] via `PRAGMA table_info` (name is column index 1). Table name is schema-sourced, not user input. */
private fun SqlDriver.columnNames(table: String): List<String> = queryStrings("PRAGMA table_info('$table')", column = 1)

/** Runs [sql] and collects the string value of result [column] from every row. */
private fun SqlDriver.queryStrings(
    sql: String,
    column: Int,
): List<String> =
    executeQuery(
        identifier = null,
        sql = sql,
        mapper = { cursor ->
            val out = mutableListOf<String>()
            while (cursor.next().value) {
                cursor.getString(column)?.let { out += it }
            }
            QueryResult.Value(out.toList())
        },
        parameters = 0,
    ).value
