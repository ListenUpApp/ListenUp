package com.calypsan.listenup.server.backup

import com.calypsan.listenup.api.dto.backup.BackupEvent
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.io.path.exists

class BackupArchiveTest :
    FunSpec({

        test("create includeImages=false: archive exists, manifest has includesImages=false, DbSnapshotting emitted") {
            runTest {
                backupTestFixture(withImages = false).use { fixture ->
                    val events = mutableListOf<BackupEvent>()

                    val archive = fixture.archive.create(id = "b1", includeImages = false, onEvent = events::add)

                    archive.exists() shouldBe true
                    val manifest = fixture.archive.validate(archive)
                    manifest.includesImages shouldBe false
                    manifest.serverId shouldBe "srv-test"
                    manifest.bookCount shouldBe 1
                    events.any { it is BackupEvent.DbSnapshotting } shouldBe true
                }
            }
        }

        test("validate throws CorruptArchiveException when listenup.db checksum mismatches") {
            runTest {
                backupTestFixture(withImages = true).use { fixture ->
                    val archive = fixture.archive.create(id = "b2", includeImages = true, onEvent = {})

                    tamperOneByteInsideZip(archive)

                    shouldThrow<BackupArchive.CorruptArchiveException> {
                        fixture.archive.validate(archive)
                    }
                }
            }
        }

        test("extractTo round-trips: extracted listenup.db exists and sha256 matches manifest db checksum") {
            runTest {
                backupTestFixture(withImages = false).use { fixture ->
                    val archive = fixture.archive.create(id = "b3", includeImages = false, onEvent = {})
                    val targetDir = Files.createTempDirectory("extract-")

                    val manifest = fixture.archive.extractTo(archive, targetDir)

                    val extractedDb = targetDir.resolve("listenup.db")
                    extractedDb.exists() shouldBe true
                    sha256Of(extractedDb) shouldBe manifest.checksums["db"]
                    manifest.includesImages shouldBe false
                    manifest.serverId shouldBe "srv-test"
                }
            }
        }

        test("extractTo throws CorruptArchiveException for a zip with a path-traversal entry (zip-slip)") {
            runTest {
                backupTestFixture(withImages = false).use { fixture ->
                    val maliciousZip = fixture.paths.tmpDir.resolve("malicious.zip")
                    Files.createDirectories(fixture.paths.tmpDir)
                    buildMaliciousZip(maliciousZip, "../escape.txt")

                    val targetDir = Files.createTempDirectory("zipslip-target-")

                    // extractTo must reject the traversal entry
                    val exception =
                        shouldThrow<BackupArchive.CorruptArchiveException> {
                            fixture.archive.extractTo(maliciousZip, targetDir)
                        }

                    // Verify no file was written outside targetDir
                    exception.message shouldNotBe null
                    targetDir.parent.resolve("escape.txt").exists() shouldBe false
                }
            }
        }
    })
