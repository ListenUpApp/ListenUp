package com.calypsan.listenup.server.backup

import com.calypsan.listenup.server.io.fileIoDispatcher
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * All filesystem locations the backup/restore domain uses, rooted at `$LISTENUP_HOME`.
 *
 * `homeDir` is the same data-home directory that holds the live SQLite database
 * (e.g. `~/ListenUp` by default, or `$LISTENUP_HOME` when the environment variable
 * is set). The database filename `listenup.db` matches the value of
 * `SQLITE_DB_FILENAME` in `DataHome.kt`.
 */
class BackupPaths(
    private val homeDir: Path,
) {
    /** Directory where finished `.listenup.zip` archives are stored. */
    val backupsDir: Path get() = Path(homeDir, "backups")

    /** Scratch space for in-progress archive creation (inside `backupsDir` to keep renames atomic). */
    val tmpDir: Path get() = Path(backupsDir, ".tmp")

    /** Safety-copy location: the pre-swap snapshot written before a restore begins. */
    val rollbackDir: Path get() = Path(homeDir, "restore-rollback")

    /** Staging area where an incoming archive is extracted before being swapped in. */
    val stagingDir: Path get() = Path(homeDir, "restore-staging")

    /** The live SQLite database file. Matches `SQLITE_DB_FILENAME = "listenup.db"` from `DataHome.kt`. */
    val dbFile: Path get() = Path(homeDir, "listenup.db")

    /** Directory containing book cover images managed by the server. */
    val coversDir: Path get() = Path(homeDir, "covers")

    /** Directory containing user avatar images managed by the server. */
    val avatarsDir: Path get() = Path(homeDir, "avatars")

    /** Returns the archive path for a backup with the given [id] stem. */
    fun archiveFor(id: String): Path = Path(backupsDir, "$id.listenup.zip")

    /**
     * Creates [backupsDir] and [tmpDir] if they do not already exist.
     * Other directories ([rollbackDir], [stagingDir]) are created on-demand by the orchestrator.
     */
    suspend fun ensureDirs() {
        withContext(fileIoDispatcher) {
            listOf(backupsDir, tmpDir).forEach { SystemFileSystem.createDirectories(it) }
        }
    }
}

/**
 * True when [id] is safe to use as a backup archive filename stem: non-blank, and free of
 * path separators (`/`, `\`) and `..` traversal sequences. Checked before any filesystem
 * access so a caller-supplied backup id cannot escape the backups directory. Shared by the
 * REST download route and the RPC [com.calypsan.listenup.server.api.BackupServiceImpl] methods
 * so both surfaces reject identical input.
 */
fun isSafeBackupId(id: String): Boolean {
    if (id.isBlank()) return false
    if (id.contains('/') || id.contains('\\')) return false
    if (id.contains("..")) return false
    return true
}
