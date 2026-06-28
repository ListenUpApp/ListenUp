package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.ImportRoutePaths
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.imports.ImportStatus
import com.calypsan.listenup.api.dto.imports.ImportSummary
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.ImportError
import com.calypsan.listenup.core.ImportId
import com.calypsan.listenup.server.absimport.AbsSchema
import com.calypsan.listenup.server.absimport.ImportPaths
import com.calypsan.listenup.server.compression.zip.ZipReader
import com.calypsan.listenup.server.io.createTempFileIn
import com.calypsan.listenup.server.io.deleteRecursively
import com.calypsan.listenup.server.io.isUnder
import com.calypsan.listenup.server.io.streamFirstFilePartTo
import com.calypsan.listenup.server.io.writeText
import com.calypsan.listenup.server.plugins.toHttpStatus
import com.calypsan.listenup.server.plugins.userPrincipalOrNull
import com.calypsan.listenup.server.plugins.withCorrelationId
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.callid.callId
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Max accepted size for an uploaded `.audiobookshelf` backup. ABS backups for large libraries
 * routinely reach hundreds of MB; this endpoint is admin-only and streams the file straight to a
 * temp file (never buffered in memory), so a generous cap is safe. Ktor's default `formFieldLimit`
 * is 50 MiB (52_428_800 bytes), which rejected real backups before they could be streamed.
 */
private const val MAX_BACKUP_UPLOAD_BYTES: Long = 5L * 1024 * 1024 * 1024 // 5 GiB

/**
 * REST route for binary Audiobookshelf-backup upload (staging only).
 *
 * Complements the RPC surface ([com.calypsan.listenup.api.ImportService]) — binary transfer
 * requires HTTP multipart rather than kRPC frames. Analyze/confirm/apply are separate RPC calls.
 *
 *  - `POST /api/v1/admin/imports/abs/upload` — streams a `.audiobookshelf` zip, extracts its
 *    `absdatabase.sqlite` into a fresh `imports/<id>/` directory, and returns an
 *    [ImportSummary] (status [ImportStatus.UPLOADED]).
 *
 * Admin-only (ROOT/ADMIN). Mount inside `authenticate(JWT_PROVIDER)`.
 *
 * Security notes:
 *  - The import id is **server-minted** (`abs-<UUID>`), never derived from the client filename —
 *    so it can never contain path separators or traversal sequences.
 *  - The upload is streamed directly to a temp file; the body is never buffered into a [ByteArray]
 *    (ABS backups can be large).
 *  - Zip extraction is **zip-slip-safe**: only the entry named exactly `absdatabase.sqlite` is
 *    extracted, and only to [ImportPaths.absDbFor] — no other entry is written, and nothing is ever
 *    written outside the import's own directory.
 *  - A zip with no `absdatabase.sqlite` is rejected with 422 and the partial import dir is removed.
 *  - The temp upload zip is always cleaned up in a `finally` block.
 */
fun Route.importRoutes(
    paths: ImportPaths,
    clock: Clock = Clock.System,
) {
    post(ImportRoutePaths.ABS_UPLOAD) {
        val p = call.userPrincipalOrNull() ?: return@post call.respond(HttpStatusCode.Unauthorized)
        if (!p.role.isImportAdmin()) return@post call.respondImportAppError(AuthError.PermissionDenied())
        call.handleImportUpload(paths, clock)
    }
}

/**
 * Streams the multipart body to a temp zip, mints a safe import id, extracts the ABS database into
 * a fresh import directory, and responds an [ImportSummary]. The temp zip is always removed in a
 * `finally`; on any failure the partial import directory is removed too.
 */
private suspend fun ApplicationCall.handleImportUpload(
    paths: ImportPaths,
    clock: Clock,
) {
    paths.ensureDirs()
    // Stream the upload to a temp file — never buffer a multi-hundred-MB ABS backup into memory.
    val tmpZip = createTempFileIn(paths.tmpDir, "abs-upload-", ".audiobookshelf")
    try {
        if (!streamFirstFilePartTo(tmpZip, MAX_BACKUP_UPLOAD_BYTES)) {
            respondImportAppError(ImportError.UploadFailed(debugInfo = "missing file part"))
            return
        }

        // Server-minted id — never from the client filename — so it is always a safe path segment.
        val importId = "abs-${Uuid.random()}"
        val importDir = paths.dirFor(importId)
        SystemFileSystem.createDirectories(importDir)
        try {
            extractAbsDatabase(tmpZip, importDir, paths.absDbFor(importId))
            val createdAt = clock.now().toEpochMilliseconds()
            paths.metaFor(importId).writeText(metaJson.encodeToString(UploadMeta(createdAt = createdAt)))
            respond(
                HttpStatusCode.OK,
                ImportSummary(
                    id = ImportId(importId),
                    createdAt = createdAt,
                    status = ImportStatus.UPLOADED,
                    bookCount = 0,
                    userCount = 0,
                ),
            )
        } catch (e: AbsDatabaseMissingException) {
            deleteRecursively(importDir)
            respondImportAppError(ImportError.UploadFailed(debugInfo = e.message))
        } catch (e: CancellationException) {
            deleteRecursively(importDir)
            throw e
        } catch (e: Exception) {
            deleteRecursively(importDir)
            respondImportAppError(ImportError.UploadFailed(debugInfo = e.message))
        }
    } finally {
        SystemFileSystem.delete(tmpZip, mustExist = false)
    }
}

/** Signals that the uploaded `.audiobookshelf` zip contained no `absdatabase.sqlite` entry. */
private class AbsDatabaseMissingException :
    Exception("The uploaded file is not an Audiobookshelf backup (no ${AbsSchema.DB_FILENAME}).")

/**
 * Extracts exactly the `absdatabase.sqlite` entry from [zip] to [dest]. Zip-slip-safe: only the
 * entry whose leaf name matches [AbsSchema.DB_FILENAME] is written, and only to [dest], which is
 * asserted to live under [importDir]. Throws [AbsDatabaseMissingException] when no entry matches.
 */
private fun extractAbsDatabase(
    zip: Path,
    importDir: Path,
    dest: Path,
) {
    // dest is server-derived under importDir; assert the invariant defensively before any write.
    require(dest.isUnder(importDir)) { "extraction target escapes the import directory" }
    val extracted =
        ZipReader(zip).use { reader ->
            // Match on the leaf name (not the full entry path) so arbitrary nested paths are ignored
            // and only [dest] is ever written — the zip-slip guard.
            val entry =
                reader.entries().firstOrNull { e ->
                    !e.name.endsWith('/') && e.name.leafName() == AbsSchema.DB_FILENAME
                }
            if (entry == null) {
                false
            } else {
                reader.openEntry(entry).use { source ->
                    SystemFileSystem.sink(dest).buffered().use { it.transferFrom(source) }
                }
                true
            }
        }
    if (!extracted) throw AbsDatabaseMissingException()
}

/** The final path segment of a zip entry name (slash- or backslash-separated). */
private fun String.leafName(): String = substringAfterLast('/').substringAfterLast('\\')

private val metaJson = Json { ignoreUnknownKeys = true }

@Serializable
@SerialName("UploadMeta")
private data class UploadMeta(
    val createdAt: Long,
)

private fun UserRole.isImportAdmin(): Boolean = this == UserRole.ROOT || this == UserRole.ADMIN

private suspend fun ApplicationCall.respondImportAppError(error: AppError) {
    val typed = error.withCorrelationId(callId)
    respond(typed.toHttpStatus(), typed)
}
