package com.calypsan.listenup.server.routes

import com.calypsan.listenup.server.api.BookAccessPolicy
import com.calypsan.listenup.server.audio.AudioFileLocation
import com.calypsan.listenup.server.audio.AudioFileLocator
import com.calypsan.listenup.server.audio.AudioUrlSigner
import com.calypsan.listenup.server.auth.UserRoleLookup
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * Streaming audio route. NOT JWT-gated — the URL signature IS the auth.
 *
 * Native players cannot reliably set an Authorization header on a media URL,
 * so per-file time-boxed HMAC signatures replace bearer auth. The caller mints
 * a signed query string via `AudioUrlSigner.signedQuery` and embeds it in the
 * media URL; this route validates it on every request.
 *
 * [PartialContent] + [AutoHeadResponse] (installed at Application level) give
 * byte-range streaming and HEAD-for-GET for free — no additional handling needed
 * here; `respondFile` cooperates with both plugins automatically.
 *
 * The signature proves *who* the caller is, not their *role*, so after it
 * verifies the route resolves the caller's role and gates through
 * [BookAccessPolicy]. A book the caller cannot reach answers 404 — never 403 —
 * so the response can't be used to probe a private book's existence (consistent
 * with `BookService.getBook`). A forged or missing signature is the distinct
 * auth failure and still answers 403.
 */
internal fun Route.audioRoutes(
    locator: AudioFileLocator,
    signer: AudioUrlSigner,
    roleLookup: UserRoleLookup,
    accessPolicy: BookAccessPolicy,
) {
    get("/api/v1/audio/{bookId}/{fileId}") {
        val bookId = call.parameters["bookId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val fileId = call.parameters["fileId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val exp = call.request.queryParameters["exp"]?.toLongOrNull()
        val sig = call.request.queryParameters["sig"]
        val userId = call.request.queryParameters["u"]
        if (exp == null || sig == null || userId == null ||
            !signer.verify(userId, bookId, fileId, exp, sig)
        ) {
            return@get call.respond(HttpStatusCode.Forbidden)
        }
        // Resolve role → access → file location in sequence. Each call is already a
        // self-contained suspend function that manages its own DB access. Every failure
        // resolves to null → 404, so a private/unknown book is indistinguishable (no
        // existence probe).
        val location: AudioFileLocation? =
            run {
                val role = roleLookup.roleOf(userId) ?: return@run null
                if (!accessPolicy.canAccess(userId, role, bookId)) return@run null
                locator.locate(bookId, fileId)
            }
        if (location == null) return@get call.respond(HttpStatusCode.NotFound)
        val file = java.io.File(location.path.toString())
        if (!file.isFile) return@get call.respond(HttpStatusCode.NotFound)
        // Set the Content-Type explicitly from the file's format. The signed
        // streaming URL carries no extension, so a native player (AVPlayer) relies
        // entirely on this header to identify the media — and Ktor's extension-based
        // default has no mapping for `.m4b`, so `respondFile` would send
        // `application/octet-stream`, which AVPlayer refuses to play (it streams only
        // once downloaded, when the local file's extension is available). ExoPlayer
        // sniffs the container bytes regardless, which is why Android was unaffected.
        // `LocalFileContent` still cooperates with `PartialContent`/`AutoHeadResponse`.
        call.respond(LocalFileContent(file, contentType = audioContentType(location.format)))
    }
}

/**
 * The audio MIME type for a stored file [format] token (the lowercase extension,
 * e.g. `"m4b"`). Covers the audiobook formats the scanner ingests; unknown formats
 * fall back to `application/octet-stream`. Kept narrow and explicit so the
 * `Content-Type` a native player sees is always correct for the bytes on disk.
 */
internal fun audioContentType(format: String): ContentType {
    val subtype =
        when (format.lowercase()) {
            "m4b", "m4a", "mp4", "m4p" -> "mp4"
            "aac" -> "aac"
            "mp3" -> "mpeg"
            "ogg", "oga" -> "ogg"
            "opus" -> "opus"
            "flac" -> "flac"
            "wav", "wave" -> "wav"
            "aiff", "aif", "aifc" -> "aiff"
            "webm" -> "webm"
            else -> return ContentType.Application.OctetStream
        }
    return ContentType("audio", subtype)
}
