package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.BackupRoutePaths
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.backup.BackupSummary
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.BackupError
import com.calypsan.listenup.core.BackupId
import com.calypsan.listenup.server.backup.BackupArchive
import com.calypsan.listenup.server.backup.BackupManifest
import com.calypsan.listenup.server.backup.BackupPaths
import com.calypsan.listenup.server.backup.isSafeBackupId
import com.calypsan.listenup.server.io.createTempFileIn
import com.calypsan.listenup.server.io.respondSeekable
import com.calypsan.listenup.server.io.streamFirstFilePartTo
import com.calypsan.listenup.server.plugins.toHttpStatus
import com.calypsan.listenup.server.plugins.userPrincipalOrNull
import com.calypsan.listenup.server.plugins.withCorrelationId
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.callid.callId
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.time.Instant

/**
 * Max accepted size for an uploaded `.listenup.zip` backup. Legitimate backups for large libraries
 * can exceed 50 MiB; this route streams the file straight to a temp file (never buffered in
 * memory), so a generous cap is safe. Ktor's default `formFieldLimit` is 50 MiB (52_428_800 bytes),
 * which would reject valid large restores before they could be staged. Mirrors [ImportRoutes]'
 * cap for consistency — both upload endpoints accept the same ceiling.
 */
private const val MAX_BACKUP_RESTORE_BYTES: Long = 5L * 1024 * 1024 * 1024 // 5 GiB

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
 *  - Download uses [respondSeekable] (file-channel streaming), not a full read into memory.
 */
fun Route.backupRoutes(
    paths: BackupPaths,
    archive: BackupArchive,
) {
    get(BackupRoutePaths.DOWNLOAD_TEMPLATE) {
        val p = call.userPrincipalOrNull() ?: return@get call.respond(HttpStatusCode.Unauthorized)
        if (!p.role.isAdmin()) return@get call.respondBareAppError(AuthError.PermissionDenied())

        val rawId = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        // Reject ids containing path separators or traversal sequences before doing any I/O.
        if (!isSafeBackupId(rawId)) {
            return@get call.respond(HttpStatusCode.BadRequest, "invalid backup id")
        }

        val archivePath = paths.archiveFor(rawId)
        if (SystemFileSystem.metadataOrNull(archivePath)?.isRegularFile != true) {
            return@get call.respondBareAppError(BackupError.BackupNotFound())
        }

        call.response.header(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment
                .withParameter(ContentDisposition.Parameters.FileName, "$rawId.listenup.zip")
                .toString(),
        )
        call.respondSeekable(archivePath, ContentType.Application.Zip)
    }

    post(BackupRoutePaths.UPLOAD) {
        val p = call.userPrincipalOrNull() ?: return@post call.respond(HttpStatusCode.Unauthorized)
        if (!p.role.isAdmin()) return@post call.respondBareAppError(AuthError.PermissionDenied())
        call.handleUpload(paths, archive)
    }
}

/**
 * Streams the multipart body to a temp file, validates it, then moves it into [BackupPaths.backupsDir].
 * The temp file is always cleaned up in a `finally` block; on success it has already been moved so
 * the delete is a no-op.
 */
private suspend fun ApplicationCall.handleUpload(
    paths: BackupPaths,
    archive: BackupArchive,
) {
    paths.ensureDirs()
    // Stream the upload to a temp file — never buffer multi-hundred-MB backups into memory.
    val tmpFile = createTempFileIn(paths.tmpDir, "upload-", ".listenup.zip")
    try {
        if (!streamFirstFilePartTo(tmpFile, MAX_BACKUP_RESTORE_BYTES)) {
            respond(HttpStatusCode.BadRequest, "missing file part")
            return
        }

        // Validate before staging — a corrupt archive must never land in backupsDir.
        val manifest = validateUpload(archive, tmpFile) ?: return

        // Derive a filesystem-safe id from the manifest timestamp — never from the
        // client-supplied filename — to prevent path traversal.
        val safeId = deriveSafeId(manifest)
        val destPath = paths.archiveFor(safeId)
        // Mirror REPLACE_EXISTING: drop any prior archive with this id before the atomic rename.
        SystemFileSystem.delete(destPath, mustExist = false)
        SystemFileSystem.atomicMove(tmpFile, destPath)

        val summary =
            BackupSummary(
                id = BackupId(safeId),
                createdAt = manifest.createdAt,
                sizeBytes = SystemFileSystem.metadataOrNull(destPath)?.size ?: 0L,
                includesImages = manifest.includesImages,
                schemaVersion = manifest.schemaVersion,
                appVersion = manifest.appVersion,
                bookCount = manifest.bookCount,
                userCount = manifest.userCount,
            )
        respond(HttpStatusCode.OK, summary)
    } finally {
        // Clean up the temp file on any failure path; on success it has already been
        // moved to dest so the delete is a no-op there.
        SystemFileSystem.delete(tmpFile, mustExist = false)
    }
}

/**
 * Validates the archive at [tmpFile] using [BackupArchive.validate]. Returns the [BackupManifest]
 * on success, or responds with a typed [BackupError.CorruptArchive] and returns null on failure.
 * [CancellationException] is always re-thrown so structured concurrency is preserved.
 */
private suspend fun ApplicationCall.validateUpload(
    archive: BackupArchive,
    tmpFile: Path,
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
 * Derives a filesystem-safe backup id from the manifest's [BackupManifest.createdAt] epoch-
 * millisecond timestamp, matching the format [com.calypsan.listenup.server.api.BackupServiceImpl]
 * uses for locally-created backups: `backup-<ISO-8601-with-colons-as-dashes>`.
 *
 * Using the manifest timestamp (not the client-supplied filename) prevents path traversal.
 */
private fun deriveSafeId(manifest: BackupManifest): String {
    val iso = Instant.fromEpochMilliseconds(manifest.createdAt).toString()
    // ':' is invalid in filenames on some filesystems; replace to match local backup id format.
    return "backup-${iso.replace(':', '-')}"
}

private fun UserRole.isAdmin(): Boolean = this == UserRole.ROOT || this == UserRole.ADMIN

private suspend fun ApplicationCall.respondBareAppError(error: AppError) {
    val typed = error.withCorrelationId(callId)
    respond(typed.toHttpStatus(), typed)
}
