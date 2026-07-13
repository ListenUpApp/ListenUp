package com.calypsan.listenup.server.absimport

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * True when [id] is safe to use as an import directory-name segment: non-blank, and free of path
 * separators (`/`, `\`) and `..` traversal sequences. Server-minted import ids are always safe, but
 * the RPC surface accepts a client-supplied [com.calypsan.listenup.core.ImportId], so every
 * id-taking [com.calypsan.listenup.server.api.ImportServiceImpl] method validates before any
 * filesystem access — mirroring [com.calypsan.listenup.server.backup.isSafeBackupId].
 */
fun isSafeImportId(id: String): Boolean {
    if (id.isBlank()) return false
    if (id.contains('/') || id.contains('\\')) return false
    if (id.contains("..")) return false
    return true
}

/**
 * All filesystem locations the ABS-import domain uses, rooted at `$LISTENUP_HOME/imports/`.
 *
 * Import jobs are **filesystem-truth**: there is no database table. Each staged import lives in
 * its own directory `imports/<id>/`, and the presence of specific files inside it
 * ([analysisFor], [mappingFor], [appliedMarkerFor]) is what derives the job's
 * [com.calypsan.listenup.api.dto.imports.ImportStatus]. The id is a server-minted directory name
 * (never a client-supplied filename), so it is safe to use directly as a path segment.
 *
 * `homeDir` is the same data-home directory that holds the live SQLite database
 * (e.g. `~/ListenUp` by default, or `$LISTENUP_HOME` when the environment variable is set).
 */
class ImportPaths(
    private val homeDir: Path,
) {
    /** Root directory holding one subdirectory per staged import job. */
    val importsDir: Path get() = Path(homeDir, "imports")

    /** Scratch space for in-progress upload streaming (inside [importsDir] to keep renames cheap). */
    val tmpDir: Path get() = Path(importsDir, ".tmp")

    /** The working directory for the import with the given [id]. */
    fun dirFor(id: String): Path = Path(importsDir, id)

    /** The extracted Audiobookshelf SQLite database for the import [id]. */
    fun absDbFor(id: String): Path = Path(dirFor(id), AbsSchema.DB_FILENAME)

    /** The persisted analysis preview (`analysis.json`) for the import [id]. */
    fun analysisFor(id: String): Path = Path(dirFor(id), "analysis.json")

    /** The server-internal resolved matches (`matches.json`) for the import [id]. */
    fun matchesFor(id: String): Path = Path(dirFor(id), "matches.json")

    /** The persisted confirmed mapping (`mapping.json`) for the import [id]. */
    fun mappingFor(id: String): Path = Path(dirFor(id), "mapping.json")

    /** The marker file written once apply has completed for the import [id]. */
    fun appliedMarkerFor(id: String): Path = Path(dirFor(id), ".applied")

    /** Marker touched when apply starts writing; cleared by a successful apply (see ImportStore). */
    fun applyingMarkerFor(id: String): Path = Path(dirFor(id), ".applying")

    /** The small upload-time metadata sidecar (`meta.json`) for the import [id]. */
    fun metaFor(id: String): Path = Path(dirFor(id), "meta.json")

    /** Creates [importsDir] and [tmpDir] if they do not already exist. */
    fun ensureDirs() {
        listOf(importsDir, tmpDir).forEach { SystemFileSystem.createDirectories(it) }
    }
}
