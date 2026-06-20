package com.calypsan.listenup.server.sync

import com.calypsan.listenup.server.testing.asSqlDatabase

import com.calypsan.listenup.server.db.BookContributorTable
import com.calypsan.listenup.server.db.ContributorTable
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
 * Integration tests for [BookSearchReindexer.reindexAllBooksForContributor].
 *
 * All tests use a real in-memory Flyway-migrated SQLite database. Books, contributors,
 * and junction rows are seeded directly via Exposed DSL so the FTS5 reindex path
 * can be exercised without a full ingestion stack.
 *
 * Modelled on [BookSearchReindexerTest].
 */
class BookSearchReindexerContributorTest :
    FunSpec({

        fun makeReindexer(
            db: org.jetbrains.exposed.v1.jdbc.Database,
            bookTagRepo: BookTagRepository,
            tagRepo: TagRepository,
        ) = BookSearchReindexer(bookTagRepo, tagRepo, db.asSqlDatabase(), db)

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

        /** Seeds a contributor row with the given [contributorId] and [name]. */
        fun seedContributor(
            db: org.jetbrains.exposed.v1.jdbc.Database,
            contributorId: String,
            name: String,
        ) {
            val now = System.currentTimeMillis()
            transaction(db) {
                ContributorTable.insert {
                    it[ContributorTable.id] = contributorId
                    it[ContributorTable.normalizedName] = name.lowercase()
                    it[ContributorTable.name] = name
                    it[ContributorTable.revision] = 1L
                    it[ContributorTable.createdAt] = now
                    it[ContributorTable.updatedAt] = now
                }
            }
        }

        /** Seeds a book_contributors junction row linking [bookId] to [contributorId]. */
        fun seedBookContributor(
            db: org.jetbrains.exposed.v1.jdbc.Database,
            bookId: String,
            contributorId: String,
            ordinal: Int = 0,
        ) {
            transaction(db) {
                BookContributorTable.insert {
                    it[BookContributorTable.bookId] = bookId
                    it[BookContributorTable.contributorId] = contributorId
                    it[BookContributorTable.role] = "author"
                    it[BookContributorTable.ordinal] = ordinal
                }
            }
        }

        /**
         * Returns true if a MATCH on `contributor_names` for [searchTerm] finds [rowid].
         *
         * Uses a column-specific MATCH so the assertion is scoped to contributor_names
         * only — not a cross-column hit.
         */
        suspend fun ftsContributorNamesMatch(
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
                    stmt = "SELECT rowid FROM book_search WHERE contributor_names MATCH ? AND rowid = ?",
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

        test("should reindex book_search.contributor_names for all linked books when contributor is renamed") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestBook("book1")
                seedTestBook("book2")
                runTest {
                    seedFtsRow(db, "book1", 1)
                    seedFtsRow(db, "book2", 2)

                    seedContributor(db, "c1", "Brandon Sanderson")
                    seedBookContributor(db, "book1", "c1", ordinal = 0)
                    seedBookContributor(db, "book2", "c1", ordinal = 0)

                    val bus = ChangeBus()
                    val registry = SyncRegistry()
                    val tagRepo = TagRepository(db = db.asSqlDatabase(), bus = bus, registry = registry)
                    val bookTagRepo = BookTagRepository(db = db.asSqlDatabase(), bus = bus, registry = registry)
                    val reindexer = makeReindexer(db, bookTagRepo, tagRepo)

                    // Rename the contributor directly (bypassing the service layer).
                    transaction(db) {
                        ContributorTable.update({ ContributorTable.id eq "c1" }) {
                            it[ContributorTable.name] = "B. Sanderson"
                            it[ContributorTable.normalizedName] = "b. sanderson"
                        }
                    }

                    reindexer.reindexAllBooksForContributor("c1")

                    // Both books should now match the new contributor name.
                    ftsContributorNamesMatch(db, 1, "B. Sanderson") shouldBe true
                    ftsContributorNamesMatch(db, 2, "B. Sanderson") shouldBe true
                }
            }
        }

        test("should be no-op when no books are linked to the contributor") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                runTest {
                    seedContributor(db, "c1", "Brandon Sanderson")

                    val bus = ChangeBus()
                    val registry = SyncRegistry()
                    val tagRepo = TagRepository(db = db.asSqlDatabase(), bus = bus, registry = registry)
                    val bookTagRepo = BookTagRepository(db = db.asSqlDatabase(), bus = bus, registry = registry)
                    val reindexer = makeReindexer(db, bookTagRepo, tagRepo)

                    // No junction rows → should complete without error.
                    reindexer.reindexAllBooksForContributor("c1")
                }
            }
        }

        test("should not affect unlinked books when contributor is reindexed") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestBook("book1")
                seedTestBook("book2")
                runTest {
                    seedFtsRow(db, "book1", 1)
                    seedFtsRow(db, "book2", 2)

                    // book1 is linked to c1; book2 is NOT linked.
                    seedContributor(db, "c1", "Brandon Sanderson")
                    seedBookContributor(db, "book1", "c1", ordinal = 0)

                    val bus = ChangeBus()
                    val registry = SyncRegistry()
                    val tagRepo = TagRepository(db = db.asSqlDatabase(), bus = bus, registry = registry)
                    val bookTagRepo = BookTagRepository(db = db.asSqlDatabase(), bus = bus, registry = registry)
                    val reindexer = makeReindexer(db, bookTagRepo, tagRepo)

                    reindexer.reindexAllBooksForContributor("c1")

                    // book1's contributor_names should match the name from source tables.
                    ftsContributorNamesMatch(db, 1, "Brandon Sanderson") shouldBe true
                    // book2 is unlinked — its contributor_names column is empty and unchanged.
                    ftsContributorNamesMatch(db, 2, "Brandon Sanderson") shouldBe false
                }
            }
        }
    })
