package com.calypsan.listenup.server.backup

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path as IoPath
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@OptIn(ExperimentalStdlibApi::class)
class BackupArchiveCompatTest :
    FunSpec({

        test("old java.util.zip archive validates and extracts under the new reader") {
            runTest {
                backupTestFixture(withImages = true).use { fixture ->
                    // Produce a valid archive with the NEW writer, then re-pack its entries (same names,
                    // same bytes, same order) with java.util.zip to simulate a foreign producer.
                    val produced = fixture.archive.create(id = "compat-src", includeImages = true, onEvent = {})
                    val foreign = IoPath(fixture.paths.tmpDir.toString(), "foreign.listenup.zip")
                    repackWithJavaUtilZip(produced, foreign)

                    val manifest = fixture.archive.validate(foreign)
                    manifest.includesImages shouldBe true

                    val target = IoPath(SystemTemporaryDirectory.toString(), "compat-extract")
                    deleteRecursivelyIfExists(target)
                    SystemFileSystem.createDirectories(target)
                    val m2 = fixture.archive.extractTo(foreign, target)
                    m2.checksums["db"] shouldBe manifest.checksums["db"]
                    // Independent recomputation against the extracted bytes (not manifest-vs-manifest),
                    // plus proof the cover entry actually landed on disk.
                    sha256Of(IoPath(target.toString(), "listenup.db")) shouldBe manifest.checksums["db"]
                    SystemFileSystem.exists(IoPath(target.toString(), "covers/cover1.jpg")) shouldBe true
                }
            }
        }

        test("new archive is readable by java.util.zip entry-by-entry") {
            runTest {
                backupTestFixture(withImages = true).use { fixture ->
                    val produced = fixture.archive.create(id = "compat-dst", includeImages = true, onEvent = {})
                    ZipFile(File(produced.toString())).use { jdk ->
                        jdk.getEntry("manifest.json") shouldNotBe null
                        jdk.getEntry("listenup.db") shouldNotBe null
                        val manifest =
                            BackupManifest.fromJson(
                                jdk.getInputStream(jdk.getEntry("manifest.json")).readBytes().decodeToString(),
                            )
                        val dbBytes = jdk.getInputStream(jdk.getEntry("listenup.db")).readBytes()
                        MessageDigest.getInstance("SHA-256").digest(dbBytes).toHexString() shouldBe manifest.checksums["db"]
                    }
                }
            }
        }
    })

private fun deleteRecursivelyIfExists(p: kotlinx.io.files.Path) {
    if (!SystemFileSystem.exists(p)) return
    val meta = SystemFileSystem.metadataOrNull(p)
    if (meta?.isDirectory == true) for (c in SystemFileSystem.list(p)) deleteRecursivelyIfExists(c)
    SystemFileSystem.delete(p, mustExist = false)
}

private fun repackWithJavaUtilZip(
    src: kotlinx.io.files.Path,
    dst: kotlinx.io.files.Path,
) {
    ZipOutputStream(
        Files.newOutputStream(
            java.nio.file.Path
                .of(dst.toString()),
        ),
    ).use { out ->
        ZipFile(File(src.toString())).use { zf ->
            zf.entries().asSequence().forEach { e ->
                out.putNextEntry(ZipEntry(e.name))
                out.write(zf.getInputStream(e).readBytes())
                out.closeEntry()
            }
        }
    }
}
