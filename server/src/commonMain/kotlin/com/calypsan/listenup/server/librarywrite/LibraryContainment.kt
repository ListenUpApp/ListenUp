package com.calypsan.listenup.server.librarywrite

import com.calypsan.listenup.server.io.isSymlink
import com.calypsan.listenup.server.io.isUnder
import com.calypsan.listenup.server.io.lexicallyNormalized
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Supplies the library folder roots [LibraryWriteBroker] is permitted to write inside.
 *
 * A separate seam rather than a constructor `Path` because the broker is a singleton serving
 * *every* library: `library_folders` holds many live `root_path` rows, and folders are added and
 * removed while the server runs. Implementations are expected to read current state per call.
 *
 * An empty list means **nothing is writable** — a server with no library folders configured has
 * nowhere legitimate to write, and failing closed is the only safe reading of that.
 */
fun interface LibraryRootProvider {
    /** The live library folder roots, as absolute paths. */
    suspend fun roots(): List<Path>
}

/**
 * How a symbolic link that is the **final** segment of a path is judged.
 *
 * Links before the final segment are always followed — the kernel walks through them, and so does
 * containment. The final one depends on what the operation does to it.
 */
internal enum class FinalLink {
    /** Judge the link where it points. For ops that act *through* it: creating, listing or removing a directory. */
    Follow,

    /**
     * Judge the link where it sits. For ops whose syscall acts on the directory entry itself —
     * `unlink(2)` and `rename(2)` never follow a final link — so a link inside the library may be
     * deleted, moved or replaced wherever it points, and its target is never touched.
     */
    InPlace,
}

/**
 * [path] as the kernel will see it, reduced to a form containment may safely compare — or `null`
 * when it cannot be resolved, which containment treats as outside.
 *
 * The longest prefix of the **raw** path that exists is resolved through [SystemFileSystem.resolve]
 * (`realpath(3)` natively, the canonical file on the JVM), then the not-yet-existing tail is
 * re-appended. The order is the whole point: `..` after a symbolic link climbs from where the link
 * points, so folding the raw path as text first turns `<root>/link/../x` into `<root>/x` while the
 * kernel writes `<link target's parent>/x`.
 *
 * `null` — refused — in three cases, none of which can be resolved soundly:
 *  - a missing prefix is nonetheless a symbolic link: dangling, or caught in a loop. A dangling
 *    link is one `mkdir` away from leading outside;
 *  - the tail holds `..`. A tail segment that does not exist is not a link, but a `..` after it
 *    steps back into the existing tree without resolving it: `<root>/Missing/../Link/x` would fold
 *    to `<root>/Link/x` with `Link` never resolved. No caller legitimately needs a `..` beyond a
 *    directory that is not there;
 *  - [SystemFileSystem.resolve] fails on a prefix that exists.
 *
 * With [finalLink] = [FinalLink.InPlace] a final segment that is itself a link is not resolved:
 * its parent is, and the link's own name is appended.
 */
internal fun resolvedForContainment(
    path: Path,
    finalLink: FinalLink = FinalLink.Follow,
): Path? {
    if (finalLink == FinalLink.InPlace && isSymlink(path)) {
        val parent = path.parent ?: return null
        return resolvedForContainment(parent)?.let { Path(it, path.name) }
    }
    val tail = ArrayDeque<String>()
    var cursor: Path? = path
    while (cursor != null) {
        if (SystemFileSystem.exists(cursor)) {
            if (".." in tail) return null
            val resolved = realPathOrNull(cursor) ?: return null
            return tail.fold(resolved) { parent, segment -> Path(parent, segment) }.lexicallyNormalized()
        }
        if (isSymlink(cursor)) return null
        tail.addFirst(cursor.name)
        cursor = cursor.parent
    }
    return path.lexicallyNormalized()
}

/** [SystemFileSystem.resolve], or null when it cannot resolve [path] (it throws rather than returning nothing). */
private fun realPathOrNull(path: Path): Path? =
    try {
        SystemFileSystem.resolve(path)
    } catch (_: IOException) {
        null
    } catch (_: IllegalStateException) {
        // The native `realpath` failing between the exists() check and the call (the path vanished,
        // or became a loop) surfaces as IllegalStateException rather than an IOException.
        null
    }

/**
 * True when [target] resolves inside at least one of [roots], compared segment by segment on
 * resolved paths (see [resolvedForContainment]). Fails closed: empty [roots], or a [target] that
 * cannot be resolved, is outside. A root that cannot be resolved admits nothing.
 */
internal fun isInsideAnyRoot(
    target: Path,
    roots: List<Path>,
    finalLink: FinalLink = FinalLink.Follow,
): Boolean {
    val resolvedTarget = resolvedForContainment(target, finalLink) ?: return false
    return roots.any { root -> resolvedForContainment(root)?.let { resolvedTarget.isUnder(it) } == true }
}
