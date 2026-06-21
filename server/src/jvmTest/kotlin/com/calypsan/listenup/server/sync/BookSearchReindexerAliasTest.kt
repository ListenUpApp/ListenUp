package com.calypsan.listenup.server.sync

import com.calypsan.listenup.server.testing.asSqlDatabase
import com.calypsan.listenup.server.testing.asSqlDriver

import com.calypsan.listenup.server.db.ContributorAliasTable
import com.calypsan.listenup.server.db.ContributorTable
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Integration tests for [BookSearchReindexer.reindexContributorAliases].
 *
 * `contributor_search` is contentless FTS5 (V22) and the `aliases` column is *not*
 * touched by the AI/AU/AD triggers — the application owns it. These tests verify
 * that [BookSearchReindexer.reindexContributorAliases] is the writer that closes
 * the gap.
 *
 * Modelled on [BookSearchReindexerContributorTest]: real in-memory Flyway-migrated
 * SQLite, contributors and alias rows seeded directly via Exposed DSL.
 */
class BookSearchReindexerAliasTest :
    FunSpec({

        fun makeReindexer(
            db: org.jetbrains.exposed.v1.jdbc.Database,
            bookTagRepo: BookTagRepository,
            tagRepo: TagRepository,
        ) = BookSearchReindexer(bookTagRepo, tagRepo, db.asSqlDatabase(), db.asSqlDriver())

        /** Seeds a contributor row. The V22 `contributors_ai` trigger inserts an FTS row with empty aliases. */
        fun seedContributor(
            db: org.jetbrains.exposed.v1.jdbc.Database,
            contributorId: String,
            name: String,
            sortName: String? = null,
            description: String? = null,
        ) {
            val now = System.currentTimeMillis()
            transaction(db) {
                ContributorTable.insert {
                    it[ContributorTable.id] = contributorId
                    it[ContributorTable.normalizedName] = name.lowercase()
                    it[ContributorTable.name] = name
                    it[ContributorTable.sortName] = sortName
                    it[ContributorTable.description] = description
                    it[ContributorTable.revision] = 1L
                    it[ContributorTable.createdAt] = now
                    it[ContributorTable.updatedAt] = now
                }
            }
        }

        /** Inserts alias rows for [contributorId] via [ContributorAliasTable]. */
        suspend fun seedAliases(
            db: org.jetbrains.exposed.v1.jdbc.Database,
            contributorId: String,
            aliases: List<String>,
        ) {
            suspendTransaction(db) {
                ContributorAliasTable.replaceForContributor(contributorId, aliases)
            }
        }

        /**
         * Returns true if a MATCH on `contributor_search.aliases` for [searchTerm]
         * finds the FTS row for [contributorId].
         *
         * Uses a column-specific MATCH so the assertion is scoped to the aliases column
         * only — not a cross-column hit on name/sort_name/description.
         */
        suspend fun ftsAliasesMatch(
            db: org.jetbrains.exposed.v1.jdbc.Database,
            contributorId: String,
            searchTerm: String,
        ): Boolean {
            val dq = '"'
            val quotedTerm = "$dq${searchTerm.replace("$dq", "$dq$dq")}$dq"
            var found = false
            suspendTransaction(db) {
                val tx = TransactionManager.current()
                tx.exec(
                    stmt =
                        "SELECT c.id FROM contributor_search cs " +
                            "JOIN contributors c ON c.rowid = cs.rowid " +
                            "WHERE cs.aliases MATCH ? AND c.id = ?",
                    args =
                        listOf(
                            TextColumnType() to quotedTerm,
                            TextColumnType() to contributorId,
                        ),
                ) { rs ->
                    found = rs.next()
                }
            }
            return found
        }

        test("should populate contributor_search.aliases after reindexContributorAliases") {
            withInMemoryDatabase {
                val db = this
                runTest {
                    seedContributor(db, "c-bachman-merge", "Stephen King", sortName = "King, Stephen")
                    seedAliases(db, "c-bachman-merge", listOf("Richard Bachman", "John Swithen"))

                    val bus = ChangeBus()
                    val registry = SyncRegistry()
                    val tagRepo = TagRepository(db = db.asSqlDatabase(), bus = bus, registry = registry)
                    val bookTagRepo = BookTagRepository(db = db.asSqlDatabase(), bus = bus, registry = registry)
                    val reindexer = makeReindexer(db, bookTagRepo, tagRepo)

                    // Pre-condition: triggers seeded aliases as '' on insert; alias rows
                    // exist in contributor_aliases but the FTS row hasn't been reindexed.
                    ftsAliasesMatch(db, "c-bachman-merge", "Bachman") shouldBe false

                    reindexer.reindexContributorAliases("c-bachman-merge")

                    // Post-condition: both aliases are now FTS-matchable on the aliases column.
                    ftsAliasesMatch(db, "c-bachman-merge", "Bachman") shouldBe true
                    ftsAliasesMatch(db, "c-bachman-merge", "Swithen") shouldBe true
                }
            }
        }

        test("should handle contributor with no aliases gracefully") {
            withInMemoryDatabase {
                val db = this
                runTest {
                    seedContributor(db, "c-no-aliases", "No Aliases")
                    // No alias rows seeded.

                    val bus = ChangeBus()
                    val registry = SyncRegistry()
                    val tagRepo = TagRepository(db = db.asSqlDatabase(), bus = bus, registry = registry)
                    val bookTagRepo = BookTagRepository(db = db.asSqlDatabase(), bus = bus, registry = registry)
                    val reindexer = makeReindexer(db, bookTagRepo, tagRepo)

                    // Should complete without error.
                    reindexer.reindexContributorAliases("c-no-aliases")

                    // No aliases → no MATCH on any term.
                    ftsAliasesMatch(db, "c-no-aliases", "anything") shouldBe false
                }
            }
        }

        test("should be a no-op when contributor does not exist") {
            withInMemoryDatabase {
                val db = this
                runTest {
                    val bus = ChangeBus()
                    val registry = SyncRegistry()
                    val tagRepo = TagRepository(db = db.asSqlDatabase(), bus = bus, registry = registry)
                    val bookTagRepo = BookTagRepository(db = db.asSqlDatabase(), bus = bus, registry = registry)
                    val reindexer = makeReindexer(db, bookTagRepo, tagRepo)

                    // No contributor seeded — rowid lookup returns null and the reindex
                    // should silently no-op rather than throw.
                    reindexer.reindexContributorAliases("does-not-exist")
                }
            }
        }

        test("should refresh aliases column when alias set changes between reindexes") {
            withInMemoryDatabase {
                val db = this
                runTest {
                    seedContributor(db, "c-rotate", "Stephen King")
                    seedAliases(db, "c-rotate", listOf("Richard Bachman"))

                    val bus = ChangeBus()
                    val registry = SyncRegistry()
                    val tagRepo = TagRepository(db = db.asSqlDatabase(), bus = bus, registry = registry)
                    val bookTagRepo = BookTagRepository(db = db.asSqlDatabase(), bus = bus, registry = registry)
                    val reindexer = makeReindexer(db, bookTagRepo, tagRepo)

                    reindexer.reindexContributorAliases("c-rotate")
                    ftsAliasesMatch(db, "c-rotate", "Bachman") shouldBe true

                    // Replace the alias set, then reindex.
                    seedAliases(db, "c-rotate", listOf("John Swithen"))
                    reindexer.reindexContributorAliases("c-rotate")

                    // Old alias is gone; new alias is matchable.
                    ftsAliasesMatch(db, "c-rotate", "Bachman") shouldBe false
                    ftsAliasesMatch(db, "c-rotate", "Swithen") shouldBe true
                }
            }
        }
    })
