package com.calypsan.listenup.server.sync

import com.calypsan.listenup.server.db.MigrationRunner
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.sql.Connection
import java.sql.SQLException

/**
 * Golden migration test for V39 — the in-place rename of `collection_shares` to
 * principal-based `collection_grants`.
 *
 * The rename must be behavior-preserving: every existing row keeps its sync
 * `revision` (no new rows are created, so the global `revision_counter` does not
 * advance), and existing user-shares become USER-principal grants. The test
 * partially migrates to V38 (the schema before V39), seeds a share with a known
 * revision, runs V39, and asserts the row survived with its identity remapped.
 */
class CollectionGrantsRenameMigrationTest :
    FunSpec({

        /** A Hikari datasource over a temp-file SQLite database, deleted on JVM exit. */
        fun freshDataSource(): HikariDataSource {
            val tmp = Files.createTempFile("listenup-grants-test-", ".db").toFile().apply { deleteOnExit() }
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

        test(
            "V39 renames collection_shares to collection_grants, preserving rows/revisions " +
                "and mapping shares to USER principals",
        ) {
            freshDataSource().use { ds ->
                // Schema state BEFORE V39.
                migrateTo(ds, target = 38)

                ds.connection.use { conn ->
                    // A library + users to satisfy the foreign keys on collections/collection_shares.
                    conn.exec(
                        "INSERT INTO libraries (id, name, created_at) VALUES ('lib-1', 'Library', 0)",
                    )
                    conn.exec(
                        "INSERT INTO users (id, email, email_normalized, password_hash, role, display_name, " +
                            "status, created_at, updated_at) VALUES " +
                            "('owner-1', 'owner@x', 'owner@x', 'h', 'ADMIN', 'Owner', 'ACTIVE', 0, 0)",
                    )
                    conn.exec(
                        "INSERT INTO users (id, email, email_normalized, password_hash, role, display_name, " +
                            "status, created_at, updated_at) VALUES " +
                            "('shared-with-1', 'reader@x', 'reader@x', 'h', 'MEMBER', 'Reader', 'ACTIVE', 0, 0)",
                    )
                    conn.exec(
                        "INSERT INTO collections " +
                            "(id, library_id, owner_id, name, created_at, updated_at, revision) " +
                            "VALUES ('coll-1', 'lib-1', 'owner-1', 'My Shelf', 0, 0, 5)",
                    )
                    conn.exec(
                        "INSERT INTO collection_shares " +
                            "(id, collection_id, shared_with_user_id, shared_by_user_id, permission, " +
                            "created_at, updated_at, revision) " +
                            "VALUES ('share-1', 'coll-1', 'shared-with-1', 'owner-1', 'read', 0, 0, 7)",
                    )
                    conn.commit()
                }

                // Apply V39.
                migrateTo(ds, target = null)

                ds.connection.use { conn ->
                    conn.createStatement().use { stmt ->
                        stmt
                            .executeQuery(
                                "SELECT principal_type, principal_id, granted_by_user_id, permission, revision " +
                                    "FROM collection_grants WHERE id = 'share-1'",
                            ).use { rs ->
                                rs.next() shouldBe true
                                rs.getString("principal_type") shouldBe "USER"
                                rs.getString("principal_id") shouldBe "shared-with-1"
                                rs.getString("granted_by_user_id") shouldBe "owner-1"
                                rs.getString("permission") shouldBe "read"
                                rs.getLong("revision") shouldBe 7L
                            }
                    }

                    // The old table no longer exists.
                    conn.createStatement().use { stmt ->
                        stmt
                            .executeQuery(
                                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'collection_shares'",
                            ).use { rs -> rs.next() shouldBe false }
                    }
                }
            }
        }

        test("collection_grants rejects a second active grant for the same (collection, USER principal)") {
            freshDataSource().use { ds ->
                migrateTo(ds, target = null)
                ds.connection.use { conn ->
                    conn.exec("INSERT INTO libraries (id, name, created_at) VALUES ('lib-1', 'Library', 0)")
                    conn.exec(
                        "INSERT INTO users (id, email, email_normalized, password_hash, role, display_name, " +
                            "status, created_at, updated_at) VALUES " +
                            "('owner-1', 'owner@x', 'owner@x', 'h', 'ADMIN', 'Owner', 'ACTIVE', 0, 0)",
                    )
                    conn.exec(
                        "INSERT INTO users (id, email, email_normalized, password_hash, role, display_name, " +
                            "status, created_at, updated_at) VALUES " +
                            "('u2', 'reader@x', 'reader@x', 'h', 'MEMBER', 'Reader', 'ACTIVE', 0, 0)",
                    )
                    conn.exec(
                        "INSERT INTO collections (id, library_id, owner_id, name, created_at, updated_at, revision) " +
                            "VALUES ('coll-1', 'lib-1', 'owner-1', 'My Shelf', 0, 0, 5)",
                    )
                    conn.exec(
                        "INSERT INTO collection_grants (id, collection_id, principal_id, principal_type, " +
                            "granted_by_user_id, permission, created_at, updated_at, revision) " +
                            "VALUES ('g1', 'coll-1', 'u2', 'USER', 'owner-1', 'read', 0, 0, 7)",
                    )
                    conn.commit()

                    // idx_collection_grants_active is UNIQUE on (collection_id, principal_type, principal_id)
                    // among live rows — a second active grant for the same principal must be rejected.
                    shouldThrow<SQLException> {
                        conn.exec(
                            "INSERT INTO collection_grants (id, collection_id, principal_id, principal_type, " +
                                "granted_by_user_id, permission, created_at, updated_at, revision) " +
                                "VALUES ('g2', 'coll-1', 'u2', 'USER', 'owner-1', 'read', 0, 0, 8)",
                        )
                    }
                }
            }
        }
    })
