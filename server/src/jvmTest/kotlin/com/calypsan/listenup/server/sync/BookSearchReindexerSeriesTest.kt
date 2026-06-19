package com.calypsan.listenup.server.sync

import com.calypsan.listenup.server.db.BookSeriesMembershipTable
import com.calypsan.listenup.server.db.BookSeriesTable
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Integration tests for [BookSearchReindexer.reindexAllBooksForSeries].
 *
 * All tests use a real in-memory Flyway-migrated SQLite database. Books, series,
 * and junction rows are seeded directly via Exposed DSL so the FTS5 reindex path
 * can be exercised without a full ingestion stack.
 *
 * Modelled on [BookSearchReindexerTest].
 */
class BookSearchReindexerSeriesTest :
    FunSpec({

        fun makeReindexer(
            db: org.jetbrains.exposed.v1.jdbc.Database,
            bookTagRepo: BookTagRepository,
            tagRepo: TagRepository,
        ) = BookSearchReindexer(bookTagRepo, tagRepo, db)

        /** Seeds a minimal book_search_map + book_search FTS row for [bookId]. */
        suspend fun seedFtsRow(
            db: org.jetbrains.exposed.v1.jdbc.Database,
            bookId: String,
            rowid: Int,
        ) {
            suspendTransaction(db) {
                val tx = TransactionManager.current()
                tx.exec(
                    stmt = "INSERT INTO book_search_map(book_id, rowid) VALUES (?, ?)",
                    args =
                        listOf(
                            TextColumnType() to bookId,
                            IntegerColumnType() to rowid,
                        ),
                )
                tx.exec(
                    stmt =
                        "INSERT INTO book_search(rowid, title, subtitle, description, contributor_names, series_names, tags) " +
                            "VALUES ($rowid, ?, '', '', '', '', '')",
                    args = listOf(TextColumnType() to "Test Book $bookId"),
                )
            }
        }

        /** Seeds a book_series row with the given [seriesId] and [name]. */
        fun seedSeries(
            db: org.jetbrains.exposed.v1.jdbc.Database,
            seriesId: String,
            name: String,
        ) {
            val now = System.currentTimeMillis()
            transaction(db) {
                BookSeriesTable.insert {
                    it[BookSeriesTable.id] = seriesId
                    it[BookSeriesTable.normalizedName] = name.lowercase()
                    it[BookSeriesTable.name] = name
                    it[BookSeriesTable.revision] = 1L
                    it[BookSeriesTable.createdAt] = now
                    it[BookSeriesTable.updatedAt] = now
                }
            }
        }

        /** Seeds a book_series_memberships junction row linking [bookId] to [seriesId]. */
        fun seedBookSeriesMembership(
            db: org.jetbrains.exposed.v1.jdbc.Database,
            bookId: String,
            seriesId: String,
            ordinal: Int = 0,
        ) {
            transaction(db) {
                BookSeriesMembershipTable.insert {
                    it[BookSeriesMembershipTable.bookId] = bookId
                    it[BookSeriesMembershipTable.seriesId] = seriesId
                    it[BookSeriesMembershipTable.ordinal] = ordinal
                }
            }
        }

        /**
         * Returns true if a MATCH on `series_names` for [searchTerm] finds [rowid].
         *
         * Uses a column-specific MATCH so the assertion is scoped to series_names
         * only — not a cross-column hit.
         */
        suspend fun ftsSeriesNamesMatch(
            db: org.jetbrains.exposed.v1.jdbc.Database,
            rowid: Int,
            searchTerm: String,
        ): Boolean {
            val dq = '"'
            val quotedTerm = "$dq${searchTerm.replace("$dq", "$dq$dq")}$dq"
            var found = false
            suspendTransaction(db) {
                val tx = TransactionManager.current()
                tx.exec(
                    stmt = "SELECT rowid FROM book_search WHERE series_names MATCH ? AND rowid = ?",
                    args =
                        listOf(
                            TextColumnType() to quotedTerm,
                            IntegerColumnType() to rowid,
                        ),
                ) { rs ->
                    found = rs.next()
                }
            }
            return found
        }

        test("should reindex book_search.series_names for all linked books when series is renamed") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestBook("book1")
                seedTestBook("book2")
                runTest {
                    seedFtsRow(db, "book1", 1)
                    seedFtsRow(db, "book2", 2)

                    seedSeries(db, "s1", "Stormlight Archive")
                    seedBookSeriesMembership(db, "book1", "s1", ordinal = 0)
                    seedBookSeriesMembership(db, "book2", "s1", ordinal = 1)

                    val bus = ChangeBus()
                    val registry = SyncRegistry()
                    val tagRepo = TagRepository(db = db, bus = bus, registry = registry)
                    val bookTagRepo = BookTagRepository(db = db, bus = bus, registry = registry)
                    val reindexer = makeReindexer(db, bookTagRepo, tagRepo)

                    // Rename the series directly (bypassing the service layer).
                    transaction(db) {
                        BookSeriesTable.update({ BookSeriesTable.id eq "s1" }) {
                            it[BookSeriesTable.name] = "The Stormlight Archive"
                            it[BookSeriesTable.normalizedName] = "the stormlight archive"
                        }
                    }

                    reindexer.reindexAllBooksForSeries("s1")

                    // Both books should now match the new series name.
                    ftsSeriesNamesMatch(db, 1, "The Stormlight Archive") shouldBe true
                    ftsSeriesNamesMatch(db, 2, "The Stormlight Archive") shouldBe true
                }
            }
        }

        test("should be no-op when no books are linked to the series") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                runTest {
                    seedSeries(db, "s1", "Stormlight Archive")

                    val bus = ChangeBus()
                    val registry = SyncRegistry()
                    val tagRepo = TagRepository(db = db, bus = bus, registry = registry)
                    val bookTagRepo = BookTagRepository(db = db, bus = bus, registry = registry)
                    val reindexer = makeReindexer(db, bookTagRepo, tagRepo)

                    // No junction rows → should complete without error.
                    reindexer.reindexAllBooksForSeries("s1")
                }
            }
        }

        test("should not affect unlinked books when series is reindexed") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestBook("book1")
                seedTestBook("book2")
                runTest {
                    seedFtsRow(db, "book1", 1)
                    seedFtsRow(db, "book2", 2)

                    // book1 is linked to s1; book2 is NOT linked.
                    seedSeries(db, "s1", "Stormlight Archive")
                    seedBookSeriesMembership(db, "book1", "s1", ordinal = 0)

                    val bus = ChangeBus()
                    val registry = SyncRegistry()
                    val tagRepo = TagRepository(db = db, bus = bus, registry = registry)
                    val bookTagRepo = BookTagRepository(db = db, bus = bus, registry = registry)
                    val reindexer = makeReindexer(db, bookTagRepo, tagRepo)

                    reindexer.reindexAllBooksForSeries("s1")

                    // book1's series_names should match the series name from source tables.
                    ftsSeriesNamesMatch(db, 1, "Stormlight Archive") shouldBe true
                    // book2 is unlinked — its series_names column is empty and unchanged.
                    ftsSeriesNamesMatch(db, 2, "Stormlight Archive") shouldBe false
                }
            }
        }
    })
