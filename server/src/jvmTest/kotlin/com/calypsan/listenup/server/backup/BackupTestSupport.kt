package com.calypsan.listenup.server.backup

import com.calypsan.listenup.server.db.DatabaseConfig
import com.calypsan.listenup.server.db.DatabaseFactory
import com.calypsan.listenup.server.db.DatabaseHandle
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.random.Random

/** Fixture wiring a real [DatabaseHandle] + [BackupPaths] + [BackupArchive] in a temp home dir. */
class BackupTestFixture(
    val homeDir: Path,
    val handle: DatabaseHandle,
    val paths: BackupPaths,
    val archive: BackupArchive,
) : AutoCloseable {
    override fun close() {
        handle.close()
    }
}

/**
 * Builds a [BackupTestFixture] in the given [homeDir] (or a fresh temp directory when null).
 *
 * Passing an existing [homeDir] allows route tests to share the same filesystem root as
 * the running server (configured via `listenup.home`) so the route's [BackupPaths] and the
 * fixture's [BackupPaths] both resolve archives under the same `backups/` subdirectory.
 *
 * If [withImages] is true, a dummy file is written into the `covers/` dir so
 * image-related code paths are exercised.
 */
fun backupTestFixture(
    homeDir: java.nio.file.Path? = null,
    withImages: Boolean = false,
): BackupTestFixture {
    val resolvedHomeDir = homeDir ?: Files.createTempDirectory("backup-test-")
    val dbFile = resolvedHomeDir.resolve("listenup.db")

    val handle =
        DatabaseFactory.init(
            DatabaseConfig(jdbcUrl = "jdbc:sqlite:$dbFile", maxPoolSize = 4),
        )

    // Seed a dummy row so the db isn't empty
    transaction(handle.database) {
        exec("CREATE TABLE IF NOT EXISTS dummy_seed(id INTEGER PRIMARY KEY, v TEXT)")
        exec("INSERT INTO dummy_seed(v) VALUES ('seed')")
    }

    val paths = BackupPaths(resolvedHomeDir)

    if (withImages) {
        val coversDir = paths.coversDir
        Files.createDirectories(coversDir)
        Files.write(coversDir.resolve("cover1.jpg"), byteArrayOf(1, 2, 3, 4, 5))
    }

    val archive =
        BackupArchive(
            paths = paths,
            dbHandle = handle,
            serverId = { "srv-test" },
            appVersion = "0.0.0-test",
            schemaVersion = { "1" },
            counts = { 1 to 1 },
        )

    return BackupTestFixture(resolvedHomeDir, handle, paths, archive)
}

/**
 * Rewrites [archive] so the `listenup.db` entry's bytes are mutated (one byte
 * flipped), causing its SHA-256 to differ from the manifest's stored checksum.
 */
fun tamperOneByteInsideZip(archive: Path) {
    val tmp = Files.createTempFile(archive.parent, "tamper-", ".zip")
    try {
        writeZipWithTamperedDb(archive, tmp)
        Files.move(tmp, archive, StandardCopyOption.REPLACE_EXISTING)
    } catch (e: Exception) {
        Files.deleteIfExists(tmp)
        throw e
    }
}

private fun writeZipWithTamperedDb(
    source: Path,
    dest: Path,
) {
    ZipOutputStream(Files.newOutputStream(dest)).use { out ->
        ZipFile(source.toFile()).use { zf ->
            zf.entries().asSequence().forEach { entry ->
                out.putNextEntry(ZipEntry(entry.name))
                val bytes = zf.getInputStream(entry).readBytes()
                out.write(flipDbBytes(entry.name, bytes))
                out.closeEntry()
            }
        }
    }
}

private fun flipDbBytes(
    entryName: String,
    bytes: ByteArray,
): ByteArray {
    if (entryName != "listenup.db" || bytes.isEmpty()) return bytes
    val idx = bytes.size / 2
    bytes[idx] = (bytes[idx].toInt() xor 0xFF).toByte()
    return bytes
}

/**
 * Builds a zip at [dest] containing one entry named [entryName] — intended to test
 * ZIP-slip: pass `"../escape.txt"` to produce an archive that [BackupArchive.extractTo]
 * must reject.
 */
fun buildMaliciousZip(
    dest: Path,
    entryName: String,
) {
    ZipOutputStream(Files.newOutputStream(dest)).use { zip ->
        zip.putNextEntry(ZipEntry(entryName))
        zip.write(Random.nextBytes(16))
        zip.closeEntry()
    }
}
