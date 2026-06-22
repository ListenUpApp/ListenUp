package com.calypsan.listenup.server.scanner.document

import com.calypsan.listenup.api.dto.scanner.AnalyzedDocument
import com.calypsan.listenup.api.dto.scanner.FileEntry
import com.calypsan.listenup.api.dto.scanner.FileType
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

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
            .map { entry -> entry to libraryRoot.resolve(entry.relPath) }
            .sortedBy { (_, absolutePath) -> absolutePath.toString() }
            .map { (_, absolutePath) ->
                AnalyzedDocument(
                    relPath = bookRoot.relativize(absolutePath).toString(),
                    format =
                        absolutePath.fileName
                            .toString()
                            .substringAfterLast('.', "")
                            .lowercase(),
                    size = Files.size(absolutePath),
                    hash = sha256OfPath(absolutePath),
                )
            }

    companion object {
        /** SHA-256 hex digest of the file at [path], streamed in 64 KiB chunks. */
        private fun sha256OfPath(path: Path): String = Files.newInputStream(path).use { sha256OfStream(it) }

        /** SHA-256 hex of bytes read from [input], streamed in 64 KiB chunks. */
        internal fun sha256OfStream(input: InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
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
