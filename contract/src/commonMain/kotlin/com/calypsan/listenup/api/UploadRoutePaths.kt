@file:OptIn(ExperimentalObjCRefinement::class)

package com.calypsan.listenup.api

import kotlin.experimental.ExperimentalObjCRefinement
import kotlin.native.HiddenFromObjC

/**
 * Canonical REST paths for the admin book-upload surface.
 *
 * Uploading books is the one library-write operation that cannot ride RPC — audio files are
 * multi-GiB binary and do not belong in JSON-RPC frames — so the paths are hand-rolled on both
 * the server routes and the client uploader. Defining them once here makes client/server drift a
 * compile-time concern rather than a runtime 404. Everything else about an upload (progress,
 * results) rides the same request/response bodies.
 *
 * An upload is a **session**: [SESSIONS] mints one, [file] streams a single file into it (one
 * request per file, each carrying the file's path relative to what the user selected), and
 * [finalize] ingests the whole staged tree in one go. One request per file rather than one
 * N-part request is deliberate: it reuses the proven single-file multipart path on both the JVM
 * and Kotlin/Native runtimes, and per-file retry falls out for free.
 */
@HiddenFromObjC
object UploadRoutePaths {
    /** `POST` — mints a new upload session; responds an [com.calypsan.listenup.api.dto.uploads.UploadSessionSummary]. */
    const val SESSIONS: String = "/api/v1/admin/uploads"

    /** `POST` (Ktor template) — streams one file into a session; `relPath` rides the query string. */
    const val FILE_TEMPLATE: String = "/api/v1/admin/uploads/{sessionId}/file"

    /** `POST` (Ktor template) — ingests the staged tree; responds an [com.calypsan.listenup.api.dto.uploads.UploadFinalizeResult]. */
    const val FINALIZE_TEMPLATE: String = "/api/v1/admin/uploads/{sessionId}/finalize"

    /** `DELETE` (Ktor template) — abandons a session and removes its staging directory. */
    const val SESSION_TEMPLATE: String = "/api/v1/admin/uploads/{sessionId}"

    /** The query-parameter name carrying a file's path relative to the user's selection root. */
    const val REL_PATH_PARAM: String = "relPath"

    /** The path segment naming an upload session, as it appears in [FILE_TEMPLATE] and friends. */
    const val SESSION_ID_PARAM: String = "sessionId"

    /** Concrete file-upload path for [sessionId]. The caller URL-encodes `relPath` into the query string. */
    fun file(sessionId: String): String = "$SESSIONS/$sessionId/file"

    /** Concrete finalize path for [sessionId]. */
    fun finalize(sessionId: String): String = "$SESSIONS/$sessionId/finalize"

    /** Concrete abandon path for [sessionId]. */
    fun session(sessionId: String): String = "$SESSIONS/$sessionId"
}
