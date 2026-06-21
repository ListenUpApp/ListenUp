package com.calypsan.listenup.server.db

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.nio.file.Files

class DatabaseFactoryTest :
    FunSpec({
        test("init runs migrations and yields a working database handle") {
            val tmp = Files.createTempFile("listenup-test-", ".db").toFile().apply { deleteOnExit() }
            val handle = DatabaseFactory.init(DatabaseConfig(jdbcUrl = "jdbc:sqlite:${tmp.absolutePath}"))
            try {
                // Migrations ran — the runner tracks a non-null applied schema version.
                handle.currentSchemaVersion() shouldNotBe null

                // The migrated schema includes the users + sessions tables, readable through the data source.
                val tables =
                    handle.dataSourceForTest().connection.use { conn ->
                        conn.createStatement().use { stmt ->
                            stmt
                                .executeQuery(
                                    "SELECT name FROM sqlite_master WHERE type='table' AND name IN ('users', 'sessions')",
                                ).use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toSet() }
                        }
                    }
                tables shouldBe setOf("users", "sessions")
            } finally {
                handle.close()
            }
        }
    })
