package com.calypsan.listenup.server.backup

import com.calypsan.listenup.server.db.DatabaseConfig
import com.calypsan.listenup.server.db.DatabaseFactory
import com.calypsan.listenup.server.db.DatabaseHandle
import com.calypsan.listenup.server.db.openAdminConnection
import com.calypsan.listenup.server.testing.fileBackedTestDataSource
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.random.Random
import kotlinx.io.buffered
import kotlinx.io.files.Path as IoPath
import kotlinx.io.files.SystemFileSystem

/** The files [backupTestFixture] seeds when `withImages = true`, keyed by path relative to the home dir. */
val SEEDED_IMAGES: Map<String, ByteArray> =
    linkedMapOf(
        "covers/cover1.jpg" to byteArrayOf(1, 2, 3, 4, 5),
        "avatars/avatar1.png" to byteArrayOf(11, 12, 13),
        "contributors/author1.jpg" to byteArrayOf(21, 22, 23, 24),
        "series/series1.jpg" to byteArrayOf(31, 32),
    )

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
 * If [withImages] is true, one small file with distinct bytes is written into each managed image
 * directory ([SEEDED_IMAGES]) so image-related code paths are exercised.
 */
fun backupTestFixture(
    homeDir: java.nio.file.Path? = null,
    withImages: Boolean = false,
): BackupTestFixture {
    val resolvedHomeDir = homeDir ?: Files.createTempDirectory("backup-test-")
    val dbFile = resolvedHomeDir.resolve("listenup.db")

    val handle =
        DatabaseFactory.init(
            DatabaseConfig(jdbcUrl = "jdbc:sqlite:$dbFile"),
        )

    // Seed a dummy row so the db isn't empty (admin connection, autocommit).
    handle.execSql(
        "CREATE TABLE IF NOT EXISTS dummy_seed(id INTEGER PRIMARY KEY, v TEXT)",
        "INSERT INTO dummy_seed(v) VALUES ('seed')",
    )

    val paths = BackupPaths(IoPath(resolvedHomeDir.toString()))

    if (withImages) {
        SEEDED_IMAGES.forEach { (rel, bytes) ->
            val file = IoPath(resolvedHomeDir.toString(), rel)
            file.parent?.let { SystemFileSystem.createDirectories(it) }
            SystemFileSystem.sink(file).buffered().use { it.write(bytes) }
        }
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
 * Runs raw DDL/DML [statements] on the handle's db path via the admin connection.
 * Test seeding/mutation helper.
 */
fun DatabaseHandle.execSql(vararg statements: String) {
    openAdminConnection(dbPath, readOnly = false).use { conn ->
        statements.forEach { conn.execute(it) }
    }
}

/**
 * Runs a scalar-int [sql] query (first column of first row) on the handle's db; 0 if no row.
 * Uses a raw JDBC connection so aggregate column names like `count(*)` are accessed positionally.
 */
fun DatabaseHandle.queryScalarInt(sql: String): Int =
    fileBackedTestDataSource("jdbc:sqlite:$dbPath").connection.use { conn ->
        conn.createStatement().executeQuery(sql).use { rs ->
            if (rs.next()) rs.getInt(1) else 0
        }
    }

/**
 * Runs a scalar-string [sql] query (first column of first row) on the handle's db; null if no row.
 * Uses a raw JDBC connection for uniform first-column access.
 */
fun DatabaseHandle.queryScalarString(sql: String): String? =
    fileBackedTestDataSource("jdbc:sqlite:$dbPath").connection.use { conn ->
        conn.createStatement().executeQuery(sql).use { rs ->
            if (rs.next()) rs.getString(1) else null
        }
    }

/**
 * Rewrites [archive] so the [entryName] entry's bytes are mutated (one byte
 * flipped), causing its SHA-256 to differ from the manifest's stored checksum.
 */
fun tamperOneByteInsideZip(
    archive: IoPath,
    entryName: String = "listenup.db",
) {
    val archiveNio =
        java.nio.file.Path
            .of(archive.toString())
    val tmp =
        Files.createTempFile(
            java.nio.file.Path
                .of(archive.parent!!.toString()),
            "tamper-",
            ".zip",
        )
    try {
        writeZipWithTamperedEntry(archiveNio, tmp, entryName)
        Files.move(tmp, archiveNio, StandardCopyOption.REPLACE_EXISTING)
    } catch (e: Exception) {
        Files.deleteIfExists(tmp)
        throw e
    }
}

private fun writeZipWithTamperedEntry(
    source: Path,
    dest: Path,
    entryName: String,
) {
    ZipOutputStream(Files.newOutputStream(dest)).use { out ->
        ZipFile(source.toFile()).use { zf ->
            zf.entries().asSequence().forEach { entry ->
                out.putNextEntry(ZipEntry(entry.name))
                val bytes = zf.getInputStream(entry).readBytes()
                out.write(flipByteOf(entryName, entry.name, bytes))
                out.closeEntry()
            }
        }
    }
}

private fun flipByteOf(
    target: String,
    entryName: String,
    bytes: ByteArray,
): ByteArray {
    if (entryName != target || bytes.isEmpty()) return bytes
    val idx = bytes.size / 2
    bytes[idx] = (bytes[idx].toInt() xor 0xFF).toByte()
    return bytes
}

/**
 * Repacks the real [archive] into [dest], copying every entry verbatim and then ADDING a
 * `../escape.txt` traversal entry. Because the manifest is preserved, [BackupArchive.open] succeeds
 * and extraction reaches the [com.calypsan.listenup.server.compression.zip.isSafeEntryName] guard —
 * proving the ZIP-slip branch fires (not the "manifest missing" branch).
 */
fun repackWithTraversalEntry(
    archive: IoPath,
    dest: IoPath,
) {
    ZipOutputStream(
        Files.newOutputStream(
            java.nio.file.Path
                .of(dest.toString()),
        ),
    ).use { out ->
        ZipFile(
            java.nio.file.Path
                .of(archive.toString())
                .toFile(),
        ).use { zf ->
            zf.entries().asSequence().forEach { entry ->
                out.putNextEntry(ZipEntry(entry.name))
                out.write(zf.getInputStream(entry).readBytes())
                out.closeEntry()
            }
        }
        out.putNextEntry(ZipEntry("../escape.txt"))
        out.write(Random.nextBytes(16))
        out.closeEntry()
    }
}
