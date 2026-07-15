package com.calypsan.listenup.server.backup

import com.calypsan.listenup.api.dto.backup.BackupEvent
import com.calypsan.listenup.server.compression.zip.ZipMethod
import com.calypsan.listenup.server.compression.zip.ZipReader
import com.calypsan.listenup.server.compression.zip.ZipWriter
import com.calypsan.listenup.server.compression.zip.isSafeEntryName
import com.calypsan.listenup.server.db.DatabaseHandle
import com.calypsan.listenup.server.io.Sha256
import com.calypsan.listenup.server.io.createTempFileIn
import com.calypsan.listenup.server.io.fileIoDispatcher
import com.calypsan.listenup.server.io.hashSourceSha256
import com.calypsan.listenup.server.io.listRegularFilesRecursively
import com.calypsan.listenup.server.io.relativeTo
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.Sink
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlin.time.Clock

private const val CHUNK = 64 * 1024L

/**
 * Creates, opens, validates, and extracts `.listenup.zip` backup archives.
 *
 * An archive contains:
 * - `listenup.db` — a transactionally-consistent SQLite snapshot (via `VACUUM INTO`)
 * - `covers/<relpath>` — book cover images (when [create] is called with `includeImages = true`)
 * - `avatars/<relpath>` — user avatar images (when `includeImages = true`)
 * - `manifest.json` — written last; carries checksums, versions, and statistics
 *
 * ZIP-slip safety: [extractTo] rejects any entry whose name is not [isSafeEntryName].
 */
