package com.calypsan.listenup.server.routes

import com.calypsan.listenup.server.audio.AudioFileLocator
import com.calypsan.listenup.server.audio.AudioUrlSigner
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
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
 */
fun Route.audioRoutes(
    locator: AudioFileLocator,
    signer: AudioUrlSigner,
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
        val location = locator.locate(bookId, fileId) ?: return@get call.respond(HttpStatusCode.NotFound)
        val file = java.io.File(location.path.toString())
        if (!file.isFile) return@get call.respond(HttpStatusCode.NotFound)
        call.respondFile(file)
    }
}
