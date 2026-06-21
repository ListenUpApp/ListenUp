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
            handle.dataSourceForTest().connection.use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("CREATE TABLE t(id INTEGER PRIMARY KEY, v TEXT)")
                    stmt.execute("INSERT INTO t(v) VALUES ('hello')")
                }
            }
            val snapshot = dir.resolve("snap.db")
            handle.vacuumInto(snapshot)
            snapshot.exists() shouldBe true

            // The driver + data source can be closed and reopened without corrupting the live db.
            handle.closePool()
            handle.reopenPool()
            val count =
                handle.dataSourceForTest().connection.use { conn ->
                    conn.createStatement().use { stmt ->
                        stmt.executeQuery("SELECT count(*) FROM t").use { rs -> if (rs.next()) rs.getInt(1) else 0 }
                    }
                }
            count shouldBe 1
            handle.close()
        }
    })
