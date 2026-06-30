package com.calypsan.listenup.server.scanner.pipeline

import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.api.dto.scanner.ChangeEventDto
import com.calypsan.listenup.api.dto.scanner.FileType
import com.calypsan.listenup.server.logging.loggerFor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

private val logger = loggerFor<Differ>()

/**
 * Stage 4 of the scanner pipeline: emits a [ChangeEventDto] per book that
 * changed since the previous scan. Books unchanged from the prior snapshot
 * emit nothing.
 *
 * Match algorithm, in priority order:
 *  1. **Path match.** A book at `rootRelPath` X matches a previous book at
 *     the same rootRelPath. The common case.
 *  2. **Inode match.** Any audio file's inode in the current book matches
 *     an audio file's inode in some previous book → treat as the same book
 *     that moved. This is how cross-folder renames are detected.
 *
 * Outcomes:
 *  - **No previous match** → [ChangeEventDto.Added].
 *  - **Match by inode but rootRelPath differs** → [ChangeEventDto.Moved].
 *  - **Match by path but content differs** → [ChangeEventDto.Modified].
 *  - **Match and content equal** → no emission.
 *  - **In previous but never matched in current** → [ChangeEventDto.Removed],
 *    emitted after the current flow is exhausted.
 *
 * Inode-based move detection is best-effort: filesystems without stable file
 * keys (Windows default, FAT, some SMB shares) leave `inode = null` in
 * [com.calypsan.listenup.api.dto.scanner.FileEntry]; those moves degrade to
 * a Removed + Added pair. Documented; not a bug.
 *
 * The "first scan" case (no prior state) is handled by passing
 * `previous = emptyList()`; every current book is then [ChangeEventDto.Added].
 */
internal class Differ {
    fun diff(
        current: Flow<AnalyzedBook>,
        previous: List<AnalyzedBook>,
    ): Flow<ChangeEventDto> =
        flow {
            val previousByRoot = previous.associateBy { it.candidate.rootRelPath }
            val previousByInode = inodeIndexFor(previous)
            val matchedRoots = mutableSetOf<String>()

            current.collect { book ->
                val match = previousByRoot[book.candidate.rootRelPath] ?: book.findByInode(previousByInode)
                val event = changeFor(book, match)
                if (event != null) emit(event)
                if (match != null) matchedRoots += match.candidate.rootRelPath
            }

            previous.forEach { previousBook ->
                if (previousBook.candidate.rootRelPath !in matchedRoots) {
                    logger.debug { "book removed: path=${previousBook.candidate.rootRelPath}" }
                    emit(ChangeEventDto.Removed(previousBook.candidate.rootRelPath))
                }
            }
        }

    private fun changeFor(
        current: AnalyzedBook,
        previous: AnalyzedBook?,
    ): ChangeEventDto? =
        when {
            previous == null -> {
                logger.debug { "book added: path=${current.candidate.rootRelPath}" }
                ChangeEventDto.Added(current)
            }

            previous.candidate.rootRelPath != current.candidate.rootRelPath -> {
                logger.debug {
                    "book moved: from=${previous.candidate.rootRelPath} to=${current.candidate.rootRelPath}"
                }
                ChangeEventDto.Moved(
                    from = previous.candidate.rootRelPath,
                    to = current.candidate.rootRelPath,
                    book = current,
                )
            }

            previous != current -> {
                logger.debug { "book modified: path=${current.candidate.rootRelPath}" }
                ChangeEventDto.Modified(
                    book = current,
                    previousRootRelPath = previous.candidate.rootRelPath,
                )
            }

            else -> {
                null
            }
        }

    private fun inodeIndexFor(books: List<AnalyzedBook>): Map<Long, AnalyzedBook> {
        val index = mutableMapOf<Long, AnalyzedBook>()
        books.forEach { book ->
            book.candidate.files.forEach { file ->
                val inode = file.inode
                if (file.fileType == FileType.AUDIO && inode != null) {
                    // First-write-wins: if two previous books somehow share an
                    // inode (impossible on one filesystem, defensive here),
                    // keep the first.
                    if (inode !in index) index[inode] = book
                }
            }
        }
        return index
    }

    private fun AnalyzedBook.findByInode(index: Map<Long, AnalyzedBook>): AnalyzedBook? =
        candidate.files.firstNotNullOfOrNull { file ->
            val inode = file.inode
            if (file.fileType == FileType.AUDIO && inode != null) index[inode] else null
        }
}
