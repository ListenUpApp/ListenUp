package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.backup.BackupSummary
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.BackupError
import com.calypsan.listenup.core.BackupId
import com.calypsan.listenup.server.backup.BackupArchive
import com.calypsan.listenup.server.backup.BackupManifest
import com.calypsan.listenup.server.backup.BackupPaths
import com.calypsan.listenup.server.plugins.toHttpStatus
import com.calypsan.listenup.server.plugins.userPrincipalOrNull
import com.calypsan.listenup.server.plugins.withCorrelationId
import io.ktor.http.ContentDisposition
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.callid.callId
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.jvm.javaio.copyTo
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException

/**
 * REST routes for binary backup download and cross-machine upload (staging).
 *
 * These complement the RPC surface ([com.calypsan.listenup.api.BackupService]) — binary
 * transfer requires HTTP multipart/file-download rather than kRPC frames.
 *
 *  - `GET  /api/v1/admin/backups/{id}/download` — streams the `.listenup.zip` as an attachment.
 *  - `POST /api/v1/admin/backups/upload`        — stages a foreign backup into [BackupPaths.backupsDir]
 *    so it can be listed by [com.calypsan.listenup.api.BackupService.listBackups] and
 *    restored by [com.calypsan.listenup.api.BackupService.restoreBackup].
 *
 * Both routes are admin-only (ROOT/ADMIN). Mount inside `authenticate(JWT_PROVIDER)`.
 *
 * Security notes:
 *  - The backup id in the download route is sanitized: path separators and `..` are rejected
 *    outright; the file is resolved via [BackupPaths.archiveFor] (which resolves under
 *    [BackupPaths.backupsDir]) and existence is checked before responding.
 *  - The uploaded archive is validated with [BackupArchive.validate] before being moved into
 *    [BackupPaths.backupsDir]; a corrupt or incompatible archive never lands in the restore list.
 *  - The id for the uploaded archive is derived from the manifest's [BackupManifest.createdAt]
 *    timestamp — never from a client-supplied filename — preventing path traversal.
 *  - Upload is streamed directly to a temp file; the body is never buffered into a [ByteArray].
 *  - Download uses Ktor's [respondFile] (file-channel streaming), not `Files.readAllBytes`.
 */
fun Route.backupRoutes(
    paths: BackupPaths,
    archive: BackupArchive,
) {
    get("/api/v1/admin/backups/{id}/download") {
        val p = call.userPrincipalOrNull() ?: return@get call.respond(HttpStatusCode.Unauthorized)
        if (!p.role.isAdmin()) return@get call.respondBareAppError(AuthError.PermissionDenied())

        val rawId = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        // Reject ids containing path separators or traversal sequences before doing any I/O.
        if (!rawId.isSafeBackupId()) {
            return@get call.respond(HttpStatusCode.BadRequest, "invalid backup id")
        }

        val archivePath = paths.archiveFor(rawId)
        if (!Files.isRegularFile(archivePath)) {
            return@get call.respondBareAppError(BackupError.BackupNotFound())
        }

        call.response.header(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment
                .withParameter(ContentDisposition.Parameters.FileName, "$rawId.listenup.zip")
                .toString(),
        )
        call.respondFile(archivePath.toFile())
    }

    post("/api/v1/admin/backups/upload") {
        val p = call.userPrincipalOrNull() ?: return@post call.respond(HttpStatusCode.Unauthorized)
        if (!p.role.isAdmin()) return@post call.respondBareAppError(AuthError.PermissionDenied())
        call.handleUpload(paths, archive)
    }
}

/**
 * Streams the multipart body to a temp file, validates it, then moves it into [BackupPaths.backupsDir].
 * The temp file is always cleaned up in a `finally` block; on success it has already been moved so
 * [Files.deleteIfExists] is a no-op.
 */
private suspend fun ApplicationCall.handleUpload(
    paths: BackupPaths,
    archive: BackupArchive,
) {
    paths.ensureDirs()
    // Stream the upload to a temp file — never buffer multi-hundred-MB backups into memory.
    val tmpFile = Files.createTempFile(paths.tmpDir, "upload-", ".listenup.zip")
    try {
        var received = false
        receiveMultipart().forEachPart { part ->
            if (part is PartData.FileItem && !received) {
                received = true
                Files.newOutputStream(tmpFile).use { out ->
                    part.provider().copyTo(out)
                }
            }
            part.release()
        }

        if (!received) {
            respond(HttpStatusCode.BadRequest, "missing file part")
            return
        }

        // Validate before staging — a corrupt archive must never land in backupsDir.
        val manifest = validateUpload(archive, tmpFile) ?: return

        // Derive a filesystem-safe id from the manifest timestamp — never from the
        // client-supplied filename — to prevent path traversal.
        val safeId = deriveSafeId(manifest)
        val dest = paths.archiveFor(safeId)
        Files.move(tmpFile, dest, StandardCopyOption.REPLACE_EXISTING)

        val summary =
            BackupSummary(
                id = BackupId(safeId),
                createdAt = manifest.createdAt,
                sizeBytes = Files.size(dest),
                includesImages = manifest.includesImages,
                schemaVersion = manifest.schemaVersion,
                appVersion = manifest.appVersion,
                bookCount = manifest.bookCount,
                userCount = manifest.userCount,
            )
        respond(HttpStatusCode.OK, summary)
    } finally {
        // Clean up the temp file on any failure path; on success it has already been
        // moved to dest so deleteIfExists is a no-op there.
        Files.deleteIfExists(tmpFile)
    }
}

/**
 * Validates the archive at [tmpFile] using [BackupArchive.validate]. Returns the [BackupManifest]
 * on success, or responds with a typed [BackupError.CorruptArchive] and returns null on failure.
 * [CancellationException] is always re-thrown so structured concurrency is preserved.
 */
private suspend fun ApplicationCall.validateUpload(
    archive: BackupArchive,
    tmpFile: java.nio.file.Path,
): BackupManifest? =
    try {
        archive.validate(tmpFile)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        respondBareAppError(BackupError.CorruptArchive(debugInfo = e.message))
        null
    }

/**
 * Returns true if [this] string is safe to use as a backup archive filename stem: no path
 * separators, no `..` traversal sequences, and non-blank. This is checked before any file
 * I/O to rule out path-traversal attacks at the earliest possible point.
 */
private fun String.isSafeBackupId(): Boolean {
    if (isBlank()) return false
    if (contains('/') || contains('\\')) return false
    if (contains("..")) return false
    return true
}

/**
 * Derives a filesystem-safe backup id from the manifest's [BackupManifest.createdAt] epoch-
 * millisecond timestamp, matching the format [com.calypsan.listenup.server.api.BackupServiceImpl]
 * uses for locally-created backups: `backup-<ISO-8601-with-colons-as-dashes>`.
 *
 * Using the manifest timestamp (not the client-supplied filename) prevents path traversal.
 */
private fun deriveSafeId(manifest: BackupManifest): String {
    val iso =
        java.time.Instant
            .ofEpochMilli(manifest.createdAt)
            .toString()
    // ':' is invalid in filenames on some filesystems; replace to match local backup id format.
    return "backup-${iso.replace(':', '-')}"
}

private fun UserRole.isAdmin(): Boolean = this == UserRole.ROOT || this == UserRole.ADMIN

private suspend fun ApplicationCall.respondBareAppError(error: AppError) {
    val typed = error.withCorrelationId(callId)
    respond(typed.toHttpStatus(), typed)
}
