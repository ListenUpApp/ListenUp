package com.calypsan.listenup.server.scanner.document

import com.calypsan.listenup.api.dto.scanner.AnalyzedDocument
import com.calypsan.listenup.api.dto.scanner.FileEntry
import com.calypsan.listenup.api.dto.scanner.FileType
import com.calypsan.listenup.server.io.hashFileSha256
import com.calypsan.listenup.server.io.relativeTo
import com.calypsan.listenup.server.io.statFile
import kotlinx.io.files.Path

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
            .filter { it.fileType == FileType.EBOOK }
            .map { entry -> entry to Path(libraryRoot, entry.relPath) }
            .sortedBy { (_, absolutePath) -> absolutePath.toString() }
            .map { (_, absolutePath) ->
                AnalyzedDocument(
                    // Documents always live beneath bookRoot (including a subtree doc rolled up into
                    // the owning book), so relativeTo is non-null; the filename fallback covers the
                    // unreachable non-descendant case rather than leaking an absolute path.
                    relPath = absolutePath.relativeTo(bookRoot) ?: absolutePath.name,
                    format =
                        absolutePath.name
                            .substringAfterLast('.', "")
                            .lowercase(),
                    size = statFile(absolutePath)?.size ?: error("file vanished during scan: $absolutePath"),
                    hash = hashFileSha256(absolutePath),
                )
            }
}
