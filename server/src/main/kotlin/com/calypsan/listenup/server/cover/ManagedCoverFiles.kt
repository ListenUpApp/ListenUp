package com.calypsan.listenup.server.cover

import com.calypsan.listenup.api.sync.CoverSource
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.db.BookAudioFileTable
import com.calypsan.listenup.server.db.BookTable
import com.calypsan.listenup.server.db.LibraryFolderTable
import com.calypsan.listenup.server.services.PendingCover
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

private val log = KotlinLogging.logger {}

/**
 * The result of storing a scanned cover to the managed cover store.
 *
 * [relPath] is the path relative to [CoverImageStore]'s base directory
 * (e.g. `"covers/<bookId>.jpg"`). [hash] is the SHA-256 hex digest of the stored bytes.
 * [source] is the sync-layer provenance tag.
 */
data class StoredCoverInfo(
    val relPath: String,
    val hash: String,
    val source: CoverSource,
)

/**
 * Self-contained cover file and path helpers extracted from `BookRepository`.
 *
 * Holds the [CoverImageStore] and [homeDir] that the managed-cover surface needs, plus
 * the [db] handle required by [coverInfo] to read the book row before resolving the
 * filesystem path. This collaborator is constructed by `BookRepository` from its own
 * constructor parameters and owns all I/O-side cover logic that does NOT touch the
 * syncable-repository template-method seam ([nextRevision], [ChangeBus], [readPayload]).
 *
 * All suspension points are safe to call from outside an Exposed transaction (file I/O
 * runs on [Dispatchers.IO]; [coverInfo] opens its own short read transaction internally).
 *
 * @param coverImageStore the cover-scoped [ImageStore] wrapper; null when the managed
 *   store is not configured (no library path set).
 * @param homeDir `$LISTENUP_HOME` as a [Path]; null when the library is not configured.
 * @param db the Exposed [Database] handle used by [coverInfo] to read book rows.
 */
