package com.calypsan.listenup.server.upload

import com.calypsan.listenup.server.io.isUnder
import com.calypsan.listenup.server.librarywrite.resolvedForContainment
import kotlinx.io.files.Path

/** Longest accepted `relPath`, in characters — well past any real `Author/Series/Title/Disc 1/track.mp3`. */
private const val MAX_REL_PATH_LENGTH = 1024

/** Most path segments one uploaded file may carry. Real book layouts are three or four deep. */
private const val MAX_REL_PATH_SEGMENTS = 24

/** Longest accepted single segment — the `NAME_MAX` most filesystems enforce anyway. */
private const val MAX_SEGMENT_LENGTH = 255

/** How much of a rejected path is worth quoting back in `debugInfo`; the rest is attacker noise. */
private const val REASON_PATH_EXCERPT = 120

/**
 * The result of turning a client-supplied `relPath` into a staging path, or refusing to.
 *
 * A sealed pair rather than a nullable path so the refusal carries *why* — the route folds that
 * into `debugInfo` for the operator's logs, while the user-facing message stays the constant on
 * [com.calypsan.listenup.api.error.UploadError.InvalidFilePath].
 */
internal sealed interface UploadTarget {
    /** [path] is inside the session directory and safe to open. */
    data class Accepted(
        val path: Path,
    ) : UploadTarget

    /** The path was refused before any file was opened; [reason] is operator-facing detail. */
    data class Refused(
        val reason: String,
    ) : UploadTarget
}

/**
 * Resolves [relPath] — **untrusted client input** — to a path inside [sessionDir], or refuses it.
 *
 * This runs before a single byte is read, and it is the first line rather than the last: the
 * broker's containment guard at the far end of the flow is a backstop for a bug here, not a
 * substitute for it. Anything that could put bytes outside [sessionDir] is refused up front:
 *
 *  - **Absolute paths** (`/etc/cron.d/x`, `C:\Windows\x`) — an upload names a location *within*
 *    the user's selection; a rooted path is never that.
 *  - **`..` traversal**, raw or percent-encoded. Percent-encoding needs no special handling here
 *    and gets none: Ktor decodes query parameters before this sees them, so `%2e%2e%2f` arrives
 *    as `../` and is refused by the same segment check as the literal form. Handling the encoded
 *    case separately would mean a second decoder to keep in step with Ktor's.
 *  - **Backslash separators**, normalised to `/` *before* the segment check, so a Windows-shaped
 *    `..\..\x` is refused rather than accepted as one exotic filename.
 *  - **Empty, `.`, and whitespace-only segments**, which collapse or resolve unpredictably.
 *  - **Anything that does not resolve inside [sessionDir]** once `.`/`..` are folded and symbolic
 *    links on the existing prefix are followed — the same [resolvedForContainment] the library
 *    write broker uses, for the same reason a raw string prefix is not enough.
 */
internal fun resolveUploadTarget(
    sessionDir: Path,
    relPath: String,
): UploadTarget {
    if (relPath.isBlank()) return UploadTarget.Refused("empty relPath")
    if (relPath.length > MAX_REL_PATH_LENGTH) return UploadTarget.Refused("relPath longer than $MAX_REL_PATH_LENGTH")
    if (relPath.any { it.code < ' '.code }) return UploadTarget.Refused("relPath contains a control character")

    val normalized = relPath.replace('\\', '/')
    if (normalized.startsWith('/')) return UploadTarget.Refused("absolute relPath: ${normalized.excerpt()}")
    if (hasDriveLetterPrefix(normalized)) {
        return UploadTarget.Refused("drive-rooted relPath: ${normalized.excerpt()}")
    }

    val segments = normalized.split('/')
    if (segments.size > MAX_REL_PATH_SEGMENTS) {
        return UploadTarget.Refused("relPath deeper than $MAX_REL_PATH_SEGMENTS segments")
    }
    segments.forEach { segment ->
        val refusal = refuseSegment(segment, normalized)
        if (refusal != null) return refusal
    }

    val target = segments.fold(sessionDir) { acc, segment -> Path(acc, segment) }
    if (!resolvedForContainment(target).isUnder(resolvedForContainment(sessionDir))) {
        return UploadTarget.Refused("relPath resolves outside the session directory: ${normalized.excerpt()}")
    }
    return UploadTarget.Accepted(target)
}

/** The per-segment half of [resolveUploadTarget]'s rules; null when [segment] is acceptable. */
private fun refuseSegment(
    segment: String,
    whole: String,
): UploadTarget.Refused? =
    when {
        segment.isEmpty() -> UploadTarget.Refused("empty path segment in ${whole.excerpt()}")
        segment.isBlank() -> UploadTarget.Refused("whitespace-only path segment in ${whole.excerpt()}")
        segment == "." || segment == ".." -> UploadTarget.Refused("traversal segment '$segment' in ${whole.excerpt()}")
        segment.length > MAX_SEGMENT_LENGTH -> UploadTarget.Refused("path segment longer than $MAX_SEGMENT_LENGTH")
        else -> null
    }

/** True for a Windows drive-rooted path (`C:/x`, `c:x`) — rooted, so never a path within a selection. */
private fun hasDriveLetterPrefix(path: String): Boolean = path.length >= 2 && path[1] == ':' && path[0].isLetter()

/** [this] truncated for a log/`debugInfo` line — a refused path is attacker-controlled text. */
private fun String.excerpt(): String = if (length <= REASON_PATH_EXCERPT) this else take(REASON_PATH_EXCERPT) + "…"
