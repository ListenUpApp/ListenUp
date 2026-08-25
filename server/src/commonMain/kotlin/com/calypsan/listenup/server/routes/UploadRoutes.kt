package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.UploadRoutePaths
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.uploads.UploadSessionSummary
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.UploadError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.io.streamFirstFilePartTo
import com.calypsan.listenup.server.logging.loggerFor
import com.calypsan.listenup.server.plugins.respondAppError
import com.calypsan.listenup.server.plugins.toHttpStatus
import com.calypsan.listenup.server.plugins.userPrincipalOrNull
import com.calypsan.listenup.server.plugins.withCorrelationId
import com.calypsan.listenup.server.upload.UploadFinalizer
import com.calypsan.listenup.server.upload.UploadStaging
import com.calypsan.listenup.server.upload.UploadTarget
import com.calypsan.listenup.server.upload.resolveUploadTarget
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.callid.callId
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Naming anchor for this file's logger. Routes are top-level extension functions with no class to
 * name, and [com.calypsan.listenup.server.logging.loggerFor] takes its tag from a reified type so
 * the name survives native symbol stripping.
 */
private object UploadRouteLog

private val logger = loggerFor<UploadRouteLog>()

/**
 * REST routes for admin book uploads — the one library-write path that cannot ride RPC, because
 * audio files are multi-GiB binary and do not belong in JSON-RPC frames.
 *
 * An upload is a **session**, not a request:
 *
 *  - `POST /api/v1/admin/uploads` — mints a session and its staging directory.
 *  - `POST /api/v1/admin/uploads/{id}/file?relPath=…` — streams **one** file into it.
 *  - `POST /api/v1/admin/uploads/{id}/finalize` — ingests the staged tree.
 *  - `DELETE /api/v1/admin/uploads/{id}` — abandons it and sweeps the staging directory.
 *
 * One request per file rather than one N-part request is deliberate. The existing
 * [streamFirstFilePartTo] handles exactly one file part and is proven on both runtimes — the
 * Kotlin/Native server cannot use Ktor's multipart transform at all (KTOR-7361) and decodes the
 * wire format by hand, so extending it to N parts would mean hardening hand-written wire parsing
 * on the production path. A session gets the same result with no decoder work, and per-file
 * progress and retry fall out for free.
 *
 * Admin-only (ROOT/ADMIN), mirroring the ABS-import upload. That is not merely a simplification:
 * an upload writes inside a library folder and derives its destination from untrusted metadata.
 * Prove it for one trusted role before widening; widening later is a policy change, getting the
 * write path wrong is data loss.
 *
 * Security notes:
 *  - The session id is **server-minted** (`up-<UUID>`) and re-validated on every request, so it
 *    can never contain a path separator or a traversal sequence.
 *  - `relPath` is **attacker-controlled** and is validated by [resolveUploadTarget] *before any
 *    file is opened* — absolute paths, `..` in any spelling, backslash separators, empty segments
 *    and anything that does not resolve strictly inside the session directory are refused. The
 *    broker's containment check at the far end is a backstop for a bug here, not the first line.
 *  - Every upload is streamed to disk chunk by chunk; a body is never buffered into a `ByteArray`.
 *  - A file arrives as a `.part` and is renamed into place only once complete, so an interrupted
 *    transfer can never be mistaken for a whole audio file at ingest time.
 *  - Sessions are capped in both file count and total bytes; a session that exceeds either is
 *    refused and swept rather than left half-staged.
 */
internal fun Route.uploadRoutes(
    staging: UploadStaging,
    finalizer: UploadFinalizer,
) {
    post(UploadRoutePaths.SESSIONS) {
        if (!call.requireUploadAdmin()) return@post
        val sessionId = staging.createSession()
        logger.info { "upload session created: $sessionId" }
        call.respond(HttpStatusCode.OK, UploadSessionSummary(sessionId = sessionId, fileCount = 0, totalBytes = 0L))
    }

    post(UploadRoutePaths.FILE_TEMPLATE) {
        if (!call.requireUploadAdmin()) return@post
        call.receiveOneFile(staging)
    }

    post(UploadRoutePaths.FINALIZE_TEMPLATE) {
        if (!call.requireUploadAdmin()) return@post
        val sessionId = call.sessionIdOrNull() ?: return@post call.respondAppError(UploadError.SessionNotFound())
        val sessionDir =
            staging.openSession(sessionId)
                ?: return@post call.respondAppError(UploadError.SessionNotFound())
        when (val result = finalizer.finalize(sessionId, sessionDir)) {
            is AppResult.Success -> call.respond(HttpStatusCode.OK, result.data)
            is AppResult.Failure -> call.respondAppError(result.error)
        }
    }

    delete(UploadRoutePaths.SESSION_TEMPLATE) {
        if (!call.requireUploadAdmin()) return@delete
        val sessionId = call.sessionIdOrNull() ?: return@delete call.respondAppError(UploadError.SessionNotFound())
        val sessionDir =
            staging.openSession(sessionId)
                ?: return@delete call.respondAppError(UploadError.SessionNotFound())
        staging.deleteSession(sessionDir)
        logger.info { "upload session abandoned: $sessionId" }
        call.respond(HttpStatusCode.NoContent)
    }
}

