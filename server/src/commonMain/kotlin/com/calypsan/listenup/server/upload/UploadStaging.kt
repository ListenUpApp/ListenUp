package com.calypsan.listenup.server.upload

import com.calypsan.listenup.server.io.deleteRecursively
import com.calypsan.listenup.server.io.isSymlink
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.uuid.Uuid

/** One gibibyte, so the session caps below read as sizes rather than as digit piles. */
private const val BYTES_PER_GIB: Long = 1024L * 1024 * 1024

/** Default cap on files per session — a big multi-disc batch, and nothing like a fill-the-disk loop. */
private const val DEFAULT_MAX_FILES = 1_000

/** Default cap on total bytes per session, in GiB. */
private const val DEFAULT_MAX_SESSION_GIB = 64L

/** Default cap on any single file, in GiB. */
private const val DEFAULT_MAX_FILE_GIB = 16L

/**
 * Caps on one upload session. A session that would exceed either is refused and swept rather than
 * left half-staged: an admin-only endpoint is still an endpoint, and an unbounded one is a way to
 * fill the server's data disk with a single request loop.
 *
 * The numbers are generous on purpose — a batch of a dozen multi-disc audiobooks is a normal
 * first-run upload, and a cap that a legitimate user hits is a bug report, not a defence.
 */
internal data class UploadLimits(
    /** Most files one session may stage. */
    val maxFiles: Int = DEFAULT_MAX_FILES,
    /** Most bytes one session may stage in total. */
    val maxSessionBytes: Long = DEFAULT_MAX_SESSION_GIB * BYTES_PER_GIB,
    /** Most bytes any single file may carry. */
    val maxFileBytes: Long = DEFAULT_MAX_FILE_GIB * BYTES_PER_GIB,
)

/** What a session currently holds — the numbers both the quota check and the client's progress read. */
internal data class UploadSessionStats(
    val fileCount: Int,
    val totalBytes: Long,
)

/**
 * Every filesystem operation the upload domain performs, all of it under
 * `$LISTENUP_HOME/uploads/` and none of it inside a library folder.
 *
 * That separation is why this class exists as a seam at all: library-folder content is the
 * [com.calypsan.listenup.server.librarywrite.LibraryWriteBroker]'s exclusive business, and the
 * upload flow only becomes the broker's business at the moment a staged file is moved *into* the
 * library. Everything before that — creating the session directory, streaming a part file,
 * renaming it into place, sweeping the session — happens out here where a failure leaves no trace
 * anything else can trip over.
 *
 * Sessions are **filesystem-truth**: a session exists exactly as long as its directory does.
 * There is no table, no in-memory registry to lose on restart, and no way for the two to disagree.
 */
internal class UploadStaging(
    private val paths: UploadPaths,
    val limits: UploadLimits = UploadLimits(),
) {
    /**
     * Mints a fresh session and creates its staging directory, returning the session id.
     *
     * The id is server-minted (`up-<UUID>`) and never derived from anything the client sent, so
     * it cannot contain a path separator or a traversal sequence — the same rule the ABS import
     * ids follow, for the same reason.
     */
    fun createSession(): String {
        val sessionId = "$UPLOAD_SESSION_ID_PREFIX${Uuid.random()}"
        SystemFileSystem.createDirectories(paths.dirFor(sessionId))
        return sessionId
    }

    /**
     * The staging directory for [sessionId], or null when there is no such live session.
     *
     * Three things must hold, and the third is the one that isn't obvious: the id must be one of
     * ours ([isSafeUploadSessionId]), the directory must exist — and it must be a **real
     * directory, not a symbolic link**. A symlinked session directory would make every later
     * containment check tautological: `resolvedForContainment` would resolve the session root to
     * wherever the link points and then happily confirm that files under it are "inside the
     * session". Refusing the link is what keeps that check meaningful.
     */
    fun openSession(sessionId: String): Path? {
        if (!isSafeUploadSessionId(sessionId)) return null
        val dir = paths.dirFor(sessionId)
        if (isSymlink(dir)) return null
        if (SystemFileSystem.metadataOrNull(dir)?.isDirectory != true) return null
        return dir
    }

    /** What [sessionDir] currently holds. Walks the staged tree — sessions are small and bounded by [limits]. */
    fun stats(sessionDir: Path): UploadSessionStats {
        var files = 0
        var bytes = 0L
        for (file in stagedFiles(sessionDir)) {
            files += 1
            bytes += SystemFileSystem.metadataOrNull(file)?.size ?: 0L
        }
        return UploadSessionStats(fileCount = files, totalBytes = bytes)
    }

    /** Every regular file staged under [sessionDir], recursively — the finalize's walk input and the quota's. */
    fun stagedFiles(sessionDir: Path): List<Path> {
        val out = mutableListOf<Path>()

        fun recurse(dir: Path) {
            for (child in SystemFileSystem.list(dir)) {
                if (SystemFileSystem.metadataOrNull(child)?.isDirectory == true) recurse(child) else out += child
            }
        }
        if (SystemFileSystem.metadataOrNull(sessionDir)?.isDirectory == true) recurse(sessionDir)
        return out
    }

    /**
     * Prepares to receive [target]: creates its parent directories and returns the temporary
     * `.part` path the body streams into.
     *
     * The `.part` indirection is what stops a dropped connection from leaving a **truncated file
     * that looks complete**. Finalize walks the session directory and treats what it finds as the
     * user's book; a half-written `.m4b` sitting there under its real name would be analysed,
     * planned, and moved into the library as if it were whole. A part file is instead invisible to
     * [stagedFiles]'s consumers by name and is swept with the session.
     */
    fun beginFile(target: Path): Path {
        target.parent?.let { SystemFileSystem.createDirectories(it) }
        val part = Path("$target$PART_SUFFIX")
        SystemFileSystem.delete(part, mustExist = false)
        return part
    }

    /** Publishes a fully-received [part] as [target] with a single rename — the file appears complete or not at all. */
    fun commitFile(
        part: Path,
        target: Path,
    ) {
        SystemFileSystem.atomicMove(part, target)
    }

    /** Removes a [part] whose transfer failed. Best effort — the session sweep catches whatever this misses. */
    fun discardFile(part: Path) {
        SystemFileSystem.delete(part, mustExist = false)
    }

    /**
     * Deletes every leftover `.part` file under [sessionDir] and reports how many there were.
     *
     * Finalize calls this first. A part file is by definition an interrupted transfer, and the
     * scanner pipeline has no notion of one — it would group the fragment into the book, move it
     * into the library, and leave a corrupt file sitting beside the real ones. Sweeping them is
     * also the honest signal that the upload was incomplete, which the caller can act on.
     */
    fun sweepPartFiles(sessionDir: Path): Int {
        val parts = stagedFiles(sessionDir).filter { it.name.endsWith(PART_SUFFIX) }
        parts.forEach { SystemFileSystem.delete(it, mustExist = false) }
        return parts.size
    }

    /** Removes [sessionDir] and everything staged in it. Idempotent — a missing directory is a no-op. */
    fun deleteSession(sessionDir: Path) {
        deleteRecursively(sessionDir)
    }

    private companion object {
        /** Suffix marking a transfer still in flight; see [beginFile]. */
        const val PART_SUFFIX = ".listenup-part"
    }
}
