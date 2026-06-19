package com.calypsan.listenup.server.sync

import com.calypsan.listenup.server.db.MigrationRunner
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.sql.Connection

/**
 * Golden migration test for V40 — the additive `collections.type` column.
 *
 * The column is `NORMAL` by default, and existing inboxes (`is_inbox = 1`) are
 * backfilled to `INBOX`. The migration is behavior-preserving: `is_inbox` is
 * retained. The test partially migrates to V39 (the schema before V40), seeds an
 * inbox collection and a normal collection, runs V40, and asserts the backfill.
 */
class CollectionTypeMigrationTest :
    FunSpec({

        /** A Hikari datasource over a temp-file SQLite database, deleted on JVM exit. */
        fun freshDataSource(): HikariDataSource {
            val tmp = Files.createTempFile("listenup-coll-type-test-", ".db").toFile().apply { deleteOnExit() }
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
        ) = MigrationRunner(dataSource).migrate(upTo = target)

        fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }

        test("V40 backfills collections.type to INBOX for inboxes and NORMAL otherwise") {
            freshDataSource().use { ds ->
                // Schema state BEFORE V40.
                migrateTo(ds, target = 39)

                ds.connection.use { conn ->
                    // A library + owner user to satisfy the foreign keys on collections.
                    conn.exec(
                        "INSERT INTO libraries (id, name, created_at) VALUES ('lib-1', 'Library', 0)",
                    )
                    conn.exec(
                        "INSERT INTO users (id, email, email_normalized, password_hash, role, display_name, " +
                            "status, created_at, updated_at) VALUES " +
                            "('owner-1', 'owner@x', 'owner@x', 'h', 'ADMIN', 'Owner', 'ACTIVE', 0, 0)",
                    )
                    conn.exec(
                        "INSERT INTO collections " +
                            "(id, library_id, owner_id, name, is_inbox, created_at, updated_at, revision) " +
                            "VALUES ('inbox-1', 'lib-1', 'owner-1', 'Inbox', 1, 0, 0, 5)",
                    )
                    conn.exec(
                        "INSERT INTO collections " +
                            "(id, library_id, owner_id, name, is_inbox, created_at, updated_at, revision) " +
                            "VALUES ('norm-1', 'lib-1', 'owner-1', 'My Shelf', 0, 0, 0, 6)",
                    )
                    conn.commit()
                }

                // Apply V40.
                migrateTo(ds, target = null)

                ds.connection.use { conn ->
                    conn.createStatement().use { stmt ->
                        stmt
                            .executeQuery("SELECT is_inbox, type FROM collections WHERE id = 'inbox-1'")
                            .use { rs ->
                                rs.next() shouldBe true
                                rs.getString("type") shouldBe "INBOX"
                                rs.getInt("is_inbox") shouldBe 1 // additive: is_inbox retained, not dropped
                            }
                    }
                    conn.createStatement().use { stmt ->
                        stmt
                            .executeQuery("SELECT is_inbox, type FROM collections WHERE id = 'norm-1'")
                            .use { rs ->
                                rs.next() shouldBe true
                                rs.getString("type") shouldBe "NORMAL"
                                rs.getInt("is_inbox") shouldBe 0 // additive: is_inbox retained, not dropped
                            }
                    }
                }
            }
        }
    })
