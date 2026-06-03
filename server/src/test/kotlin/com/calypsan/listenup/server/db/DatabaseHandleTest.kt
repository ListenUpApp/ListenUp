package com.calypsan.listenup.server.db

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.nio.file.Files
import kotlin.io.path.exists

class DatabaseHandleTest :
    FunSpec({
        test("vacuumInto produces a standalone populated db, and pool suspend/resume round-trips") {
            val dir = Files.createTempDirectory("dbhandle-")
            val dbFile = dir.resolve("listenup.db")
            val handle =
                DatabaseFactory.init(
                    DatabaseConfig(jdbcUrl = "jdbc:sqlite:$dbFile", username = "", password = "", maxPoolSize = 4),
                )
            transaction(handle.database) {
                exec("CREATE TABLE t(id INTEGER PRIMARY KEY, v TEXT)")
                exec("INSERT INTO t(v) VALUES ('hello')")
            }
            val snapshot = dir.resolve("snap.db")
            handle.vacuumInto(snapshot)
            snapshot.exists() shouldBe true

            // pool can be frozen and thawed without corrupting the live db
            handle.suspendPool()
            handle.evictConnections()
            handle.resumePool()
            transaction(handle.database) {
                val count = exec("SELECT count(*) FROM t") { rs -> if (rs.next()) rs.getInt(1) else 0 }
                count shouldBe 1
            }
            handle.close()
        }
    })
