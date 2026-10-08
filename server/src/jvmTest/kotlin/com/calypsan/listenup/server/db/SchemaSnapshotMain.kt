package com.calypsan.listenup.server.db

import com.calypsan.listenup.server.testing.fileBackedTestDataSource
import java.io.File
import java.nio.file.Files
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger("com.calypsan.listenup.server.db.SchemaSnapshotMain")

/** Regenerates the golden schema snapshot from the current MigrationCatalog. */
fun main() {
    val tmp = Files.createTempFile("listenup-snapshot-", ".db").toFile().apply { deleteOnExit() }
    val path = tmp.absolutePath
    val ds = fileBackedTestDataSource("jdbc:sqlite:$path")
    MigrationRunner(path).migrate()
    // dumpSchema is the normalized dump defined in MigrationRunnerTest.kt (same test source set
    // + package) — reuse it so the regenerated golden matches the equivalence test exactly.
    File("server/src/jvmTest/resources/golden/schema-current.txt").apply {
        parentFile.mkdirs()
        writeText(dumpSchema(ds))
    }
    logger.info { "Wrote golden schema snapshot." }
}
