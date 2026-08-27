package com.calypsan.listenup.server.organize

import kotlinx.io.files.Path

/**
 * The result of planning a full-library reorganization. Two kinds of [MovePlanEntry] live here,
 * and the counts keep them apart because they are different promises to the person confirming:
 *
 * - **Relocations** — the book's folder is not where the rules say, so the whole folder moves.
 * - **In-place renames** — the folder is already canonical, but the book's single audio file still
 *   carries an old name (`Book 1 - The Land Founding/The Land - Founding.m4b`). Nothing moves
 *   between folders; one file is renamed where it stands.
 *
 * A book that is fully conformant — right folder, right filename — is silently excluded.
 * [OrganizePlanBuilder] never emits a no-op entry, which is what makes a second sweep move zero
 * files.
 */
data class MovePlan(
    val entries: List<MovePlanEntry>,
) {
    /** How many books this plan relocates — folder moves only, never an in-place rename. */
    val bookCount: Int get() = entries.count { it.isRelocation }

    /** How many individual files the [bookCount] relocations move, summed across those entries. */
    val fileCount: Int get() = entries.filter { it.isRelocation }.sumOf { it.files.size }

    /** How many books keep their folder and have only their single audio file renamed in place. */
    val renamedInPlaceCount: Int get() = entries.count { !it.isRelocation }

    /**
     * How many entries needed a deterministic ` (n)` disambiguation suffix because their
     * canonical target collided with another book's (or a book that's staying put).
     */
    val collisionCount: Int get() = entries.count { it.collisionResolved }
}

/**
 * One book's planned work.
 *
 * **A relocation** ([isRelocation]) moves the whole folder from [fromDir] to [toDir] (both
 * absolute, library-folder-rooted paths). [files] then carries every file found under [fromDir] at
 * plan time — audio, documents, covers, and any sidecar the scanner doesn't track in its own
 * tables — expanded as absolute (from, to) pairs that preserve each file's position relative to
 * the book folder.
 *
 * **An in-place rename** has [fromDir] == [toDir]: the folder is already canonical and only the
 * book's single audio file is misnamed. [files] then carries **exactly that one rename** and
 * nothing else — never a self-move of the untouched files, which the broker would reject as an
 * ambiguous source-and-destination-both-exist move.
 *
 * [toRootRelPath] is the value [MoveManifestExecutor] writes back to `books.root_rel_path` (for an
 * in-place rename, the path it already had), and [audioRename] the filename it writes back
 * alongside it (null when nothing is renamed).
 */
data class MovePlanEntry(
    val bookId: String,
    val fromDir: Path,
    val toDir: Path,
    val toRootRelPath: String,
    val files: List<FileMove>,
    val collisionResolved: Boolean,
    val audioRename: AudioFileRename? = null,
) {
    /** True when this entry moves the book's folder; false when it only renames a file in place. */
    val isRelocation: Boolean get() = fromDir != toDir
}

/**
 * The audio-file rename the organizer performs for a **single-file** book: [from] is the one
 * filename it has today, [to] the folder-matching name it takes. A single-file book's filename
 * carries nothing its folder doesn't, so aligning the two is pure gain — whether the folder is
 * moving at the same time or was already canonical and only the file lagged behind.
 *
 * Multi-file books never get one — their filenames often encode ordering (`Book 01 - Chapter
 * 03.mp3`) and other tools key on them. Identity is safe either way: a same-folder rename
 * preserves the inode, and the executor rewrites `book_audio_files.filename` in the same
 * transaction as the path, so the DB never points at a name that is no longer on disk.
 */
data class AudioFileRename(
    val from: String,
    val to: String,
)

/** One file's absolute source → destination pair inside a [MovePlanEntry]. */
data class FileMove(
    val from: Path,
    val to: Path,
)
