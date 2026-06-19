package com.calypsan.listenup.server.absimport

import java.nio.file.Files
import java.nio.file.Path

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
    val importsDir: Path get() = homeDir.resolve("imports")

    /** Scratch space for in-progress upload streaming (inside [importsDir] to keep renames cheap). */
    val tmpDir: Path get() = importsDir.resolve(".tmp")

    /** The working directory for the import with the given [id]. */
    fun dirFor(id: String): Path = importsDir.resolve(id)

    /** The extracted Audiobookshelf SQLite database for the import [id]. */
    fun absDbFor(id: String): Path = dirFor(id).resolve(AbsSchema.DB_FILENAME)

    /** The persisted analysis preview (`analysis.json`) for the import [id]. */
    fun analysisFor(id: String): Path = dirFor(id).resolve("analysis.json")

    /** The server-internal resolved matches (`matches.json`) for the import [id]. */
    fun matchesFor(id: String): Path = dirFor(id).resolve("matches.json")

    /** The persisted confirmed mapping (`mapping.json`) for the import [id]. */
    fun mappingFor(id: String): Path = dirFor(id).resolve("mapping.json")

    /** The marker file written once apply has completed for the import [id]. */
    fun appliedMarkerFor(id: String): Path = dirFor(id).resolve(".applied")

    /** The small upload-time metadata sidecar (`meta.json`) for the import [id]. */
    fun metaFor(id: String): Path = dirFor(id).resolve("meta.json")

    /** Creates [importsDir] and [tmpDir] if they do not already exist. */
    fun ensureDirs() {
        listOf(importsDir, tmpDir).forEach { Files.createDirectories(it) }
    }
}
