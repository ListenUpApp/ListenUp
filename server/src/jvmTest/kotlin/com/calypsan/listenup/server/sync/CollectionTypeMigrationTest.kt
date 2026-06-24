package com.calypsan.listenup.server.sync

import com.calypsan.listenup.server.db.MigrationRunner
import com.calypsan.listenup.server.testing.fileBackedTestDataSource
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.sql.Connection
import javax.sql.DataSource

/**
 * Golden migration tests for the `collections.type` column lifecycle.
 *
 * V40 added `type TEXT NOT NULL DEFAULT 'NORMAL'` and backfilled existing inboxes
 * (`is_inbox = 1`) to `type = 'INBOX'`. V42 then dropped the now-redundant `is_inbox`
 * column and recreated `idx_collections_inbox` as a `WHERE type = 'INBOX'` partial index.
 *
 * These tests partially migrate to a known schema state, seed data, run forward to a
 * specific version, and assert the expected shape.
 */
class CollectionTypeMigrationTest :
    FunSpec({

        /** Returns (dbPath, DataSource) for a fresh temp-file SQLite database. */
        fun freshDb(): Pair<String, DataSource> {
            val tmp = Files.createTempFile("listenup-coll-type-test-", ".db").toFile().apply { deleteOnExit() }
            val path = tmp.absolutePath
            return path to fileBackedTestDataSource("jdbc:sqlite:$path")
        }

        // Migrates the database at [dbPath] up to [target] (inclusive); null migrates to latest.
        fun migrateTo(
            dbPath: String,
            target: Int?,
        ) = MigrationRunner(dbPath).migrate(upTo = target)

        fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }

        test("V40 backfills collections.type to INBOX for inboxes and NORMAL otherwise") {
            val (path, ds) = freshDb()
            // Schema state BEFORE V40.
            migrateTo(path, target = 39)

            ds.connection.use { conn ->
                conn.autoCommit = false
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

            // Apply V40 only — stop at V41 to keep is_inbox column present for this assertion.
            migrateTo(path, target = 41)

            ds.connection.use { conn ->
                conn.autoCommit = false
                conn.createStatement().use { stmt ->
                    stmt
                        .executeQuery("SELECT is_inbox, type FROM collections WHERE id = 'inbox-1'")
                        .use { rs ->
                            rs.next() shouldBe true
                            rs.getString("type") shouldBe "INBOX"
                            rs.getInt("is_inbox") shouldBe 1
                        }
                }
                conn.createStatement().use { stmt ->
                    stmt
                        .executeQuery("SELECT is_inbox, type FROM collections WHERE id = 'norm-1'")
                        .use { rs ->
                            rs.next() shouldBe true
                            rs.getString("type") shouldBe "NORMAL"
                            rs.getInt("is_inbox") shouldBe 0
                        }
                }
            }
        }

        test("V42 drops is_inbox column and type column remains the sole inbox discriminator") {
            val (path, ds) = freshDb()
            // Schema state BEFORE V40.
            migrateTo(path, target = 39)

            ds.connection.use { conn ->
                conn.autoCommit = false
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

            // Apply all migrations including V42.
            migrateTo(path, target = null)

            ds.connection.use { conn ->
                conn.autoCommit = false
                // type column correctly reflects the backfilled + preserved values.
                conn.createStatement().use { stmt ->
                    stmt
                        .executeQuery("SELECT type FROM collections WHERE id = 'inbox-1'")
                        .use { rs ->
                            rs.next() shouldBe true
                            rs.getString("type") shouldBe "INBOX"
                        }
                }
                conn.createStatement().use { stmt ->
                    stmt
                        .executeQuery("SELECT type FROM collections WHERE id = 'norm-1'")
                        .use { rs ->
                            rs.next() shouldBe true
                            rs.getString("type") shouldBe "NORMAL"
                        }
                }
                // is_inbox column no longer exists — querying it must throw.
                val threw =
                    runCatching {
                        conn.createStatement().use { stmt ->
                            stmt.executeQuery("SELECT is_inbox FROM collections WHERE id = 'inbox-1'").use { rs ->
                                rs.next()
                            }
                        }
                    }.isFailure
                threw shouldBe true
            }
        }
    })
