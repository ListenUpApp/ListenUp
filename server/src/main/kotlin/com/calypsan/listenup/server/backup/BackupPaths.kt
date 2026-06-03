package com.calypsan.listenup.server.backup

import java.nio.file.Files
import java.nio.file.Path

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
    val backupsDir: Path get() = homeDir.resolve("backups")

    /** Scratch space for in-progress archive creation (inside `backupsDir` to keep renames atomic). */
    val tmpDir: Path get() = backupsDir.resolve(".tmp")

    /** Safety-copy location: the pre-swap snapshot written before a restore begins. */
    val rollbackDir: Path get() = homeDir.resolve("restore-rollback")

    /** Staging area where an incoming archive is extracted before being swapped in. */
    val stagingDir: Path get() = homeDir.resolve("restore-staging")

    /**
     * The live SQLite database file.
     *
     * Matches `SQLITE_DB_FILENAME = "listenup.db"` from `DataHome.kt`.
     * At Koin wiring time (Task 6) this is cross-checked against
     * `DatabaseHandle.dbFilePath` so the two stay in sync.
     */
    val dbFile: Path get() = homeDir.resolve("listenup.db")

    /** Directory containing book cover images managed by the server. */
    val coversDir: Path get() = homeDir.resolve("covers")

    /** Directory containing user avatar images managed by the server. */
    val avatarsDir: Path get() = homeDir.resolve("avatars")

    /** Returns the archive path for a backup with the given [id] stem. */
    fun archiveFor(id: String): Path = backupsDir.resolve("$id.listenup.zip")

    /**
     * Creates [backupsDir] and [tmpDir] if they do not already exist.
     * Other directories ([rollbackDir], [stagingDir]) are created on-demand by the orchestrator.
     */
    fun ensureDirs() {
        listOf(backupsDir, tmpDir).forEach { Files.createDirectories(it) }
    }
}
