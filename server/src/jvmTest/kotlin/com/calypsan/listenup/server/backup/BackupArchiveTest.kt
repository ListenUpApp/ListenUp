package com.calypsan.listenup.server.backup

import com.calypsan.listenup.api.dto.backup.BackupEvent
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path as IoPath
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readByteArray

class BackupArchiveTest :
    FunSpec({

        test("create includeImages=false: archive exists, manifest has includesImages=false, DbSnapshotting emitted") {
            runTest {
                backupTestFixture(withImages = false).use { fixture ->
                    val events = mutableListOf<BackupEvent>()

                    val archive = fixture.archive.create(id = "b1", includeImages = false, onEvent = events::add)

                    SystemFileSystem.exists(archive) shouldBe true
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
                    val targetDir = IoPath(SystemTemporaryDirectory, "extract-${System.nanoTime()}")
                    SystemFileSystem.createDirectories(targetDir)

                    val manifest = fixture.archive.extractTo(archive, targetDir)

                    val extractedDb = IoPath(targetDir, "listenup.db")
                    SystemFileSystem.exists(extractedDb) shouldBe true
                    sha256Of(extractedDb) shouldBe manifest.checksums["db"]
                    manifest.includesImages shouldBe false
                    manifest.serverId shouldBe "srv-test"
                }
            }
        }

        test("extractTo throws CorruptArchiveException for a zip with a path-traversal entry (zip-slip)") {
            runTest {
                backupTestFixture(withImages = false).use { fixture ->
                    // A real archive (valid manifest) repacked with an extra ../escape.txt entry, so open()
                    // succeeds and extraction reaches the isSafeEntryName guard — the branch under test.
                    val real = fixture.archive.create(id = "slip-src", includeImages = false, onEvent = {})
                    val maliciousZip = IoPath(fixture.paths.tmpDir, "malicious.zip")
                    repackWithTraversalEntry(real, maliciousZip)

                    val targetDir = IoPath(SystemTemporaryDirectory, "zipslip-target-${System.nanoTime()}")
                    SystemFileSystem.createDirectories(targetDir)

                    val exception =
                        shouldThrow<BackupArchive.CorruptArchiveException> {
                            fixture.archive.extractTo(maliciousZip, targetDir)
                        }

                    // The ZIP-slip branch fired (not the "manifest missing" branch), and nothing escaped.
                    exception.message shouldContain "ZIP-slip"
                    SystemFileSystem.exists(IoPath(targetDir.parent!!, "escape.txt")) shouldBe false
                }
            }
        }

        test("create+extract round-trips multiple nested cover files and an avatar (full-path order invariant)") {
            runTest {
                backupTestFixture(withImages = false).use { fixture ->
                    // Seed several images across nested subdirectories so the rolling image digest spans
                    // more than one file and the full-path sort order is actually exercised.
                    val seeded =
                        linkedMapOf(
                            "covers/cover1.jpg" to byteArrayOf(1, 1, 1),
                            "covers/a/cover2.jpg" to byteArrayOf(2, 2, 2, 2),
                            "covers/b/cover3.jpg" to byteArrayOf(3, 3),
                            "avatars/avatar1.png" to byteArrayOf(9, 8, 7, 6, 5),
                        )
                    for ((rel, bytes) in seeded) {
                        val root = if (rel.startsWith("covers/")) fixture.paths.coversDir else fixture.paths.avatarsDir
                        val target = IoPath(root, rel.substringAfter('/'))
                        target.parent?.let { SystemFileSystem.createDirectories(it) }
                        SystemFileSystem.sink(target).buffered().use { it.write(bytes) }
                    }

                    val archive = fixture.archive.create(id = "multi", includeImages = true, onEvent = {})
                    val targetDir = IoPath(SystemTemporaryDirectory, "multi-extract-${System.nanoTime()}")
                    SystemFileSystem.createDirectories(targetDir)

                    // No CorruptArchiveException ⇒ the rolling image digest reproduced across the
                    // create-time full-path sort order during central-directory-order extraction.
                    fixture.archive.extractTo(archive, targetDir)

                    for ((rel, bytes) in seeded) {
                        val extracted = IoPath(targetDir, rel)
                        SystemFileSystem.exists(extracted) shouldBe true
                        val actual = SystemFileSystem.source(extracted).buffered().use { it.readByteArray() }
                        actual.toList() shouldBe bytes.toList()
                    }
                }
            }
        }
    })
