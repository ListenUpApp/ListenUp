package com.calypsan.listenup.server.db

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.nio.file.Files

class DatabaseFactoryTest :
    FunSpec({

        test("init runs Flyway migrations and yields a usable Exposed Database") {
            val tmp = Files.createTempFile("listenup-test-", ".db").toFile().apply { deleteOnExit() }
            val cfg = DatabaseConfig(jdbcUrl = "jdbc:sqlite:${tmp.absolutePath}", username = "", password = "")

            val db = DatabaseFactory.init(cfg)

            // Migration produced the users and sessions tables.
            transaction(db) {
                val tables =
                    exec(
                        "SELECT name FROM sqlite_master WHERE type='table' AND name IN ('users', 'sessions')",
                    ) { rs ->
                        generateSequence { if (rs.next()) rs.getString(1) else null }.toSet()
                    } ?: emptySet()
                tables shouldBe setOf("users", "sessions")
            }
        }
    })
