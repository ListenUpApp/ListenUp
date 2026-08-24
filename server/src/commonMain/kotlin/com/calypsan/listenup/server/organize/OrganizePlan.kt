package com.calypsan.listenup.server.organize

import kotlinx.io.files.Path

/**
 * The result of planning a full-library reorganization: one [MovePlanEntry] per book whose
 * current on-disk location differs from [OrganizerPathPlanner]'s canonical output for it. Books
 * already at their canonical path are silently excluded — [OrganizePlanBuilder] never emits a
 * no-op entry.
 */
data class MovePlan(
    val entries: List<MovePlanEntry>,
) {
    /** How many books this plan moves. */
    val bookCount: Int get() = entries.size

    /** How many individual files this plan moves, summed across every entry. */
    val fileCount: Int get() = entries.sumOf { it.files.size }

    /**
     * How many entries needed a deterministic ` (n)` disambiguation suffix because their
     * canonical target collided with another book's (or a book that's staying put).
     */
    val collisionCount: Int get() = entries.count { it.collisionResolved }
}

/**
 * One book's move: its whole folder relocates from [fromDir] to [toDir] (both absolute,
 * library-folder-rooted paths). [files] carries every file found under [fromDir] at plan time —
 * audio, documents, covers, and any sidecar the scanner doesn't track in its own tables —
 * expanded as absolute (from, to) pairs that preserve each file's position relative to the book
 * folder. [toRootRelPath] is the value [MoveManifestExecutor] writes back to `books.root_rel_path`,
 * and [audioRename] the filename it writes back alongside it (null when nothing is renamed).
 */
data class MovePlanEntry(
    val bookId: String,
    val fromDir: Path,
    val toDir: Path,
    val toRootRelPath: String,
    val files: List<FileMove>,
    val collisionResolved: Boolean,
    val audioRename: AudioFileRename? = null,
)

/**
 * The audio-file rename that rides along with a **single-file** book's move: [from] is the one
 * filename it has today, [to] the folder-matching name it takes. A single-file book's filename
 * carries nothing its folder doesn't, so aligning the two is pure gain.
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