/**
 * Validates the session and the client's `relPath`, streams one file into staging, and responds
 * the session's updated totals.
 *
 * Order matters and is the security-relevant part: the session is resolved, the quota is checked,
 * and the path is validated — all before a single byte of the body is read. Nothing that arrives
 * on the wire can influence where it lands.
 */
private suspend fun ApplicationCall.receiveOneFile(staging: UploadStaging) {
    val sessionId = sessionIdOrNull() ?: return respondAppError(UploadError.SessionNotFound())
    val sessionDir = staging.openSession(sessionId) ?: return respondAppError(UploadError.SessionNotFound())

    val before = staging.stats(sessionDir)
    val remaining = staging.limits.maxSessionBytes - before.totalBytes
    if (before.fileCount >= staging.limits.maxFiles || remaining <= 0) {
        staging.deleteSession(sessionDir)
        return respondAppError(
            UploadError.SessionTooLarge(
                debugInfo = "session $sessionId at ${before.fileCount} files / ${before.totalBytes} bytes",
            ),
        )
    }

    val rawRelPath = request.queryParameters[UploadRoutePaths.REL_PATH_PARAM]
    if (rawRelPath == null) {
        return respondAppError(UploadError.InvalidFilePath(debugInfo = "missing relPath query parameter"))
    }
    val target =
        when (val resolved = resolveUploadTarget(sessionDir, rawRelPath)) {
            is UploadTarget.Refused -> {
                logger.warn { "upload $sessionId: refused a file path — ${resolved.reason}" }
                return respondAppError(UploadError.InvalidFilePath(debugInfo = resolved.reason))
            }

            is UploadTarget.Accepted -> {
                resolved.path
            }
        }

    val allowance = minOf(staging.limits.maxFileBytes, remaining)
    val part = staging.beginFile(target)
    val received =
        try {
            streamFirstFilePartTo(part, allowance)
        } catch (e: CancellationException) {
            staging.discardFile(part)
            throw e
        } catch (e: Exception) {
            return failPartialTransfer(staging, sessionId, part, allowance, e)
        }
    if (!received) {
        staging.discardFile(part)
        return respondAppError(UploadError.FileTransferFailed(debugInfo = "request carried no file part"))
    }

    staging.commitFile(part, target)
    val after = staging.stats(sessionDir)
    respond(
        HttpStatusCode.OK,
        UploadSessionSummary(sessionId = sessionId, fileCount = after.fileCount, totalBytes = after.totalBytes),
    )
}

/**
 * Cleans up after a transfer that threw, and reports which of the two things went wrong.
 *
 * The runtimes disagree on how an over-limit part surfaces — the native decoder raises a typed
 * [com.calypsan.listenup.server.io.MultipartPartTooLargeException], while Ktor's JVM multipart
 * transform raises its own `IOException` — so the size of what actually landed is the honest
 * discriminator, and it works identically on both.
 */
private suspend fun ApplicationCall.failPartialTransfer(
    staging: UploadStaging,
    sessionId: String,
    part: Path,
    allowance: Long,
    cause: Exception,
) {
    val landed = SystemFileSystem.metadataOrNull(part)?.size ?: 0L
    staging.discardFile(part)
    logger.warn(cause) { "upload $sessionId: file transfer failed after $landed bytes (allowance $allowance)" }
    if (landed >= allowance) {
        respondAppError(UploadError.SessionTooLarge(debugInfo = "file exceeded the $allowance-byte allowance"))
    } else {
        respondAppError(UploadError.FileTransferFailed(debugInfo = cause.message))
    }
}

/** The `{sessionId}` path segment, or null when it is absent. */
private fun ApplicationCall.sessionIdOrNull(): String? = parameters[UploadRoutePaths.SESSION_ID_PARAM]

/**
 * Responds 401/403 and returns false when the caller may not upload; returns true when they may.
 *
 * Mirrors the ABS-import gate: ROOT and ADMIN only.
 */
private suspend fun ApplicationCall.requireUploadAdmin(): Boolean {
    val principal = userPrincipalOrNull()
    if (principal == null) {
        respond(HttpStatusCode.Unauthorized)
        return false
    }
    if (!principal.role.isUploadAdmin()) {
        respondAppError(AuthError.PermissionDenied())
        return false
    }
    return true
}

private fun UserRole.isUploadAdmin(): Boolean = this == UserRole.ROOT || this == UserRole.ADMIN
