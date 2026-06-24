package com.calypsan.listenup.server.db

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
    })
