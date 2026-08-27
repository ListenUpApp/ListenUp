package com.calypsan.listenup.server.librarywrite

import com.calypsan.listenup.server.io.isUnder
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
 * [this] with `.` and `..` segments folded away textually, without touching the filesystem.
 *
 * Purely lexical on purpose: it runs *before* the filesystem is consulted so that a path whose
 * later segments don't exist yet (the normal case for a file about to be written) still has its
 * escapes collapsed. `..` above an absolute root is clamped at `/`, matching the kernel.
 */
internal fun Path.lexicallyNormalized(): Path {
    val raw = toString()
    val absolute = raw.startsWith("/")
    val segments = ArrayDeque<String>()
    for (segment in raw.split('/')) {
        when {
            segment.isEmpty() || segment == "." -> {
                Unit
            }

            segment == ".." -> {
                when {
                    segments.isNotEmpty() && segments.last() != ".." -> segments.removeLast()

                    // A relative path may legitimately still lead with `..`; an absolute one cannot
                    // climb above `/`, so the segment is simply dropped.
                    !absolute -> segments.addLast(segment)

                    else -> Unit
                }
            }

            else -> {
                segments.addLast(segment)
            }
        }
    }
    val joined = segments.joinToString("/")
    return Path(if (absolute) "/$joined" else joined.ifEmpty { "." })
}

/**
 * [path] reduced to the form containment may safely compare: lexically normalised, then with its
 * longest existing ancestor resolved through [SystemFileSystem.resolve] so symbolic links are
 * followed, and the not-yet-existing tail re-appended.
 *
 * The two-part shape is forced by [SystemFileSystem.resolve] throwing on a path that does not
 * exist — which a write target usually does not. Resolving the existing ancestor is what closes
 * the symlink hole: a book directory that is a link out of the library resolves to its real
 * location here, and is refused there.
 */
internal fun resolvedForContainment(path: Path): Path {
    val normalized = path.lexicallyNormalized()
    val tail = mutableListOf<String>()
    var cursor: Path? = normalized
    while (cursor != null) {
        if (SystemFileSystem.exists(cursor)) {
            val base = runCatching { SystemFileSystem.resolve(cursor) }.getOrElse { cursor }
            return if (tail.isEmpty()) base else Path("$base/${tail.asReversed().joinToString("/")}")
        }
        tail.add(cursor.name)
        cursor = cursor.parent
    }
    return normalized
}

/**
 * True when [target] resolves inside at least one of [roots].
 *
 * Both sides go through [resolvedForContainment] first, which is what makes the underlying
 * string-prefix comparison sound: on raw paths a prefix test accepts `<root>/../outside/x`, and
 * that is exactly the escape this guards. Empty [roots] returns `false` — fail closed.
 */
internal fun isInsideAnyRoot(
    target: Path,
    roots: List<Path>,
): Boolean {
    if (roots.isEmpty()) return false
    val resolvedTarget = resolvedForContainment(target)
    return roots.any { resolvedTarget.isUnder(resolvedForContainment(it)) }
}
