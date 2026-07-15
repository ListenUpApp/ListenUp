package com.calypsan.listenup.server.sync

import app.cash.sqldelight.db.QueryResult
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/**
 * Integration tests for [BookSearchReindexer.reindexContributorAliases].
 *
 * `contributor_search` is contentless FTS5 (V22) and the `aliases` column is *not*
 * touched by the AI/AU/AD triggers — the application owns it. These tests verify
 * that [BookSearchReindexer.reindexContributorAliases] is the writer that closes
 * the gap.
 *
 * Modelled on [BookSearchReindexerContributorTest]: real in-memory Flyway-migrated
 * SQLite, contributors and alias rows seeded directly via SQLDelight queries.
 */
class BookSearchReindexerAliasTest :
    FunSpec({

        fun makeReindexer(
            dbs: SqlTestDatabases,
            bookTagRepo: BookTagRepository,
            tagRepo: TagRepository,
        ) = BookSearchReindexer(bookTagRepo, tagRepo, dbs.sql, dbs.driver)

        /** Seeds a contributor row. The V22 `contributors_ai` trigger inserts an FTS row with empty aliases. */
        fun seedContributor(
            dbs: SqlTestDatabases,
            contributorId: String,
            name: String,
            sortName: String? = null,
            description: String? = null,
        ) {
            val now = System.currentTimeMillis()
            dbs.sql.contributorsQueries.insert(
                id = contributorId,
                normalized_name = name.lowercase(),
                name = name,
                sort_name = sortName,
                revision = 1L,
                created_at = now,
                updated_at = now,
                deleted_at = null,
                client_op_id = null,
                asin = null,
                description = description,
                image_path = null,
                birth_date = null,
                death_date = null,
                website = null,
            )
        }

        /** Replaces the alias set for [contributorId] via delete-then-insert. */
        fun seedAliases(
            dbs: SqlTestDatabases,
            contributorId: String,
            aliases: List<String>,
        ) {
            dbs.sql.transaction {
                dbs.sql.contributorsQueries.deleteAliasesFor(contributor_id = contributorId)
                aliases.forEach { alias ->
                    dbs.sql.contributorsQueries.insertAlias(
                        contributor_id = contributorId,
                        alias = alias,
                    )
                }
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
            dbs: SqlTestDatabases,
            contributorId: String,
            searchTerm: String,
        ): Boolean {
            val dq = '"'
            val quotedTerm = "$dq${searchTerm.replace("$dq", "$dq$dq")}$dq"
            return withContext(Dispatchers.IO) {
                dbs.driver
                    .executeQuery(
                        identifier = null,
                        sql =
                            "SELECT c.id FROM contributor_search cs " +
                                "JOIN contributors c ON c.rowid = cs.rowid " +
                                "WHERE cs.aliases MATCH ? AND c.id = ?",
                        mapper = { cursor -> QueryResult.Value(cursor.next().value) },
                        parameters = 2,
                        binders = {
                            bindString(0, quotedTerm)
                            bindString(1, contributorId)
                        },
                    ).value
            }
        }

        test("should populate contributor_search.aliases after reindexContributorAliases") {
            withSqlDatabase {
                runTest {
                    seedContributor(this@withSqlDatabase, "c-bachman-merge", "Stephen King", sortName = "King, Stephen")
                    seedAliases(this@withSqlDatabase, "c-bachman-merge", listOf("Richard Bachman", "John Swithen"))

                    val bus = ChangeBus()
                    val registry = SyncRegistry()
                    val tagRepo = TagRepository(db = sql, bus = bus, registry = registry)
                    val bookTagRepo = BookTagRepository(db = sql, bus = bus, registry = registry)
                    val reindexer = makeReindexer(this@withSqlDatabase, bookTagRepo, tagRepo)

                    // Pre-condition: triggers seeded aliases as '' on insert; alias rows
                    // exist in contributor_aliases but the FTS row hasn't been reindexed.
                    ftsAliasesMatch(this@withSqlDatabase, "c-bachman-merge", "Bachman") shouldBe false

                    reindexer.reindexContributorAliases("c-bachman-merge")

                    // Post-condition: both aliases are now FTS-matchable on the aliases column.
                    ftsAliasesMatch(this@withSqlDatabase, "c-bachman-merge", "Bachman") shouldBe true
                    ftsAliasesMatch(this@withSqlDatabase, "c-bachman-merge", "Swithen") shouldBe true
                }
            }
        }

        test("should handle contributor with no aliases gracefully") {
            withSqlDatabase {
                runTest {
                    seedContributor(this@withSqlDatabase, "c-no-aliases", "No Aliases")
                    // No alias rows seeded.

                    val bus = ChangeBus()
                    val registry = SyncRegistry()
                    val tagRepo = TagRepository(db = sql, bus = bus, registry = registry)
                    val bookTagRepo = BookTagRepository(db = sql, bus = bus, registry = registry)
                    val reindexer = makeReindexer(this@withSqlDatabase, bookTagRepo, tagRepo)

                    // Should complete without error.
                    reindexer.reindexContributorAliases("c-no-aliases")

                    // No aliases → no MATCH on any term.
                    ftsAliasesMatch(this@withSqlDatabase, "c-no-aliases", "anything") shouldBe false
                }
            }
        }

        test("should be a no-op when contributor does not exist") {
            withSqlDatabase {
                runTest {
                    val bus = ChangeBus()
                    val registry = SyncRegistry()
                    val tagRepo = TagRepository(db = sql, bus = bus, registry = registry)
                    val bookTagRepo = BookTagRepository(db = sql, bus = bus, registry = registry)
                    val reindexer = makeReindexer(this@withSqlDatabase, bookTagRepo, tagRepo)

                    // No contributor seeded — rowid lookup returns null and the reindex
                    // should silently no-op rather than throw.
                    reindexer.reindexContributorAliases("does-not-exist")
                }
            }
        }

        test("should refresh aliases column when alias set changes between reindexes") {
            withSqlDatabase {
                runTest {
                    seedContributor(this@withSqlDatabase, "c-rotate", "Stephen King")
                    seedAliases(this@withSqlDatabase, "c-rotate", listOf("Richard Bachman"))

                    val bus = ChangeBus()
                    val registry = SyncRegistry()
                    val tagRepo = TagRepository(db = sql, bus = bus, registry = registry)
                    val bookTagRepo = BookTagRepository(db = sql, bus = bus, registry = registry)
                    val reindexer = makeReindexer(this@withSqlDatabase, bookTagRepo, tagRepo)

                    reindexer.reindexContributorAliases("c-rotate")
                    ftsAliasesMatch(this@withSqlDatabase, "c-rotate", "Bachman") shouldBe true

                    // Replace the alias set, then reindex.
                    seedAliases(this@withSqlDatabase, "c-rotate", listOf("John Swithen"))
                    reindexer.reindexContributorAliases("c-rotate")

                    // Old alias is gone; new alias is matchable.
                    ftsAliasesMatch(this@withSqlDatabase, "c-rotate", "Bachman") shouldBe false
                    ftsAliasesMatch(this@withSqlDatabase, "c-rotate", "Swithen") shouldBe true
                }
            }
        }
    })
