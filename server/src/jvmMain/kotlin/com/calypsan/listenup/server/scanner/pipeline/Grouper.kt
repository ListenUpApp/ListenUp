package com.calypsan.listenup.server.scanner.pipeline

import com.calypsan.listenup.api.dto.scanner.CandidateBook
import com.calypsan.listenup.api.dto.scanner.FileEntry
import com.calypsan.listenup.api.dto.scanner.FileType
import com.calypsan.listenup.server.scanner.inference.MultiDiscPattern
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.fold

/**
 * Stage 2 of the scanner pipeline: groups [FileEntry]s into [CandidateBook]s
 * by their book root.
 *
 * Two grouping rules:
 *  1. **Multi-disc collapse.** When a file's immediate parent folder name
 *     matches the [MultiDiscPattern] regex (`CD1`, `Disc 2`, `Disk 3`, …),
 *     the book root rolls up to the disc folder's parent and the disc
 *     folder's name is recorded in [CandidateBook.discFolders]. ABS allows
 *     up to 3-digit disc numbers, case-insensitive, with optional whitespace.
 *  2. **Single-file books.** An audio file directly in the library root
 *     becomes its own [CandidateBook] with `isFile = true` and
 *     `rootRelPath` set to the file's relative path. Each such audio file
 *     is its own book; non-audio files at the library root are dropped.
 *
 * Books at deeper levels (`Author/Title`, `Author/Series/Title`) all use
 * the file's parent directory as the book root.
 *
 * Order: emission preserves the order in which book roots are first seen
 * in the upstream Walker flow. Walker is depth-first stable, so the order
 * is reproducible across runs over the same filesystem snapshot.
 */
internal class Grouper {
    fun group(files: Flow<FileEntry>): Flow<CandidateBook> =
        flow {
            // Single-file books emit immediately. Folder-rooted books accumulate
            // until the upstream is exhausted, then emit in first-seen order.
            val acc =
                files.fold(GroupingAccumulator(emit = { emit(it) })) { acc, entry ->
                    acc.apply { add(entry) }
                }
            acc.emitFolderBooks()
        }
}

/**
 * Mutable state for [Grouper.group]. Folder-rooted books collect into
 * insertion-ordered maps keyed by book root; single-file books are emitted
 * eagerly through [emit].
 */
private class GroupingAccumulator(
    private val emit: suspend (CandidateBook) -> Unit,
) {
    private val filesByRoot = linkedMapOf<String, MutableList<FileEntry>>()
    private val discsByRoot = linkedMapOf<String, MutableSet<String>>()
    private val singleFileBooks = mutableListOf<CandidateBook>()

    fun add(entry: FileEntry) {
        val parentSegments = entry.relPath.split('/').dropLast(1)

        if (parentSegments.isEmpty()) {
            // File at the library root — single-file book if audio, else dropped.
            if (entry.fileType == FileType.AUDIO) {
                singleFileBooks +=
                    CandidateBook(
                        rootRelPath = entry.relPath,
                        isFile = true,
                        files = listOf(entry),
                    )
            }
            return
        }

        val (bookRoot, discFolder) = bookRootAndDisc(parentSegments)
        filesByRoot.getOrPut(bookRoot) { mutableListOf() } += entry
        if (discFolder != null) {
            discsByRoot.getOrPut(bookRoot) { linkedSetOf() } += discFolder
        }
    }

    suspend fun emitFolderBooks() {
        // Emit single-file books first (Walker visits them in depth-first
        // order; root-level files come before subdirectory descents on most
        // filesystems, but ordering between the two groups isn't contractual).
        singleFileBooks.forEach { emit(it) }
        filesByRoot.forEach { (root, files) ->
            emit(
                CandidateBook(
                    rootRelPath = root,
                    isFile = false,
                    files = files.toList(),
                    discFolders = discsByRoot[root]?.sorted().orEmpty(),
                ),
            )
        }
    }

    private fun bookRootAndDisc(parentSegments: List<String>): Pair<String, String?> {
        val last = parentSegments.last()
        return if (MultiDiscPattern.matches(last)) {
            parentSegments.dropLast(1).joinToString("/") to last
        } else {
            parentSegments.joinToString("/") to null
        }
    }
}
