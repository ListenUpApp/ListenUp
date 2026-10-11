package com.calypsan.listenup.server.db

import app.cash.sqldelight.db.QueryResult
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import kotlin.io.path.exists

class DatabaseHandleTest :
    FunSpec({
        test("vacuumInto produces a standalone populated db, and driver close+reopen round-trips") {
            val dir = Files.createTempDirectory("dbhandle-")
            val dbFile = dir.resolve("listenup.db")
            val handle =
                DatabaseFactory.init(
                    DatabaseConfig(jdbcUrl = "jdbc:sqlite:$dbFile"),
                )
            openAdminConnection(handle.dbPath, readOnly = false).use { conn ->
                conn.execute("CREATE TABLE t(id INTEGER PRIMARY KEY, v TEXT)")
                conn.execute("INSERT INTO t(v) VALUES ('hello')")
            }
            val snapshot = dir.resolve("snap.db")
            handle.vacuumInto(snapshot.toAbsolutePath().toString())
            snapshot.exists() shouldBe true

            // The driver can be closed and reopened without corrupting the live db.
            handle.closePool()
            handle.reopenPool()
            val count =
                openAdminConnection(handle.dbPath, readOnly = false).use { conn ->
                    conn.query("SELECT count(*) AS n FROM t") { row -> row.getInt("n") }.firstOrNull() ?: 0
                }
            count shouldBe 1
            handle.close()
        }

        fun openHandleWithRow(): DatabaseHandle {
            val dir = Files.createTempDirectory("dbhandle-closed-")
            val handle = DatabaseFactory.init(DatabaseConfig(jdbcUrl = "jdbc:sqlite:${dir.resolve("listenup.db")}"))
            openAdminConnection(handle.dbPath, readOnly = false).use { conn ->
                conn.execute("CREATE TABLE t(id INTEGER PRIMARY KEY, v TEXT)")
                conn.execute("INSERT INTO t(v) VALUES ('hello')")
            }
            return handle
        }

        fun DatabaseHandle.countRowsThroughDriver(): Long =
            sqlDriver
                .executeQuery(
                    identifier = null,
                    sql = "SELECT count(*) FROM t",
                    mapper = { cursor ->
                        cursor.next()
                        QueryResult.Value(cursor.getLong(0) ?: 0L)
                    },
                    parameters = 0,
                ).value

        test("a query through the driver throws while the pool is closed and works again after reopen") {
            val handle = openHandleWithRow()
            handle.countRowsThroughDriver() shouldBe 1L

            handle.closePool()
            shouldThrow<IllegalStateException> { handle.countRowsThroughDriver() }

            handle.reopenPool()
            handle.countRowsThroughDriver() shouldBe 1L
            handle.close()
        }

        test("a write through the driver throws while the pool is closed and leaves no row behind") {
            val handle = openHandleWithRow()

            handle.closePool()
            shouldThrow<IllegalStateException> {
                handle.sqlDriver.execute(identifier = null, sql = "INSERT INTO t(v) VALUES ('late')", parameters = 0)
            }

            handle.reopenPool()
            handle.countRowsThroughDriver() shouldBe 1L
            handle.close()
        }

        test("a transaction cannot begin through the driver while the pool is closed") {
            val handle = openHandleWithRow()

            handle.closePool()
            shouldThrow<IllegalStateException> { handle.sqlDriver.newTransaction() }

            handle.reopenPool()
            handle.close()
        }
    })