class ManagedCoverFiles(
    private val coverImageStore: CoverImageStore?,
    private val homeDir: Path?,
    private val db: Database,
) {
    /**
     * Stores [pending] to the managed cover store (if configured + non-null) and returns a
     * [StoredCoverInfo] carrying the relative path, sha256 hash, and provenance. Returns null
     * when [coverImageStore] is not configured, [pending] is null, or storage fails (logged,
     * not propagated — cover is best-effort at scan time).
     *
     * Must be called OUTSIDE any DB transaction — file I/O must not run inside an Exposed
     * `suspendTransaction`.
     */
    suspend fun storeCoverIfPresent(
        bookId: BookId,
        pending: PendingCover?,
    ): StoredCoverInfo? {
        if (coverImageStore == null || pending == null) return null
        return try {
            val stored = coverImageStore.store.store(bookId.value, pending.bytes, pending.mime)
            val relPath = "covers/${stored.path.fileName}"
            StoredCoverInfo(relPath = relPath, hash = stored.sha256, source = pending.source)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "Cover store failed for book ${bookId.value} — skipping" }
            null
        }
    }

    /**
     * Resolves where [id]'s cover image lives, as an absolute filesystem path,
     * for the cover-serving route. Returns null when the book is absent **or**
     * has no cover (a null `coverSource`) — both are a plain 404 to the caller,
     * not an error.
     *
     * **Managed file wins when `cover_path` is set.** When [homeDir] is configured
     * and the book row has a non-null `cover_path`, [resolveManagedCover] is tried
     * first and returned when the file exists — regardless of `cover_source`. This
     * covers FILESYSTEM and EMBEDDED books whose cover was persisted to the managed
     * store at scan time, as well as UPLOADED and ENRICHED covers.
     *
     * **Source-based resolution is the fallback.** Applied when `cover_path` is null
     * (book not yet re-scanned after the managed store was introduced) or the managed
     * file is missing (deleted from disk — treat as 404, not 500):
     *
     *  - [CoverSource.FILESYSTEM] → the book directory is scanned for a cover
     *    image, mirroring the scanner's `Analyzer.resolveCover` precedence:
     *    a `cover.*` file (case-insensitive) wins, else the first image file
     *    by name. A [CoverInfo.Filesystem] is returned only when such a file
     *    actually exists on disk; otherwise null.
     *  - [CoverSource.EMBEDDED] → the book's primary audio file (lowest
     *    `ordinal`) resolves to `<root>/<rootRelPath>/<filename>`, returned as
     *    [CoverInfo.Embedded] for serve-time artwork extraction.
     *  - [CoverSource.UPLOADED] / [CoverSource.ENRICHED] → the persisted
     *    `cover_path` is resolved sandboxed under [homeDir], returned as [CoverInfo.Managed].
     *
     * Opens its own short read transaction — the route calls it outside any
     * substrate orchestration.
     */
    suspend fun coverInfo(id: BookId): CoverInfo? {
        val resolved =
            suspendTransaction(db) {
                val bookRow =
                    BookTable
                        .selectAll()
                        .where { BookTable.id eq id.value }
                        .firstOrNull() ?: return@suspendTransaction null
                val source = bookRow[BookTable.coverSource] ?: return@suspendTransaction null
                val rootRelPath = bookRow[BookTable.rootRelPath]
                val hash = bookRow[BookTable.coverHash]
                // Resolve the folder root path via the book's folder_id column.
                // TODO: surface a typed error (LIB-C) if the folder row is missing.
                val folderRoot =
                    LibraryFolderTable
                        .selectAll()
                        .where { LibraryFolderTable.id eq bookRow[BookTable.folderId] }
                        .firstOrNull()
                        ?.get(LibraryFolderTable.rootPath)
                        ?: return@suspendTransaction null
                val primaryFilename =
                    BookAudioFileTable
                        .selectAll()
                        .where { BookAudioFileTable.bookId eq id.value }
                        .orderBy(BookAudioFileTable.ordinal)
                        .firstOrNull()
                        ?.get(BookAudioFileTable.filename)
                val coverPath = bookRow[BookTable.coverPath]
                ResolvedCover(source, folderRoot, rootRelPath, primaryFilename, hash, coverPath)
            } ?: return null

        val bookDir = Path.of(resolved.libraryRoot, resolved.rootRelPath)
        val source =
            CoverSource.entries.firstOrNull { it.name.equals(resolved.source, ignoreCase = true) }
                ?: return null

        // Managed file wins when cover_path is set — this covers FILESYSTEM and EMBEDDED books
        // whose cover was persisted to the managed store at scan time (Task 5), as well as
        // UPLOADED and ENRICHED covers. Source-based fallback applies when cover_path is null
        // (book not yet re-scanned after the managed store was introduced) or the file is
        // missing (deleted from disk — treat as 404, not 500).
        if (homeDir != null && resolved.coverPath != null) {
            val managed = resolveManagedCover(homeDir, resolved.coverPath, resolved.hash)
            if (managed != null) return managed
        }

        return when (source) {
            CoverSource.FILESYSTEM -> {
                resolveFilesystemCover(bookDir)?.let { CoverInfo.Filesystem(it, resolved.hash) }
            }

            CoverSource.EMBEDDED -> {
                resolved.primaryFilename
                    ?.let { bookDir.resolve(it) }
                    ?.takeIf { withContext(Dispatchers.IO) { Files.isRegularFile(it) } }
                    ?.let { CoverInfo.Embedded(it, resolved.hash) }
            }

            CoverSource.UPLOADED,
            CoverSource.ENRICHED,
            -> {
                val relPath = resolved.coverPath ?: return null
                val base = homeDir ?: return null
                resolveManagedCover(base, relPath, resolved.hash)
            }
        }
    }

    /**
     * Resolves [relPath] sandboxed under [base], returning [CoverInfo.Managed] when the
     * file exists or null when the path escapes the sandbox or the file is absent.
     * Mirrors the `resolveSandboxed` logic in `MetadataImageRoutes`.
     */
    private suspend fun resolveManagedCover(
        base: Path,
        relPath: String,
        hash: String?,
    ): CoverInfo.Managed? {
        if (relPath.startsWith("/") || "../" in relPath || relPath == "..") return null
        val absolute = base.resolve(relPath).normalize()
        if (!absolute.startsWith(base.normalize())) return null
        val exists = withContext(Dispatchers.IO) { Files.isRegularFile(absolute) }
        return if (exists) CoverInfo.Managed(absolute, hash) else null
    }

    /**
     * Finds the filesystem cover image in [bookDir], mirroring the scanner's
     * `Analyzer.resolveCover` precedence: a file whose stem is `cover`
     * (case-insensitive) wins, else the first image file by name. Returns null
     * when the directory holds no image — or has vanished since the scan.
     */
    private suspend fun resolveFilesystemCover(bookDir: Path): Path? =
        withContext(Dispatchers.IO) {
            if (!Files.isDirectory(bookDir)) return@withContext null
            val images =
                Files
                    .list(bookDir)
                    .use { stream ->
                        stream
                            .filter { Files.isRegularFile(it) }
                            .filter {
                                it.fileName
                                    .toString()
                                    .substringAfterLast('.', "")
                                    .lowercase() in IMAGE_EXTENSIONS
                            }.sorted(compareBy { it.fileName.toString() })
                            .toList()
                    }
            images.firstOrNull {
                it.fileName
                    .toString()
                    .substringBeforeLast('.')
                    .equals("cover", ignoreCase = true)
            }
                ?: images.firstOrNull()
        }

    /** Intermediate carrier for the columns [coverInfo] reads inside its transaction. */
    private data class ResolvedCover(
        val source: String,
        val libraryRoot: String,
        val rootRelPath: String,
        val primaryFilename: String?,
        val hash: String?,
        val coverPath: String?,
    )

    private companion object {
        /** Image file extensions the scanner recognises — see `FileTypeRules.imageExt`. */
        val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")
    }
}
