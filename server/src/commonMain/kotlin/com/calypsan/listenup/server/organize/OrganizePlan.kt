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
 * folder. [toRootRelPath] is the value [MoveManifestExecutor] writes back to `books.root_rel_path`.
 */
data class MovePlanEntry(
    val bookId: String,
    val fromDir: Path,
    val toDir: Path,
    val toRootRelPath: String,
    val files: List<FileMove>,
    val collisionResolved: Boolean,
)

/** One file's absolute source → destination pair inside a [MovePlanEntry]. */
data class FileMove(
    val from: Path,
    val to: Path,
)
