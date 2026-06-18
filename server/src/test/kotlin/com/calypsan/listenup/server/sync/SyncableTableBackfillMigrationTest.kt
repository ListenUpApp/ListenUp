@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.sync

import com.calypsan.listenup.server.services.ContributorRepository
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.sql.Connection
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.Database

/**
 * Exercises the V13/V14 backfill against **pre-existing rows** — the case no
 * other migration test covers, because [withInMemoryDatabase] always runs the
 * full migration chain on an empty database.
 *
 * The setup partially migrates a fresh database to V12 (the schema state before
 * `contributors`/`book_series` became syncable domains), inserts rows at that
 * schema version, then runs the remaining migrations (V13/V14). This proves the
 * backfill draws revisions from the global counter rather than a local 1..N
 * sequence — the C1 sync-divergence bug.
 */
class SyncableTableBackfillMigrationTest :
    FunSpec({

        /** A Hikari datasource over a temp-file SQLite database, deleted on JVM exit. */
        fun freshDataSource(): HikariDataSource {
            val tmp = Files.createTempFile("listenup-backfill-test-", ".db").toFile().apply { deleteOnExit() }
            return HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = "jdbc:sqlite:${tmp.absolutePath}"
                    maximumPoolSize = 1
                    isAutoCommit = false
                    addDataSourceProperty("foreign_keys", "true")
                    validate()
                },
            )
        }

        // Migrates [dataSource] up to [target] (inclusive); null migrates to latest.
        fun migrateTo(
            dataSource: HikariDataSource,
            target: Int?,
        ) = com.calypsan.listenup.server.db
            .MigrationRunner(dataSource)
            .migrate(upTo = target)

        fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }

        fun Connection.counterValue(): Long =
            createStatement().use { stmt ->
                stmt.executeQuery("SELECT value FROM sync_meta WHERE key = 'revision_counter'").use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }

        fun Connection.revisions(table: String): List<Long> =
            createStatement().use { stmt ->
                stmt.executeQuery("SELECT revision FROM $table ORDER BY rowid").use { rs ->
                    generateSequence { if (rs.next()) rs.getLong(1) else null }.toList()
                }
            }

        test("V13/V14 backfill draws revisions from the global counter and advances it past them") {
            val ds = freshDataSource()

            // 1. Partially migrate to V12 — the schema before B1's syncable promotion.
            migrateTo(ds, target = 12)

            // 2. Insert rows at the V12 schema: more contributors than series, and
            //    advance the global counter so it holds a realistic non-zero value
            //    (e.g. from prior tag/book writes). The bug is invisible at counter 0.
            val counterBeforeBackfill: Long
            ds.connection.use { conn ->
                // Simulate prior cross-domain sync writes: counter sits at 5.
                conn.exec("UPDATE sync_meta SET value = 5 WHERE key = 'revision_counter'")
                (1..8).forEach { i ->
                    conn.exec(
                        "INSERT INTO contributors (id, normalized_name, name) " +
                            "VALUES ('contrib-$i', 'author $i', 'Author $i')",
                    )
                }
                (1..3).forEach { i ->
                    conn.exec(
                        "INSERT INTO book_series (id, normalized_name, name) " +
                            "VALUES ('series-$i', 'series $i', 'Series $i')",
                    )
                }
                conn.commit()
                counterBeforeBackfill = conn.counterValue()
            }
            counterBeforeBackfill shouldBe 5L

            // 3. Run the remaining migrations — V13 then V14 apply the backfill.
            migrateTo(ds, target = null)

            val contributorRevisions: List<Long>
            val seriesRevisions: List<Long>
            val counterAfter: Long
            ds.connection.use { conn ->
                contributorRevisions = conn.revisions("contributors")
                seriesRevisions = conn.revisions("book_series")
                counterAfter = conn.counterValue()
            }
            val allRevisions = contributorRevisions + seriesRevisions

            // Every backfilled revision is non-null, unique, and strictly above the
            // pre-V13 counter value — so no client can have already pulled past it.
            allRevisions.size shouldBe 11
            allRevisions.toSet().size shouldBe 11
            allRevisions.forEach { it shouldBeGreaterThan counterBeforeBackfill }

            // V13 assigns counter+1..counter+8, V14 assigns (counter+8)+1..(counter+8)+3.
            contributorRevisions shouldBe (6L..13L).toList()
            seriesRevisions shouldBe (14L..16L).toList()

            // The counter holds the last revision consumed (the highest backfilled
            // value). `nextRevision()` increments-then-returns, so the next write
            // gets counter+1 — strictly above every backfilled row, which is the
            // property that keeps the post-migration write deliverable.
            counterAfter shouldBe 16L
            counterAfter shouldBe allRevisions.max()
        }

        test("the delivery invariant: a post-migration write lands above every backfilled revision") {
            val ds = freshDataSource()
            migrateTo(ds, target = 12)

            ds.connection.use { conn ->
                conn.exec("UPDATE sync_meta SET value = 5 WHERE key = 'revision_counter'")
                (1..8).forEach { i ->
                    conn.exec(
                        "INSERT INTO contributors (id, normalized_name, name) " +
                            "VALUES ('contrib-$i', 'author $i', 'Author $i')",
                    )
                }
                conn.commit()
            }

            migrateTo(ds, target = null)

            val maxBackfilledRevision: Long
            ds.connection.use { conn ->
                maxBackfilledRevision = conn.revisions("contributors").max()
            }

            // A client that has caught up to `maxBackfilledRevision` uses it as its
            // pull cursor. A new write must produce a revision ABOVE that cursor so
            // `pullSince` delivers it. Before the fix, the post-migration write got
            // `counter+1` — below the backfilled rows — and was silently dropped.
            val db = Database.connect(ds)
            val repo = ContributorRepository(db = db, bus = ChangeBus(), registry = SyncRegistry())
            runTest {
                val newId = repo.resolveOrCreate("Post Migration Author", sortName = null)
                val page = repo.pullSince(userId = null, cursor = maxBackfilledRevision, limit = 100)

                page.items.map { it.id } shouldContain newId.value
                page.items.size shouldBeGreaterThan 0
                repo.findById(newId.value)!!.revision shouldBeGreaterThan maxBackfilledRevision
            }
        }
    })
