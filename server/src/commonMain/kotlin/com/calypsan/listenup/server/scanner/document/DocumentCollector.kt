package com.calypsan.listenup.server.scanner.document

import com.calypsan.listenup.api.dto.scanner.AnalyzedDocument
import com.calypsan.listenup.api.dto.scanner.FileEntry
import com.calypsan.listenup.api.dto.scanner.FileType
import com.calypsan.listenup.server.io.hashFileSha256
import com.calypsan.listenup.server.io.relativeTo
import com.calypsan.listenup.server.io.statFile
import com.calypsan.listenup.server.logging.loggerFor
import kotlinx.io.IOException
import kotlinx.io.files.Path

private val logger = loggerFor<DocumentCollector>()

/**
 * Collects EBOOK-typed files from a candidate's file list and maps them to
 * [AnalyzedDocument] values.
 *
 * Each document's [AnalyzedDocument.relPath] is relative to [bookRoot] (not the
 * library root), so it becomes the `filename` column in `book_documents`.
 * [AnalyzedDocument.hash] is the lowercase SHA-256 hex digest, streamed in 64 KiB
 * chunks — the whole file is never loaded into memory.
 *
 * Documents are sorted by their absolute path string before mapping, which gives
 * stable ordinals across rescans when the filesystem does not guarantee ordering.
 *
 * A document that cannot be stat'ed or hashed (deleted since the walk, a dangling link, permission
 * denied) is left out with a warning: one unreadable supplement must not fail the audiobook's analysis.
 */
internal class DocumentCollector {
    /**
     * Returns one [AnalyzedDocument] per EBOOK file in [files], ordered by
     * absolute path for stable ordinals.
     *
     * @param libraryRoot the library folder root; used to resolve each
     *   [FileEntry.relPath] (which is library-root-relative) to an absolute path.
     * @param bookRoot the book's absolute folder path; used to relativize each
     *   file's absolute path to produce [AnalyzedDocument.relPath].
     * @param files the full candidate file list as handed to the Analyzer.
     */
    fun collect(
        libraryRoot: Path,
        bookRoot: Path,
        files: List<FileEntry>,
    ): List<AnalyzedDocument> =
        files
            .asSequence()
            .filter { it.fileType == FileType.EBOOK }
            .map { entry -> entry to Path(libraryRoot, entry.relPath) }
            .sortedBy { (_, absolutePath) -> absolutePath.toString() }
            .mapNotNull { (_, absolutePath) -> analyze(absolutePath, bookRoot) }
            .toList()

    private fun analyze(
        absolutePath: Path,
        bookRoot: Path,
    ): AnalyzedDocument? {
        val size = statFile(absolutePath)?.size
        if (size == null) {
            logger.warn { "document $absolutePath could not be stat'ed (gone since the walk?) — leaving it out" }
            return null
        }
        val hash =
            try {
                hashFileSha256(absolutePath)
            } catch (e: IOException) {
                logger.warn(e) { "document $absolutePath could not be hashed — leaving it out" }
                return null
            }
        return AnalyzedDocument(
            // Documents always live beneath bookRoot (including a subtree doc rolled up into the owning
            // book), so relativeTo is non-null; the filename fallback covers the unreachable
            // non-descendant case rather than leaking an absolute path.
            relPath = absolutePath.relativeTo(bookRoot) ?: absolutePath.name,
            format =
                absolutePath.name
                    .substringAfterLast('.', "")
                    .lowercase(),
            size = size,
            hash = hash,
        )
    }
}
