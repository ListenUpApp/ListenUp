package com.calypsan.listenup.server.backup

import com.calypsan.listenup.api.dto.backup.BackupEvent
import com.calypsan.listenup.api.dto.backup.RestoreResult
import com.calypsan.listenup.api.error.BackupError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.SyncControl
import com.calypsan.listenup.core.BackupId
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.ControlFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import java.util.Locale

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
            changeBus: ChangeBus = ChangeBus(),
        ): RestoreOrchestrator =
            RestoreOrchestrator(
                paths = fixture.paths,
                archive = fixture.archive,
                dbHandle = fixture.handle,
                maintenance = maintenance,
                eventBus = eventBus,
                changeBus = changeBus,
            )

        test("nothing can query the restored database until it has been migrated") {
            runTest {
                backupTestFixture(withImages = false).use { fixture ->
                    fixture.archive.create("order1", includeImages = false, onEvent = {})
                    val eventBus = MutableSharedFlow<BackupEvent>(extraBufferCapacity = 64)
                    val poolOpenWhileMigrating = mutableListOf<Boolean>()
                    // Unconfined + UNDISPATCHED: the collector resumes inline inside tryEmit, so the
                    // probe reads the pool at the instant Migrating is announced.
                    val probe =
                        launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                            eventBus.collect { event ->
                                if (event is BackupEvent.Migrating) {
                                    poolOpenWhileMigrating += fixture.handle.sqlDriver.isOpen
                                }
                            }
                        }

                    buildOrchestrator(fixture, eventBus = eventBus)
                        .restore(BackupId("order1"))
                        .shouldBeInstanceOf<AppResult.Success<RestoreResult>>()

                    poolOpenWhileMigrating shouldBe listOf(false)
                    fixture.handle.sqlDriver.isOpen shouldBe true
                    probe.cancel()
                }
            }
        }

        test("a restore leaves the revision counter at or above where it was") {
            runTest {
                backupTestFixture(withImages = false).use { fixture ->
                    fun counter() = fixture.handle.queryScalarInt("SELECT value FROM sync_meta WHERE key = 'revision_counter'")
                    fixture.handle.execSql("UPDATE sync_meta SET value = 10 WHERE key = 'revision_counter'")
                    fixture.archive.create("rev1", includeImages = false, onEvent = {})
                    // Writes after the backup: clients have now seen revisions up to 500.
                    fixture.handle.execSql("UPDATE sync_meta SET value = 500 WHERE key = 'revision_counter'")

                    buildOrchestrator(fixture)
                        .restore(BackupId("rev1"))
                        .shouldBeInstanceOf<AppResult.Success<RestoreResult>>()

                    counter() shouldBe 500
                }
            }
        }

        test("restoring a backup that is ahead of the live counter keeps the backup's value") {
            // The other half of max(): a counter that was reset must not drag the restored one down.
            runTest {
                backupTestFixture(withImages = false).use { fixture ->
                    fun counter() = fixture.handle.queryScalarInt("SELECT value FROM sync_meta WHERE key = 'revision_counter'")
                    fixture.handle.execSql("UPDATE sync_meta SET value = 500 WHERE key = 'revision_counter'")
                    fixture.archive.create("rev2", includeImages = false, onEvent = {})
                    fixture.handle.execSql("UPDATE sync_meta SET value = 10 WHERE key = 'revision_counter'")

                    buildOrchestrator(fixture)
                        .restore(BackupId("rev2"))
                        .shouldBeInstanceOf<AppResult.Success<RestoreResult>>()

                    counter() shouldBe 500
                }
            }
        }

        test("round-trip restore: db returns to pre-backup state after restore") {
            runTest {
                backupTestFixture(withImages = false).use { fixture ->
                    // Seed row A before backup
                    fixture.handle.execSql(
                        "CREATE TABLE IF NOT EXISTS restore_test(v TEXT)",
                        "INSERT INTO restore_test(v) VALUES ('row-A')",
                    )

                    fixture.archive.create("rt1", includeImages = false, onEvent = {})
                    val backupId = BackupId("rt1")

                    // Mutate to row B after backup
                    fixture.handle.execSql(
                        "DELETE FROM restore_test",
                        "INSERT INTO restore_test(v) VALUES ('row-B')",
                    )

                    val orchestrator = buildOrchestrator(fixture)
                    val result = orchestrator.restore(backupId)

                    result.shouldBeInstanceOf<AppResult.Success<RestoreResult>>()
                    result.data.restoredFrom shouldBe backupId
                    result.data.includedImages shouldBe false

                    // row A is back, row B is gone
                    fixture.handle.queryScalarString("SELECT v FROM restore_test") shouldBe "row-A"
                }
            }
        }

        test("successful restore broadcasts SyncControl.LibraryDataChanged to the firehose") {
            runTest {
                backupTestFixture(withImages = false).use { fixture ->
                    // Subscribe BEFORE the restore: the control channel has replay = 0, so a frame
                    // emitted before subscription would be missed. UNDISPATCHED runs the collector
                    // up to its first suspension (the subscribe) synchronously, guaranteeing it is
                    // registered on the bus before restore emits.
                    val changeBus = ChangeBus()
                    val received = mutableListOf<ControlFrame>()
                    val collector =
                        launch(start = CoroutineStart.UNDISPATCHED) {
                            changeBus.subscribeControl().toList(received)
                        }

                    fixture.handle.execSql(
                        "CREATE TABLE IF NOT EXISTS restore_test(v TEXT)",
                        "INSERT INTO restore_test(v) VALUES ('row-A')",
                    )
                    fixture.archive.create("bcast1", includeImages = false, onEvent = {})

                    val orchestrator = buildOrchestrator(fixture, changeBus = changeBus)
                    orchestrator
                        .restore(BackupId("bcast1"))
                        .shouldBeInstanceOf<AppResult.Success<RestoreResult>>()

                    testScheduler.advanceUntilIdle()
                    received shouldBe listOf(ControlFrame(SyncControl.LibraryDataChanged, ChangeBus.BROADCAST))
                    collector.cancel()
                }
            }
        }

        test("rollback on corrupt-but-checksum-valid snapshot: row B preserved, pool resumed, gate dropped") {
            runTest {
                backupTestFixture(withImages = false).use { fixture ->
                    // Seed row B in the live db — this must survive the failed restore
                    fixture.handle.execSql(
                        "CREATE TABLE IF NOT EXISTS rollback_test(v TEXT)",
                        "INSERT INTO rollback_test(v) VALUES ('row-B')",
                    )

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
                    ZipOutputStream(
                        Files.newOutputStream(
                            java.nio.file.Path
                                .of(archivePath.toString()),
                        ),
                    ).use { zip ->
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

                    // Connections must be resumed: a normal DB query must work, and row B is still there
                    // (rollback restored the original db).
                    fixture.handle.queryScalarString("SELECT v FROM rollback_test") shouldBe "row-B"
                }
            }
        }

        test("a write made just before the pool closes survives a rolled-back restore") {
            runTest {
                backupTestFixture(withImages = false).use { fixture ->
                    fixture.handle.execSql(
                        "CREATE TABLE IF NOT EXISTS late_test(v TEXT)",
                        "INSERT INTO late_test(v) VALUES ('row-B')",
                    )

                    val garbageBytes = "not-a-sqlite-database-at-all".toByteArray()
                    val manifest =
                        BackupManifest(
                            formatVersion = BackupManifest.FORMAT_VERSION,
                            serverId = "srv-test",
                            createdAt = System.currentTimeMillis(),
                            appVersion = "0.0.0-test",
                            schemaVersion = "0",
                            includesImages = false,
                            checksums = mapOf("db" to sha256OfBytes(garbageBytes)),
                            bookCount = 0,
                            userCount = 0,
                        )
                    fixture.paths.ensureDirs()
                    ZipOutputStream(
                        Files.newOutputStream(
                            java.nio.file.Path
                                .of(fixture.paths.archiveFor("late1").toString()),
                        ),
                    ).use { zip ->
                        zip.putNextEntry(ZipEntry("listenup.db"))
                        zip.write(garbageBytes)
                        zip.closeEntry()
                        zip.putNextEntry(ZipEntry("manifest.json"))
                        zip.write(manifest.toJson().toByteArray())
                        zip.closeEntry()
                    }

                    // Swapping is emitted just before the pool closes, so a row inserted here is a
                    // write that landed during the restore; the safety copy is taken after the close.
                    val eventBus = MutableSharedFlow<BackupEvent>(extraBufferCapacity = 64)
                    val probe =
                        launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                            eventBus.collect { event ->
                                if (event is BackupEvent.Swapping) {
                                    fixture.handle.execSql("INSERT INTO late_test(v) VALUES ('late')")
                                }
                            }
                        }

                    val result = buildOrchestrator(fixture, eventBus = eventBus).restore(BackupId("late1"))

                    result
                        .shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<BackupError.RestoreFailed>()
                        .rolledBack shouldBe true
                    fixture.handle.queryScalarInt("SELECT COUNT(*) FROM late_test WHERE v IN ('row-B', 'late')") shouldBe 2
                    probe.cancel()
                }
            }
        }

        test("a corrupt archive fails before anything is copied or closed") {
            runTest {
                backupTestFixture(withImages = false).use { fixture ->
                    fixture.handle.execSql(
                        "CREATE TABLE IF NOT EXISTS untouched_test(v TEXT)",
                        "INSERT INTO untouched_test(v) VALUES ('row-B')",
                    )
                    fixture.paths.ensureDirs()
                    writeBytes(fixture.paths.archiveFor("junk1"), "this is not a zip".toByteArray())

                    // A file too small to be a zip escapes validate() as a MalformedZipException rather than
                    // a Failure today; either way the restore must stop before touching anything.
                    val outcome =
                        try {
                            buildOrchestrator(fixture).restore(BackupId("junk1"))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                    outcome?.shouldBeInstanceOf<AppResult.Failure>()

                    fixture.handle.sqlDriver.isOpen shouldBe true
                    SystemFileSystem.exists(Path(fixture.paths.rollbackDir, "listenup.db")) shouldBe false
                    fixture.handle.queryScalarString("SELECT v FROM untouched_test") shouldBe "row-B"
                }
            }
        }

        test("the swap window is closed to writers: a write through the driver while migrating is refused") {
            runTest {
                backupTestFixture(withImages = false).use { fixture ->
                    fixture.handle.execSql(
                        "CREATE TABLE IF NOT EXISTS window_test(v TEXT)",
                        "INSERT INTO window_test(v) VALUES ('row-A')",
                    )
                    fixture.archive.create("window1", includeImages = false, onEvent = {})
                    fixture.handle.execSql("INSERT INTO window_test(v) VALUES ('row-B')")

                    val eventBus = MutableSharedFlow<BackupEvent>(extraBufferCapacity = 64)
                    val refused = mutableListOf<Boolean>()
                    val probe =
                        launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                            eventBus.collect { event ->
                                if (event is BackupEvent.Migrating) {
                                    refused +=
                                        runCatching {
                                            fixture.handle.sqlDriver.execute(
                                                identifier = null,
                                                sql = "INSERT INTO window_test(v) VALUES ('sneaky')",
                                                parameters = 0,
                                            )
                                        }.isFailure
                                }
                            }
                        }

                    buildOrchestrator(fixture, eventBus = eventBus)
                        .restore(BackupId("window1"))
                        .shouldBeInstanceOf<AppResult.Success<RestoreResult>>()

                    refused shouldBe listOf(true)
                    fixture.handle.queryScalarString("SELECT v FROM window_test") shouldBe "row-A"
                    fixture.handle.queryScalarInt("SELECT COUNT(*) FROM window_test") shouldBe 1
                    probe.cancel()
                }
            }
        }

        test("rolled-back restore does not broadcast: the DB is back to its pre-restore state") {
            runTest {
                backupTestFixture(withImages = false).use { fixture ->
                    val changeBus = ChangeBus()
                    val received = mutableListOf<ControlFrame>()
                    val collector =
                        launch(start = CoroutineStart.UNDISPATCHED) {
                            changeBus.subscribeControl().toList(received)
                        }

                    fixture.handle.execSql(
                        "CREATE TABLE IF NOT EXISTS no_bcast_test(v TEXT)",
                        "INSERT INTO no_bcast_test(v) VALUES ('row-B')",
                    )

                    // Malicious archive: garbage db bytes with a matching checksum (validate passes)
                    // and schemaVersion "0" (not rejected as incompatible), so the swap fails and the
                    // orchestrator rolls back — the rollback path must NOT broadcast.
                    val garbageBytes = "not-a-sqlite-database-at-all".toByteArray()
                    val manifest =
                        BackupManifest(
                            formatVersion = BackupManifest.FORMAT_VERSION,
                            serverId = "srv-test",
                            createdAt = System.currentTimeMillis(),
                            appVersion = "0.0.0-test",
                            schemaVersion = "0",
                            includesImages = false,
                            checksums = mapOf("db" to sha256OfBytes(garbageBytes)),
                            bookCount = 0,
                            userCount = 0,
                        )
                    fixture.paths.ensureDirs()
                    val archivePath = fixture.paths.archiveFor("no-bcast1")
                    ZipOutputStream(
                        Files.newOutputStream(
                            java.nio.file.Path
                                .of(archivePath.toString()),
                        ),
                    ).use { zip ->
                        zip.putNextEntry(ZipEntry("listenup.db"))
                        zip.write(garbageBytes)
                        zip.closeEntry()
                        zip.putNextEntry(ZipEntry("manifest.json"))
                        zip.write(manifest.toJson().toByteArray())
                        zip.closeEntry()
                    }

                    val orchestrator = buildOrchestrator(fixture, changeBus = changeBus)
                    val result = orchestrator.restore(BackupId("no-bcast1"))

                    val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                    failure.error.shouldBeInstanceOf<BackupError.RestoreFailed>()

                    testScheduler.advanceUntilIdle()
                    received.shouldBeEmpty()
                    collector.cancel()
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
                    fixture.handle.execSql("SELECT 1")
                }
            }
        }

        test("metadata-only restore: sentinel files in the managed image dirs survive because includeImages=false") {
            runTest {
                backupTestFixture(withImages = false).use { fixture ->
                    // Write a sentinel file into coversDir before creating the backup
                    SystemFileSystem.createDirectories(fixture.paths.coversDir)
                    val sentinel = Path(fixture.paths.coversDir, "sentinel.jpg")
                    SystemFileSystem.sink(sentinel).buffered().use { it.write(byteArrayOf(0x01, 0x02, 0x03)) }

                    val otherSentinels =
                        listOf("contributors", "series").map { dir ->
                            Path(fixture.paths.imageDir(dir), "sentinel.jpg").also { writeBytes(it, byteArrayOf(0x04)) }
                        }

                    // Create a metadata-only backup (no images)
                    fixture.archive.create("meta1", includeImages = false, onEvent = {})

                    val orchestrator = buildOrchestrator(fixture)
                    val result = orchestrator.restore(BackupId("meta1"))

                    val success = result.shouldBeInstanceOf<AppResult.Success<RestoreResult>>()
                    success.data.includedImages shouldBe false

                    // Sentinel file must still exist — images were not swapped
                    SystemFileSystem.exists(sentinel) shouldBe true
                    otherSentinels.forEach { SystemFileSystem.exists(it) shouldBe true }
                }
            }
        }

        test("restore with images brings back contributor photos and series covers") {
            runTest {
                backupTestFixture(withImages = true).use { fixture ->
                    fixture.archive.create("img1", includeImages = true, onEvent = {})

                    val photo = Path(fixture.paths.imageDir("contributors"), "author1.jpg")
                    val seriesCover = Path(fixture.paths.imageDir("series"), "series1.jpg")
                    deleteTree(fixture.paths.imageDir("contributors"))
                    writeBytes(seriesCover, byteArrayOf(99, 99, 99))

                    buildOrchestrator(fixture)
                        .restore(BackupId("img1"))
                        .shouldBeInstanceOf<AppResult.Success<RestoreResult>>()

                    readBytes(photo).toList() shouldBe SEEDED_IMAGES.getValue("contributors/author1.jpg").toList()
                    readBytes(seriesCover).toList() shouldBe SEEDED_IMAGES.getValue("series/series1.jpg").toList()
                }
            }
        }

        test("rollback leaves every managed image directory as it was") {
            runTest {
                backupTestFixture(withImages = true).use { fixture ->
                    // A forged archive that includes images: garbage db (checksum-valid, so validate passes)
                    // plus replacement bytes for every managed directory. The swap replaces the live
                    // directories first and the migrate then fails, so only a full rollback brings them back.
                    val garbageBytes = "not-a-sqlite-database-at-all".toByteArray()
                    val manifest =
                        BackupManifest(
                            formatVersion = BackupManifest.FORMAT_VERSION,
                            serverId = "srv-test",
                            createdAt = System.currentTimeMillis(),
                            appVersion = "0.0.0-test",
                            schemaVersion = "0",
                            includesImages = true,
                            checksums = mapOf("db" to sha256OfBytes(garbageBytes)),
                            bookCount = 0,
                            userCount = 0,
                        )
                    fixture.paths.ensureDirs()
                    ZipOutputStream(Files.newOutputStream(fixture.paths.archiveFor("mal-img").nio())).use { zip ->
                        zip.putNextEntry(ZipEntry("listenup.db"))
                        zip.write(garbageBytes)
                        zip.closeEntry()
                        SEEDED_IMAGES.keys.forEach { rel ->
                            zip.putNextEntry(ZipEntry(rel))
                            zip.write(byteArrayOf(-1, -1, -1))
                            zip.closeEntry()
                        }
                        zip.putNextEntry(ZipEntry("manifest.json"))
                        zip.write(manifest.toJson().toByteArray())
                        zip.closeEntry()
                    }

                    val result = buildOrchestrator(fixture).restore(BackupId("mal-img"))

                    val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                    failure.error.shouldBeInstanceOf<BackupError.RestoreFailed>().rolledBack shouldBe true
                    SEEDED_IMAGES.forEach { (rel, bytes) ->
                        readBytes(Path(fixture.homeDir.toString(), rel)).toList() shouldBe bytes.toList()
                    }
                }
            }
        }

        test("an archive written without contributor and series directories leaves the live ones alone") {
            runTest {
                backupTestFixture(withImages = true).use { fixture ->
                    val full = fixture.archive.create("full", includeImages = true, onEvent = {})
                    val fullManifest = fixture.archive.open(full)
                    val oldShape =
                        fullManifest.copy(checksums = fullManifest.checksums.filterKeys { it in setOf("db", "covers", "avatars") })
                    rebuildArchiveWithout(
                        source = full,
                        dest = fixture.paths.archiveFor("old-shape"),
                        newManifest = oldShape,
                        dropPrefixes = listOf("contributors/", "series/"),
                    )

                    val photo = Path(fixture.paths.imageDir("contributors"), "sentinel.jpg")
                    val seriesCover = Path(fixture.paths.imageDir("series"), "sentinel.jpg")
                    writeBytes(photo, byteArrayOf(7))
                    writeBytes(seriesCover, byteArrayOf(8))

                    buildOrchestrator(fixture)
                        .restore(BackupId("old-shape"))
                        .shouldBeInstanceOf<AppResult.Success<RestoreResult>>()

                    SystemFileSystem.exists(photo) shouldBe true
                    SystemFileSystem.exists(seriesCover) shouldBe true
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
                    fixture.handle.execSql(
                        "CREATE TABLE IF NOT EXISTS loop_test(v TEXT)",
                        "INSERT INTO loop_test(v) VALUES ('row-A')",
                    )
                    fixture.archive.create("loop1", includeImages = false, onEvent = {})
                    val orchestrator = buildOrchestrator(fixture)

                    repeat(10) { i ->
                        fixture.handle.execSql(
                            "DELETE FROM loop_test",
                            "INSERT INTO loop_test(v) VALUES ('row-B-$i')",
                        )
                        val result = orchestrator.restore(BackupId("loop1"))
                        result.shouldBeInstanceOf<AppResult.Success<RestoreResult>>()
                        fixture.handle.queryScalarString("SELECT v FROM loop_test") shouldBe "row-A"
                    }
                }
            }
        }

        test("a connection used before restore does not leak stale data afterward") {
            runTest {
                backupTestFixture(withImages = false).use { fixture ->
                    fixture.handle.execSql(
                        "CREATE TABLE IF NOT EXISTS stale_test(v TEXT)",
                        "INSERT INTO stale_test(v) VALUES ('row-A')",
                    )
                    fixture.archive.create("stale1", includeImages = false, onEvent = {})
                    fixture.handle.execSql(
                        "DELETE FROM stale_test",
                        "INSERT INTO stale_test(v) VALUES ('row-B')",
                    )
                    // Touch the db right before restore so a physical connection has been opened.
                    fixture.handle.execSql("SELECT 1")

                    buildOrchestrator(fixture)
                        .restore(BackupId("stale1"))
                        .shouldBeInstanceOf<AppResult.Success<RestoreResult>>()

                    fixture.handle.queryScalarString("SELECT v FROM stale_test") shouldBe "row-A"
                }
            }
        }
    })