class BackupArchive(
    private val paths: BackupPaths,
    private val dbHandle: DatabaseHandle,
    private val serverId: suspend () -> String,
    private val appVersion: String,
    private val schemaVersion: suspend () -> String,
    private val counts: suspend () -> Pair<Int, Int>,
    private val clock: Clock = Clock.System,
) {
    /** Thrown when an archive is missing required entries or carries mismatched checksums. */
    class CorruptArchiveException(
        message: String,
        cause: Throwable? = null,
    ) : Exception(message, cause)

    /** Creates a new backup archive at `paths.archiveFor(id)` and returns its path. */
    suspend fun create(
        id: String,
        includeImages: Boolean,
        onEvent: (BackupEvent) -> Unit,
    ): Path =
        withContext(fileIoDispatcher) {
            paths.ensureDirs()
            val tmpDb = createTempFileIn(paths.tmpDir, "db-", ".db")
            try {
                onEvent(BackupEvent.DbSnapshotting)
                dbHandle.vacuumInto(tmpDb.toString())
                val dbHash = sha256Of(tmpDb)
                val dest = paths.archiveFor(id)
                writeArchive(tmpDb, dbHash, includeImages, onEvent, dest)
                dest
            } finally {
                SystemFileSystem.delete(tmpDb, mustExist = false)
            }
        }

    private suspend fun writeArchive(
        tmpDb: Path,
        dbHash: String,
        includeImages: Boolean,
        onEvent: (BackupEvent) -> Unit,
        dest: Path,
    ) {
        val tmpZip = createTempFileIn(paths.tmpDir, "zip-", ".zip")
        try {
            val checksums = linkedMapOf(DB_CHECKSUM_KEY to dbHash)

            ZipWriter(SystemFileSystem.sink(tmpZip)).use { zip ->
                zip.putEntry(DB_ENTRY, ZipMethod.DEFLATE).buffered().use { sink ->
                    SystemFileSystem.source(tmpDb).use { it.drainInto(sink) }
                }

                if (includeImages) {
                    checksums[COVERS_CHECKSUM_KEY] = addDirToZip(zip, paths.coversDir, COVERS_PREFIX, onEvent)
                    checksums[AVATARS_CHECKSUM_KEY] = addDirToZip(zip, paths.avatarsDir, AVATARS_PREFIX, onEvent)
                }

                onEvent(BackupEvent.Finalizing)
                zip.putEntry(MANIFEST_ENTRY, ZipMethod.DEFLATE).buffered().use { sink ->
                    sink.write(buildManifest(includeImages, checksums).toJson().encodeToByteArray())
                }
            }

            // tmpZip lives in tmpDir (inside backupsDir → same filesystem as dest), so this rename is always atomic.
            SystemFileSystem.atomicMove(tmpZip, dest)
        } catch (e: Exception) {
            SystemFileSystem.delete(tmpZip, mustExist = false)
            throw e
        }
    }

    private suspend fun buildManifest(
        includesImages: Boolean,
        checksums: Map<String, String>,
    ): BackupManifest {
        val (bookCount, userCount) = counts()
        return BackupManifest(
            formatVersion = BackupManifest.FORMAT_VERSION,
            serverId = serverId(),
            createdAt = clock.now().toEpochMilliseconds(),
            appVersion = appVersion,
            schemaVersion = schemaVersion(),
            includesImages = includesImages,
            checksums = checksums,
            bookCount = bookCount,
            userCount = userCount,
        )
    }

    /**
     * Opens an archive and returns its manifest without verifying checksums.
     * @throws CorruptArchiveException if `manifest.json` is absent or unparseable.
     */
    fun open(archive: Path): BackupManifest =
        ZipReader(archive).use { zr ->
            val entry =
                zr.entry(MANIFEST_ENTRY)
                    ?: throw CorruptArchiveException("$MANIFEST_ENTRY missing from $archive")
            val bytes = zr.openEntry(entry).buffered().use { it.readByteArray() }
            runCatching { BackupManifest.fromJson(bytes.decodeToString()) }
                .getOrElse { e ->
                    throw CorruptArchiveException("$MANIFEST_ENTRY unparseable in $archive: ${e.message}", e)
                }
        }

    /**
     * Opens the archive and verifies the `listenup.db` checksum against the manifest.
     * Image-dir checksums are verified in [extractTo] while streaming.
     * @throws CorruptArchiveException if the manifest is missing/unparseable or the db checksum mismatches.
     */
    fun validate(archive: Path): BackupManifest {
        val manifest = open(archive)
        verifyDbChecksum(archive, manifest)
        return manifest
    }

    private fun verifyDbChecksum(
        archive: Path,
        manifest: BackupManifest,
    ) {
        ZipReader(archive).use { zr ->
            val dbEntry = zr.entry(DB_ENTRY) ?: throw CorruptArchiveException("$DB_ENTRY missing from $archive")
            val actual = zr.openEntry(dbEntry).use { hashSourceSha256(it) }
            val expected =
                manifest.checksums[DB_CHECKSUM_KEY]
                    ?: throw CorruptArchiveException("manifest missing '$DB_CHECKSUM_KEY' checksum in $archive")
            if (actual != expected) {
                throw CorruptArchiveException(
                    "$DB_ENTRY checksum mismatch in $archive (expected=$expected, actual=$actual)",
                )
            }
        }
    }

    /**
     * Unzips `listenup.db` and (if present in the manifest) the image directories into [targetDir],
     * verifying checksums, and returns the manifest.
     *
     * ZIP-slip safety: every entry name is checked with [isSafeEntryName]; an unsafe entry throws.
     * Image-dir checksums are verified here while streaming (rolling digest per prefix, central-dir order).
     */
    fun extractTo(
        archive: Path,
        targetDir: Path,
    ): BackupManifest {
        val manifest = open(archive)
        val imageSums = extractEntries(archive, targetDir)
        verifyExtractedChecksums(archive, targetDir, manifest, imageSums)
        return manifest
    }

    private fun extractEntries(
        archive: Path,
        targetDir: Path,
    ): ImageDigests {
        val coversDigest = Sha256()
        val avatarsDigest = Sha256()
        try {
            ZipReader(archive).use { zr ->
                for (entry in zr.entries()) {
                    if (entry.name == MANIFEST_ENTRY) continue
                    if (!isSafeEntryName(entry.name)) {
                        throw CorruptArchiveException(
                            "ZIP-slip detected: entry '${entry.name}' would escape the target directory",
                        )
                    }
                    val target = Path(targetDir, entry.name)
                    target.parent?.let { SystemFileSystem.createDirectories(it) }
                    val digest =
                        when {
                            entry.name.startsWith(COVERS_PREFIX) -> coversDigest
                            entry.name.startsWith(AVATARS_PREFIX) -> avatarsDigest
                            else -> null
                        }
                    zr.openEntry(entry).buffered().use { src ->
                        SystemFileSystem.sink(target).buffered().use { out -> src.drainInto(out, digest) }
                    }
                }
            }
            return ImageDigests(covers = coversDigest.digestHex(), avatars = avatarsDigest.digestHex())
        } finally {
            coversDigest.close()
            avatarsDigest.close()
        }
    }

    private fun verifyExtractedChecksums(
        archive: Path,
        targetDir: Path,
        manifest: BackupManifest,
        imageSums: ImageDigests,
    ) {
        verifyExtractedDb(archive, targetDir, manifest)
        if (manifest.includesImages) {
            checkImageChecksum(COVERS_CHECKSUM_KEY, manifest, imageSums.covers)
            checkImageChecksum(AVATARS_CHECKSUM_KEY, manifest, imageSums.avatars)
        }
    }

    private fun verifyExtractedDb(
        archive: Path,
        targetDir: Path,
        manifest: BackupManifest,
    ) {
        val extractedDb = Path(targetDir, DB_ENTRY)
        if (!SystemFileSystem.exists(extractedDb)) throw CorruptArchiveException("$DB_ENTRY missing from $archive")
        val expected =
            manifest.checksums[DB_CHECKSUM_KEY]
                ?: throw CorruptArchiveException("manifest missing '$DB_CHECKSUM_KEY' checksum")
        val actual = sha256Of(extractedDb)
        if (actual != expected) {
            throw CorruptArchiveException(
                "$DB_ENTRY checksum mismatch after extract (expected=$expected, actual=$actual)",
            )
        }
    }

    private fun checkImageChecksum(
        key: String,
        manifest: BackupManifest,
        actual: String,
    ) {
        val expected = manifest.checksums[key] ?: return
        if (actual != expected) {
            throw CorruptArchiveException("$key checksum mismatch (expected=$expected, actual=$actual)")
        }
    }

    /**
     * Adds every regular file under [dir] as a zip entry under [prefix], in full-path sorted order,
     * accumulating a rolling SHA-256 across all file bytes (the per-dir checksum). Emits
     * [BackupEvent.ImagesCopying] per file. Absent directories produce the empty-input hash.
     */
    private fun addDirToZip(
        zip: ZipWriter,
        dir: Path,
        prefix: String,
        onEvent: (BackupEvent) -> Unit,
    ): String {
        val digest = Sha256()
        try {
            val files = listRegularFilesRecursively(dir)
            files.forEachIndexed { index, file ->
                // listRegularFilesRecursively only returns files beneath dir, so relativeTo is
                // non-null; the filename fallback covers the unreachable non-descendant case.
                val rel = file.relativeTo(dir) ?: file.name
                zip.putEntry("$prefix$rel", ZipMethod.DEFLATE).buffered().use { sink ->
                    SystemFileSystem.source(file).use { src -> src.drainInto(sink, digest) }
                }
                onEvent(BackupEvent.ImagesCopying(done = index + 1, total = files.size))
            }
            return digest.digestHex()
        } finally {
            digest.close()
        }
    }

    private data class ImageDigests(
        val covers: String,
        val avatars: String,
    )

    companion object {
        internal const val MANIFEST_ENTRY = "manifest.json"
        internal const val DB_ENTRY = "listenup.db"
        internal const val COVERS_PREFIX = "covers/"
        internal const val AVATARS_PREFIX = "avatars/"

        internal const val DB_CHECKSUM_KEY = "db"
        internal const val COVERS_CHECKSUM_KEY = "covers"
        internal const val AVATARS_CHECKSUM_KEY = "avatars"
    }
}

/**
 * Drains this source into [sink] in [CHUNK]-sized passes, feeding each chunk to [digest] (if any) before
 * writing it. The single canonical copy loop for db, image-write, and image-read paths.
 */
private fun RawSource.drainInto(
    sink: Sink,
    digest: Sha256? = null,
) {
    val chunk = Buffer()
    while (true) {
        val n = readAtMostTo(chunk, CHUNK)
        if (n == -1L) break
        val bytes = chunk.readByteArray()
        digest?.update(bytes)
        sink.write(bytes)
    }
}
