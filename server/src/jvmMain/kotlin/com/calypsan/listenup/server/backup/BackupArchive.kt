package com.calypsan.listenup.server.backup

import com.calypsan.listenup.api.dto.backup.BackupEvent
import com.calypsan.listenup.server.db.DatabaseHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Creates, opens, validates, and extracts `.listenup.zip` backup archives.
 *
 * An archive contains:
 * - `listenup.db` — a transactionally-consistent SQLite snapshot (via `VACUUM INTO`)
 * - `covers/<relpath>` — book cover images (when [create] is called with `includeImages = true`)
 * - `avatars/<relpath>` — user avatar images (when `includeImages = true`)
 * - `manifest.json` — written last; carries checksums, versions, and statistics
 *
 * ZIP-slip safety: [extractTo] rejects any entry whose resolved target would escape [targetDir].
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

    /**
     * Creates a new backup archive at `paths.archiveFor(id)` and returns its path.
     *
     * Sequence: ensure dirs → VACUUM INTO tmp db → zip db → (optional) zip image dirs →
     * write manifest.json → atomic-rename → return dest. Progress events are emitted via
     * [onEvent]. The temp db is always deleted in a `finally` block.
     */
    suspend fun create(
        id: String,
        includeImages: Boolean,
        onEvent: (BackupEvent) -> Unit,
    ): Path =
        withContext(Dispatchers.IO) {
            paths.ensureDirs()
            val tmpDb = Files.createTempFile(paths.tmpDir, "db-", ".db")
            try {
                onEvent(BackupEvent.DbSnapshotting)
                dbHandle.vacuumInto(tmpDb.toAbsolutePath().toString())
                val dbHash = sha256Of(tmpDb)
                val dest = paths.archiveFor(id)
                writeArchive(tmpDb, dbHash, includeImages, onEvent, dest)
                dest
            } finally {
                Files.deleteIfExists(tmpDb)
            }
        }

    private suspend fun writeArchive(
        tmpDb: Path,
        dbHash: String,
        includeImages: Boolean,
        onEvent: (BackupEvent) -> Unit,
        dest: Path,
    ) {
        val tmpZip = Files.createTempFile(paths.tmpDir, "zip-", ".zip")
        try {
            val checksums = linkedMapOf(DB_CHECKSUM_KEY to dbHash)

            ZipOutputStream(Files.newOutputStream(tmpZip)).use { zip ->
                zip.putNextEntry(ZipEntry(DB_ENTRY))
                Files.copy(tmpDb, zip)
                zip.closeEntry()

                if (includeImages) {
                    checksums[COVERS_CHECKSUM_KEY] = addDirToZip(zip, paths.coversDir, COVERS_PREFIX, onEvent)
                    checksums[AVATARS_CHECKSUM_KEY] = addDirToZip(zip, paths.avatarsDir, AVATARS_PREFIX, onEvent)
                }

                onEvent(BackupEvent.Finalizing)
                zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
                zip.write(buildManifest(includeImages, checksums).toJson().toByteArray())
                zip.closeEntry()
            }

            moveAtomic(tmpZip, dest)
        } catch (e: Exception) {
            Files.deleteIfExists(tmpZip)
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
     *
     * @throws CorruptArchiveException if `manifest.json` is absent or unparseable.
     */
    fun open(archive: Path): BackupManifest =
        ZipFile(archive.toFile()).use { zf ->
            val entry =
                zf.getEntry(MANIFEST_ENTRY)
                    ?: throw CorruptArchiveException("$MANIFEST_ENTRY missing from $archive")
            runCatching { BackupManifest.fromJson(zf.getInputStream(entry).readBytes().decodeToString()) }
                .getOrElse { e ->
                    throw CorruptArchiveException("$MANIFEST_ENTRY unparseable in $archive: ${e.message}", e)
                }
        }

    /**
     * Opens the archive and verifies the `listenup.db` checksum against the manifest.
     *
     * Image-dir checksums (covers / avatars) are verified in [extractTo] while streaming,
     * so full extraction is not required here. The db checksum is cheap to verify because
     * the db is always present and is the most critical entry to validate.
     *
     * @throws CorruptArchiveException if the manifest is missing/unparseable or the db
     *   checksum does not match.
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
        ZipFile(archive.toFile()).use { zf ->
            val dbEntry =
                zf.getEntry(DB_ENTRY) ?: throw CorruptArchiveException("$DB_ENTRY missing from $archive")
            val actual = sha256OfStream(zf.getInputStream(dbEntry))
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
     * Unzips `listenup.db` and (if present in the manifest) the image directories into
     * [targetDir], verifying checksums, and returns the manifest.
     *
     * ZIP-slip safety: every entry's resolved target is checked to stay within [targetDir].
     * Any entry that would write outside [targetDir] causes a [CorruptArchiveException].
     *
     * Image-dir checksums are verified here while streaming (rolling digest per prefix).
     *
     * @throws CorruptArchiveException on any security violation, missing entry, or checksum mismatch.
     */
    fun extractTo(
        archive: Path,
        targetDir: Path,
    ): BackupManifest {
        val manifest = open(archive)
        // Normalize targetDir without resolving symlinks so comparison works on macOS
        // where /var/... is a symlink to /private/var/...
        val normalizedTarget = targetDir.normalize().toAbsolutePath()
        val imageSums = extractEntries(archive, normalizedTarget)
        verifyExtractedChecksums(archive, targetDir, manifest, imageSums)
        return manifest
    }

    private fun extractEntries(
        archive: Path,
        normalizedTarget: Path,
    ): ImageDigests {
        val coversDigest = newSha256()
        val avatarsDigest = newSha256()

        ZipFile(archive.toFile()).use { zf ->
            zf
                .entries()
                .asSequence()
                .filter { it.name != MANIFEST_ENTRY }
                .forEach { entry ->
                    // ZIP-slip check: both sides are normalize().toAbsolutePath()
                    val entryTarget = normalizedTarget.resolve(entry.name).normalize()
                    if (!entryTarget.startsWith(normalizedTarget)) {
                        throw CorruptArchiveException(
                            "ZIP-slip detected: entry '${entry.name}' would escape the target directory",
                        )
                    }
                    Files.createDirectories(entryTarget.parent)
                    copyEntry(zf, entry.name, entryTarget, coversDigest, avatarsDigest)
                }
        }

        return ImageDigests(
            covers = coversDigest.digest().toHexString(),
            avatars = avatarsDigest.digest().toHexString(),
        )
    }

    private fun copyEntry(
        zf: ZipFile,
        entryName: String,
        target: Path,
        coversDigest: MessageDigest,
        avatarsDigest: MessageDigest,
    ) {
        val digest =
            when {
                entryName.startsWith(COVERS_PREFIX) -> coversDigest
                entryName.startsWith(AVATARS_PREFIX) -> avatarsDigest
                else -> null
            }
        zf.getInputStream(zf.getEntry(entryName)).use { input ->
            streamToFile(input, target, digest)
        }
    }

    private fun streamToFile(
        input: InputStream,
        target: Path,
        digest: MessageDigest?,
    ) {
        val buf = ByteArray(64 * 1024)
        Files.newOutputStream(target).use { out ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest?.update(buf, 0, n)
                out.write(buf, 0, n)
            }
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
        val extractedDb = targetDir.resolve(DB_ENTRY)
        val dbExpected = resolveDbExpected(archive, extractedDb, manifest)
        val dbActual = sha256Of(extractedDb)
        if (dbActual != dbExpected) {
            throw CorruptArchiveException(
                "$DB_ENTRY checksum mismatch after extract (expected=$dbExpected, actual=$dbActual)",
            )
        }
    }

    private fun resolveDbExpected(
        archive: Path,
        extractedDb: Path,
        manifest: BackupManifest,
    ): String {
        if (!Files.exists(extractedDb)) throw CorruptArchiveException("$DB_ENTRY missing from $archive")
        return manifest.checksums[DB_CHECKSUM_KEY]
            ?: throw CorruptArchiveException("manifest missing '$DB_CHECKSUM_KEY' checksum")
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
     * Walks [dir] and adds every file as a zip entry under [prefix], accumulating a rolling
     * SHA-256 across all file bytes (for a per-dir checksum). Emits [BackupEvent.ImagesCopying]
     * as each file is written. Gracefully skips absent directories (returns an empty-input hash).
     */
    private fun addDirToZip(
        zip: ZipOutputStream,
        dir: Path,
        prefix: String,
        onEvent: (BackupEvent) -> Unit,
    ): String {
        val digest = newSha256()

        if (!Files.exists(dir)) {
            return digest.digest().toHexString()
        }

        val allFiles =
            Files
                .walk(dir)
                .filter { Files.isRegularFile(it) }
                .sorted()
                .toList()
        val total = allFiles.size
        val buf = ByteArray(64 * 1024)

        allFiles.forEachIndexed { index, file ->
            val relativePath = dir.relativize(file).toString().replace('\\', '/')
            zip.putNextEntry(ZipEntry("$prefix$relativePath"))
            Files.newInputStream(file).use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                    zip.write(buf, 0, n)
                }
            }
            zip.closeEntry()
            onEvent(BackupEvent.ImagesCopying(done = index + 1, total = total))
        }

        return digest.digest().toHexString()
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

        /** Checksum map keys stored in the manifest (short names, not zip entry paths). */
        internal const val DB_CHECKSUM_KEY = "db"
        internal const val COVERS_CHECKSUM_KEY = "covers"
        internal const val AVATARS_CHECKSUM_KEY = "avatars"

        private const val SHA_256 = "SHA-256"

        private fun newSha256(): MessageDigest = MessageDigest.getInstance(SHA_256)

        /**
         * Moves [src] to [dest], attempting an atomic move first and falling back to
         * a non-atomic replace-existing move if the filesystem doesn't support it.
         */
        internal fun moveAtomic(
            src: Path,
            dest: Path,
        ) {
            try {
                Files.move(src, dest, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(src, dest, StandardCopyOption.REPLACE_EXISTING)
            }
        }

        /** SHA-256 hex of bytes read from [input] (streamed in 64 KiB chunks). */
        internal fun sha256OfStream(input: InputStream): String {
            val digest = newSha256()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
            return digest.digest().toHexString()
        }
    }
}
