package com.calypsan.listenup.server.backup

import com.calypsan.listenup.api.dto.backup.BackupEvent
import com.calypsan.listenup.api.dto.backup.RestoreResult
import com.calypsan.listenup.api.error.BackupError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BackupId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.exists

/**
 * Adversarial test suite for [RestoreOrchestrator].
 *
 * Verifies the safety properties of the restore sequence:
 * 1. Happy-path round-trip (row A survives restore)
 * 2. Rollback on corrupt-but-checksum-valid snapshot (garbage db → pool stays resumed, gate drops)
 * 3. IncompatibleSchema rejected before the gate is entered
 * 4. Metadata-only restore leaves image dirs untouched
 * 5. Single-flight: second restore while one is in progress → RestoreInProgress
 * 6. BackupNotFound for a non-existent id
 */
class RestoreOrchestratorTest :
    FunSpec({

        fun buildOrchestrator(
            fixture: BackupTestFixture,
            maintenance: MaintenanceState = MaintenanceState(),
            eventBus: MutableSharedFlow<BackupEvent> = MutableSharedFlow(extraBufferCapacity = 64),
        ): RestoreOrchestrator =
            RestoreOrchestrator(
                paths = fixture.paths,
                archive = fixture.archive,
                dbHandle = fixture.handle,
                maintenance = maintenance,
                eventBus = eventBus,
            )

        test("round-trip restore: db returns to pre-backup state after restore") {
            runTest {
                backupTestFixture(withImages = false).use { fixture ->
                    // Seed row A before backup
                    transaction(fixture.handle.database) {
                        exec("CREATE TABLE IF NOT EXISTS restore_test(v TEXT)")
                        exec("INSERT INTO restore_test(v) VALUES ('row-A')")
                    }

                    val archivePath = fixture.archive.create("rt1", includeImages = false, onEvent = {})
                    val backupId = BackupId("rt1")

                    // Mutate to row B after backup
                    transaction(fixture.handle.database) {
                        exec("DELETE FROM restore_test")
                        exec("INSERT INTO restore_test(v) VALUES ('row-B')")
                    }

                    val orchestrator = buildOrchestrator(fixture)
                    val result = orchestrator.restore(backupId)

                    result.shouldBeInstanceOf<AppResult.Success<RestoreResult>>()
                    result.data.restoredFrom shouldBe backupId
                    result.data.includedImages shouldBe false

                    // row A is back, row B is gone
                    transaction(fixture.handle.database) {
                        val v =
                            exec("SELECT v FROM restore_test") { rs ->
                                if (rs.next()) rs.getString(1) else null
                            }
                        v shouldBe "row-A"
                    }
                }
            }
        }

        test("rollback on corrupt-but-checksum-valid snapshot: row B preserved, pool resumed, gate dropped") {
            runTest {
                backupTestFixture(withImages = false).use { fixture ->
                    // Seed row B in the live db — this must survive the failed restore
                    transaction(fixture.handle.database) {
                        exec("CREATE TABLE IF NOT EXISTS rollback_test(v TEXT)")
                        exec("INSERT INTO rollback_test(v) VALUES ('row-B')")
                    }

                    // Build a MALICIOUS archive by hand:
                    // - listenup.db entry = garbage bytes (not a real SQLite file)
                    // - manifest checksum = the real sha256 of those garbage bytes (so validate() PASSES)
                    // - schemaVersion = "0" (≤ server's current schema → not rejected as IncompatibleSchema)
                    val garbageBytes = "not-a-sqlite-database-at-all".toByteArray()
                    val garbageChecksum = sha256OfBytes(garbageBytes)

                    val manifest =
                        BackupManifest(
                            formatVersion = BackupManifest.FORMAT_VERSION,
                            serverId = "srv-test",
                            createdAt = System.currentTimeMillis(),
                            appVersion = "0.0.0-test",
                            schemaVersion = "0", // ≤ any real schema version
                            includesImages = false,
                            checksums = mapOf("db" to garbageChecksum),
                            bookCount = 0,
                            userCount = 0,
                        )

                    // Write the malicious archive to the expected path
                    fixture.paths.ensureDirs()
                    val archivePath = fixture.paths.archiveFor("mal1")
                    ZipOutputStream(Files.newOutputStream(archivePath)).use { zip ->
                        zip.putNextEntry(ZipEntry("listenup.db"))
                        zip.write(garbageBytes)
                        zip.closeEntry()
                        zip.putNextEntry(ZipEntry("manifest.json"))
                        zip.write(manifest.toJson().toByteArray())
                        zip.closeEntry()
                    }

                    val maintenance = MaintenanceState()
                    val orchestrator = buildOrchestrator(fixture, maintenance)
                    val result = orchestrator.restore(BackupId("mal1"))

                    // Must fail with RestoreFailed(rolledBack = true)
                    val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                    val error = failure.error.shouldBeInstanceOf<BackupError.RestoreFailed>()
                    error.rolledBack shouldBe true

                    // Gate must be dropped
                    maintenance.isActive shouldBe false

                    // Pool must be resumed: a normal DB query must work
                    transaction(fixture.handle.database) {
                        val v =
                            exec("SELECT v FROM rollback_test") { rs ->
                                if (rs.next()) rs.getString(1) else null
                            }
                        // row B is still there (rollback restored original db)
                        v shouldBe "row-B"
                    }
                }
            }
        }

        test("IncompatibleSchema: rejected before gate is entered, db untouched") {
            runTest {
                backupTestFixture(withImages = false).use { fixture ->
                    // Create a legitimate archive first (to have a valid archive structure)
                    val archivePath = fixture.archive.create("incompat1", includeImages = false, onEvent = {})

                    // Now build a malicious archive at a different id with a huge schema version
                    val realManifest = fixture.archive.open(archivePath)
                    val futureManifest = realManifest.copy(schemaVersion = "99999")

                    // We need to rebuild the archive with the modified manifest but preserve the db entry
                    val incompatPath = fixture.paths.archiveFor("incompat2")
                    rebuildArchiveWithManifest(archivePath, incompatPath, futureManifest)

                    val maintenance = MaintenanceState()
                    val orchestrator = buildOrchestrator(fixture, maintenance)
                    val result = orchestrator.restore(BackupId("incompat2"))

                    val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                    failure.error.shouldBeInstanceOf<BackupError.IncompatibleSchema>()

                    // Gate was never entered (schema check runs before enter())
                    maintenance.isActive shouldBe false

                    // A normal db query works fine
                    transaction(fixture.handle.database) {
                        exec("SELECT 1") { rs -> rs.next() }
                    }
                }
            }
        }

        test("metadata-only restore: sentinel file in coversDir survives because includeImages=false") {
            runTest {
                backupTestFixture(withImages = false).use { fixture ->
                    // Write a sentinel file into coversDir before creating the backup
                    Files.createDirectories(fixture.paths.coversDir)
                    val sentinel = fixture.paths.coversDir.resolve("sentinel.jpg")
                    Files.write(sentinel, byteArrayOf(0x01, 0x02, 0x03))

                    // Create a metadata-only backup (no images)
                    fixture.archive.create("meta1", includeImages = false, onEvent = {})

                    val orchestrator = buildOrchestrator(fixture)
                    val result = orchestrator.restore(BackupId("meta1"))

                    val success = result.shouldBeInstanceOf<AppResult.Success<RestoreResult>>()
                    success.data.includedImages shouldBe false

                    // Sentinel file must still exist — images were not swapped
                    sentinel.exists() shouldBe true
                }
            }
        }

        test("single-flight: second restore while gate is held returns RestoreInProgress") {
            runTest {
                backupTestFixture(withImages = false).use { fixture ->
                    fixture.archive.create("sf1", includeImages = false, onEvent = {})

                    val maintenance = MaintenanceState()
                    // Simulate an in-progress restore by entering the gate directly
                    maintenance.enter() shouldBe true

                    val orchestrator = buildOrchestrator(fixture, maintenance)
                    val result = orchestrator.restore(BackupId("sf1"))

                    val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                    failure.error.shouldBeInstanceOf<BackupError.RestoreInProgress>()

                    // Release the simulated restore
                    maintenance.exit()
                    maintenance.isActive shouldBe false
                }
            }
        }

        test("BackupNotFound: restore with non-existent id returns BackupNotFound") {
            runTest {
                backupTestFixture(withImages = false).use { fixture ->
                    val orchestrator = buildOrchestrator(fixture)
                    val result = orchestrator.restore(BackupId("does-not-exist"))

                    val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                    failure.error.shouldBeInstanceOf<BackupError.BackupNotFound>()
                }
            }
        }

        test("repeated restores are deterministic: pre-backup row present every time") {
            runTest {
                backupTestFixture(withImages = false).use { fixture ->
                    transaction(fixture.handle.database) {
                        exec("CREATE TABLE IF NOT EXISTS loop_test(v TEXT)")
                        exec("INSERT INTO loop_test(v) VALUES ('row-A')")
                    }
                    fixture.archive.create("loop1", includeImages = false, onEvent = {})
                    val orchestrator = buildOrchestrator(fixture)

                    repeat(10) { i ->
                        transaction(fixture.handle.database) {
                            exec("DELETE FROM loop_test")
                            exec("INSERT INTO loop_test(v) VALUES ('row-B-$i')")
                        }
                        val result = orchestrator.restore(BackupId("loop1"))
                        result.shouldBeInstanceOf<AppResult.Success<RestoreResult>>()
                        val v =
                            transaction(fixture.handle.database) {
                                exec("SELECT v FROM loop_test") { rs -> if (rs.next()) rs.getString(1) else null }
                            }
                        v shouldBe "row-A"
                    }
                }
            }
        }

        test("a connection used before restore does not leak stale data afterward") {
            runTest {
                backupTestFixture(withImages = false).use { fixture ->
                    transaction(fixture.handle.database) {
                        exec("CREATE TABLE IF NOT EXISTS stale_test(v TEXT)")
                        exec("INSERT INTO stale_test(v) VALUES ('row-A')")
                    }
                    fixture.archive.create("stale1", includeImages = false, onEvent = {})
                    transaction(fixture.handle.database) {
                        exec("DELETE FROM stale_test")
                        exec("INSERT INTO stale_test(v) VALUES ('row-B')")
                    }
                    // Touch the pool right before restore so a physical connection exists in it.
                    transaction(fixture.handle.database) { exec("SELECT 1") }

                    buildOrchestrator(fixture)
                        .restore(BackupId("stale1"))
                        .shouldBeInstanceOf<AppResult.Success<RestoreResult>>()

                    val v =
                        transaction(fixture.handle.database) {
                            exec("SELECT v FROM stale_test") { rs -> if (rs.next()) rs.getString(1) else null }
                        }
                    v shouldBe "row-A"
                }
            }
        }
    })

/** SHA-256 hex of raw [bytes] — used by the malicious-archive test. */
private fun sha256OfBytes(bytes: ByteArray): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    digest.update(bytes)
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/**
 * Copies entries from [source] into [dest], replacing the `manifest.json` entry with
 * the serialized [newManifest]. Used to forge an otherwise-intact archive that carries
 * a future schema version (for the IncompatibleSchema test).
 */
private fun rebuildArchiveWithManifest(
    source: java.nio.file.Path,
    dest: java.nio.file.Path,
    newManifest: BackupManifest,
) {
    ZipOutputStream(Files.newOutputStream(dest)).use { out ->
        java.util.zip.ZipFile(source.toFile()).use { zf ->
            copyEntriesReplacingManifest(out, zf, newManifest)
        }
    }
}

private fun copyEntriesReplacingManifest(
    out: ZipOutputStream,
    zf: java.util.zip.ZipFile,
    newManifest: BackupManifest,
) {
    zf.entries().asSequence().forEach { entry ->
        out.putNextEntry(ZipEntry(entry.name))
        if (entry.name == "manifest.json") {
            out.write(newManifest.toJson().toByteArray())
        } else {
            zf.getInputStream(entry).use { it.copyTo(out) }
        }
        out.closeEntry()
    }
}