/** SHA-256 hex of raw [bytes] — used by the malicious-archive test. */
private fun sha256OfBytes(bytes: ByteArray): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    digest.update(bytes)
    return digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
}

/**
 * Copies entries from [source] into [dest], replacing the `manifest.json` entry with
 * the serialized [newManifest]. Used to forge an otherwise-intact archive that carries
 * a future schema version (for the IncompatibleSchema test).
 */
private fun rebuildArchiveWithManifest(
    source: Path,
    dest: Path,
    newManifest: BackupManifest,
) {
    ZipOutputStream(
        Files.newOutputStream(
            java.nio.file.Path
                .of(dest.toString()),
        ),
    ).use { out ->
        java.util.zip
            .ZipFile(
                java.nio.file.Path
                    .of(source.toString())
                    .toFile(),
            ).use { zf ->
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

private fun Path.nio(): java.nio.file.Path =
    java.nio.file.Path
        .of(toString())

private fun readBytes(path: Path): ByteArray = Files.readAllBytes(path.nio())

private fun writeBytes(
    path: Path,
    bytes: ByteArray,
) {
    Files.createDirectories(path.nio().parent)
    Files.write(path.nio(), bytes)
}

private fun deleteTree(dir: Path) {
    dir.nio().toFile().deleteRecursively()
}

/**
 * Copies [source] into [dest] minus every entry under one of [dropPrefixes], replacing the manifest
 * with [newManifest] — the shape of an archive written before those directories were archived.
 */
private fun rebuildArchiveWithout(
    source: Path,
    dest: Path,
    newManifest: BackupManifest,
    dropPrefixes: List<String>,
) {
    ZipOutputStream(Files.newOutputStream(dest.nio())).use { out ->
        java.util.zip.ZipFile(source.nio().toFile()).use { zf ->
            val kept = zf.entries().asSequence().filter { e -> dropPrefixes.none { e.name.startsWith(it) } }
            for (entry in kept) copyEntry(out, zf, entry, newManifest)
        }
    }
}

private fun copyEntry(
    out: ZipOutputStream,
    zf: java.util.zip.ZipFile,
    entry: ZipEntry,
    newManifest: BackupManifest,
) {
    out.putNextEntry(ZipEntry(entry.name))
    if (entry.name == "manifest.json") {
        out.write(newManifest.toJson().toByteArray())
    } else {
        zf.getInputStream(entry).use { it.copyTo(out) }
    }
    out.closeEntry()
}
