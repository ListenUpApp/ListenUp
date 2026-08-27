package com.calypsan.listenup.server.upload

import kotlinx.io.files.Path

/** The prefix every server-minted upload session id carries, so a staging directory is self-identifying. */
internal const val UPLOAD_SESSION_ID_PREFIX = "up-"

/**
 * True when [id] is safe to use as an upload-session directory-name segment: it carries the
 * server-minted [UPLOAD_SESSION_ID_PREFIX], and is free of path separators and `..` sequences.
 *
 * Session ids are always server-minted, but they come back from the client on every subsequent
 * request in the session, so every id-taking entry point validates before touching the
 * filesystem — mirroring [com.calypsan.listenup.server.absimport.isSafeImportId] and
 * `isSafeBackupId`. The prefix check is the extra turn of the screw the other two don't need:
 * an upload id names a directory that the finalize step then *moves content out of*, so an id
 * that isn't one of ours must never even resolve to a path.
 */
internal fun isSafeUploadSessionId(id: String): Boolean {
    if (!id.startsWith(UPLOAD_SESSION_ID_PREFIX)) return false
    if (id.length <= UPLOAD_SESSION_ID_PREFIX.length) return false
    if (id.contains('/') || id.contains('\\')) return false
    if (id.contains("..")) return false
    return true
}

/**
 * All filesystem locations the upload domain uses, rooted at `$LISTENUP_HOME/uploads/`.
 *
 * **Staging lives outside every library folder, and that is the whole point.** An upload in
 * flight is not library content: it may be abandoned, it may be a duplicate we refuse, it may be
 * half a file when the connection drops. Staging it under the data home means the scanner never
 * walks it, the watcher never sees it, and the broker's containment refuses any write that tries
 * to escape it — so a failed upload leaves nothing behind that anything else has to reason about.
 *
 * Like the import domain, upload sessions are **filesystem-truth**: there is no database table.
 * A session exists exactly as long as its directory does.
 *
 * [homeDir] is the same data-home directory that holds the live SQLite database (e.g.
 * `~/ListenUp` by default, or `$LISTENUP_HOME` when the environment variable is set).
 */
internal class UploadPaths(
    private val homeDir: Path,
) {
    /** Root directory holding one subdirectory per in-flight upload session. */
    val uploadsDir: Path get() = Path(homeDir, "uploads")

    /** The staging directory for the session with the given [sessionId]. */
    fun dirFor(sessionId: String): Path = Path(uploadsDir, sessionId)
}
