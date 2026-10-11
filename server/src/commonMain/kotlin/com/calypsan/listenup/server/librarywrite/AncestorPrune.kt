package com.calypsan.listenup.server.librarywrite

import kotlinx.io.files.Path

/**
 * The empty-directory cleanup for everything between [dir] and its library folder root — one
 * [WriteOp.DeleteDirIfEmpty] per level, deepest first. [dir] itself is not included: whether the
 * book's own folder goes is the caller's call ([WriteOp.DeleteDir] for Delete Book, a
 * [WriteOp.DeleteDirIfEmpty] for Organize).
 *
 * Removing `Aleron Kong/Chaos Seeds/Book 1` — deleting it, or moving it elsewhere — leaves two
 * directories behind that now describe nothing; the walk removes the series folder, finds the
 * author folder empty too, and removes that. It needs no knowledge of what a "series folder" is —
 * the shape of the path is the only input, so any hierarchy the organizer can produce is pruned by
 * the same code.
 *
 * Nothing here decides *whether* a directory should go: [WriteOp.DeleteDirIfEmpty] is best-effort,
 * so the first ancestor still holding a sibling book (or a stray file the user put there) stops the
 * chain on its own, and the ops above it become no-ops.
 *
 * The walk is bounded by **segment count, not path comparison**: a book stored at a
 * [rootRelPath] of N segments has exactly N-1 ancestors below the root, so the root is unreachable
 * by construction rather than by a string compare that a trailing slash could defeat. The broker
 * refuses a root-targeted op regardless — belt and braces on the one mistake that would take the
 * library with it.
 */
fun ancestorPruneOps(
    dir: Path,
    rootRelPath: String,
): List<WriteOp.DeleteDirIfEmpty> {
    val depth = rootRelPath.split('/').count { it.isNotEmpty() }
    val ops = mutableListOf<WriteOp.DeleteDirIfEmpty>()
    var current = dir.parent
    repeat(maxOf(depth - 1, 0)) {
        val ancestor = current ?: return ops
        ops.add(WriteOp.DeleteDirIfEmpty(ancestor))
        current = ancestor.parent
    }
    return ops
}
