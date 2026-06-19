package com.calypsan.listenup.server.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.io.File
import java.nio.file.Files

/** Regenerates the golden schema snapshot from the current MigrationCatalog. */
fun main() {
    val tmp = Files.createTempFile("listenup-snapshot-", ".db").toFile().apply { deleteOnExit() }
    val ds =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:sqlite:${tmp.absolutePath}"
                maximumPoolSize = 1
                isAutoCommit = false
                addDataSourceProperty("foreign_keys", "true")
                validate()
            },
        )
    MigrationRunner(ds).migrate()
    // dumpSchema is the normalized dump defined in MigrationRunnerTest.kt (same test source set
    // + package) — reuse it so the regenerated golden matches the equivalence test exactly.
    File("server/src/test/resources/golden/schema-current.txt").apply {
        parentFile.mkdirs()
        writeText(dumpSchema(ds))
    }
    println("Wrote golden schema snapshot.")
}
