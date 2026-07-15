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
 * Three grouping rules:
 *  1. **Multi-disc collapse.** When a file's immediate parent folder name
 *     matches the [MultiDiscPattern] regex (`CD1`, `Disc 2`, `Disk 3`, …),
 *     the book root rolls up to the disc folder's parent and the disc
 *     folder's name is recorded in [CandidateBook.discFolders]. ABS allows
 *     up to 3-digit disc numbers, case-insensitive, with optional whitespace.
 *  2. **Audio-less subfolder rollup.** A folder that contains no audio (e.g.
 *     `Author/Title/extras/notes.pdf`) is not a book. Its files roll up into
 *     the nearest ancestor folder that *does* have audio, so a book's bonus
 *     PDFs/images attach to the book instead of surfacing as a permanently
 *     audio-less "book" that fails `NoRecognizedAudio` on every scan. A
 *     genuinely orphaned audio-less folder (no audio ancestor) is preserved
 *     as before rather than silently dropped.
 *  3. **Single-file books.** An audio file directly in the library root
 *     becomes its own [CandidateBook] with `isFile = true` and
 *     `rootRelPath` set to the file's relative path. Each such audio file
 *     is its own book; a sibling `cover.*` (or any root-level image) rides
 *     along so a loose single-file book keeps its cover. Non-image, non-audio
 *     files at the library root are dropped.
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
 * insertion-ordered maps keyed by book root; single-file audio files and
 * root-level images accumulate separately and are resolved in [emitFolderBooks].
 */
private class GroupingAccumulator(
    private val emit: suspend (CandidateBook) -> Unit,
) {
    private val filesByRoot = linkedMapOf<String, MutableList<FileEntry>>()
    private val discsByRoot = linkedMapOf<String, MutableSet<String>>()
    private val rootAudioFiles = mutableListOf<FileEntry>()
    private val rootImages = mutableListOf<FileEntry>()

    fun add(entry: FileEntry) {
        val parentSegments = entry.relPath.split('/').dropLast(1)

        if (parentSegments.isEmpty()) {
            // File at the library root. Audio → a single-file book; an image is retained as a
            // sibling cover for those books; anything else is dropped.
            when (entry.fileType) {
                FileType.AUDIO -> rootAudioFiles += entry
                FileType.IMAGE -> rootImages += entry
                else -> Unit
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
        // Root-level single-file books first (ordering between the two groups isn't contractual).
        // Each keeps every root-level image as a sibling so `resolveCover` can pick a `cover.*` (or
        // the first image) — a loose `MyBook.m4b` + `cover.jpg` no longer loses its cover.
        rootAudioFiles.forEach { audio ->
            emit(
                CandidateBook(
                    rootRelPath = audio.relPath,
                    isFile = true,
                    files = listOf(audio) + rootImages,
                ),
            )
        }

        // Roots that hold at least one audio file are real books; audio-less roots roll their files
        // up into the nearest such ancestor (M8).
        val audioRoots = filesByRoot.filterValues { files -> files.any { it.fileType == FileType.AUDIO } }.keys
        val rolledUp = mutableSetOf<String>()
        for ((root, files) in filesByRoot) {
            if (root in audioRoots) continue
            val ancestor = nearestAudioAncestor(root, audioRoots) ?: continue
            filesByRoot.getValue(ancestor) += files
            rolledUp += root
        }

        filesByRoot.forEach { (root, files) ->
            if (root in rolledUp) return@forEach // its files now live under an audio ancestor
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

    /** The longest audio-bearing root that is a strict ancestor of [root], or null if none. */
    private fun nearestAudioAncestor(
        root: String,
        audioRoots: Set<String>,
    ): String? =
        audioRoots
            .filter { root.startsWith("$it/") }
            .maxByOrNull { it.length }

    private fun bookRootAndDisc(parentSegments: List<String>): Pair<String, String?> {
        val last = parentSegments.last()
        return if (MultiDiscPattern.matches(last)) {
            parentSegments.dropLast(1).joinToString("/") to last
        } else {
            parentSegments.joinToString("/") to null
        }
    }
}
