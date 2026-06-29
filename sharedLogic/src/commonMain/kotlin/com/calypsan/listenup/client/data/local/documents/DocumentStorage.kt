package com.calypsan.listenup.client.data.local.documents

import com.calypsan.listenup.client.data.local.images.StoragePaths
import com.calypsan.listenup.core.IODispatcher
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.write

private val logger = KotlinLogging.logger {}

/**
 * On-disk cache for supplementary book documents (PDFs, ebooks, etc.).
 *
 * Storage structure:
 * ```
 * {filesDir}/documents/{bookId}/{docId}.{format}
 * ```
 *
 * File presence on disk is the entire "downloaded" signal — there is no separate
 * download-state DB table (mirrors the cover-image cache model, not the audio download model).
 *
 * Extracted as an interface so [DocumentRepositoryImpl] tests can inject a fake without
 * touching the real filesystem. Production code uses [DocumentStorageImpl].
 */
internal interface DocumentStorage {
    /**
     * Returns the absolute path for a cached document without checking whether the file exists.
     *
     * @param bookId Owning book identifier.
     * @param docId Server document UUID.
     * @param format Lowercase file extension (e.g. `"pdf"`, `"epub"`).
     * @return Absolute path string of the form `{filesDir}/documents/{bookId}/{docId}.{format}`.
     */
    fun pathFor(
        bookId: String,
        docId: String,
        format: String,
    ): String

    /**
     * Returns `true` if a file exists at [path].
     *
     * @param path Absolute path, typically obtained from [pathFor].
     */
    fun exists(path: String): Boolean

    /**
     * Writes [bytes] to [path], creating any missing parent directories.
     *
     * Runs on [IODispatcher] to keep file-system IO off the main thread.
     *
     * @param path Absolute destination path, typically obtained from [pathFor].
     * @param bytes Raw document bytes to cache.
     */
    suspend fun write(
        path: String,
        bytes: ByteArray,
    )

    /**
     * Deletes the cached file for a document, if present. Best-effort: a missing file or a
     * failed delete is silently ignored — the cache is a derived store whose worst case is a
     * re-fetch. Runs on [IODispatcher].
     *
     * Used to garbage-collect orphaned cache files when a book's document UUIDs rotate on
     * rescan.
     *
     * @param bookId Owning book identifier.
     * @param docId Server document UUID whose cached file should be removed.
     * @param format Lowercase file extension the file was cached under.
     */
    suspend fun deleteCached(
        bookId: String,
        docId: String,
        format: String,
    )
}

/**
 * Production [DocumentStorage] backed by the platform's app-private filesystem.
 *
 * This class is platform-agnostic; it delegates storage location resolution to
 * [StoragePaths], which each platform supplies (Android: `Context.filesDir`,
 * iOS: `NSDocumentDirectory`, JVM: OS-appropriate app data dir).
 *
 * @property storagePaths Platform-specific base directory provider.
 */
internal class DocumentStorageImpl(
    storagePaths: StoragePaths,
) : DocumentStorage {
    private val documentsDir: Path = Path(storagePaths.filesDir.toString(), DOCUMENTS_DIR)

    override fun pathFor(
        bookId: String,
        docId: String,
        format: String,
    ): String {
        val bookDir = Path(documentsDir.toString(), bookId)
        return Path(bookDir.toString(), "$docId.$format").toString()
    }

    override fun exists(path: String): Boolean = SystemFileSystem.exists(Path(path))

    override suspend fun write(
        path: String,
        bytes: ByteArray,
    ) {
        withContext(IODispatcher) {
            val target = Path(path)
            val parent = target.parent
            if (parent != null && !SystemFileSystem.exists(parent)) {
                SystemFileSystem.createDirectories(parent)
            }
            SystemFileSystem.sink(target).buffered().use { sink ->
                sink.write(bytes)
            }
        }
    }

    override suspend fun deleteCached(
        bookId: String,
        docId: String,
        format: String,
    ) {
        withContext(IODispatcher) {
            try {
                SystemFileSystem.delete(Path(pathFor(bookId, docId, format)), mustExist = false)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "[DocumentStorage] Failed to delete cached document $docId for book $bookId" }
            }
        }
    }

    companion object {
        private const val DOCUMENTS_DIR = "documents"
    }
}
