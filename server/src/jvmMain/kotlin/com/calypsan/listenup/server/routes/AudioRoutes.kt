package com.calypsan.listenup.server.routes

import com.calypsan.listenup.server.api.BookAccessPolicy
import com.calypsan.listenup.server.audio.AudioFileLocation
import com.calypsan.listenup.server.audio.AudioFileLocator
import com.calypsan.listenup.server.audio.AudioUrlSigner
import com.calypsan.listenup.server.auth.UserRoleLookup
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

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
    db: Database,
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
        // Resolve role → access → file location in ONE transaction. The three checks each open
        // their own suspendTransaction, which nest-reuse this outer one (Exposed JDBC), so a
        // hot streaming request takes a single pooled connection instead of three — easing the
        // pool contention behind the playback stalls (#598). Every failure resolves to null →
        // 404, so a private/unknown book is indistinguishable (no existence probe).
        val location: AudioFileLocation? =
            suspendTransaction(db) {
                val role = roleLookup.roleOf(userId) ?: return@suspendTransaction null
                if (!accessPolicy.canAccess(userId, role, bookId)) return@suspendTransaction null
                locator.locate(bookId, fileId)
            }
        if (location == null) return@get call.respond(HttpStatusCode.NotFound)
        val file = java.io.File(location.path.toString())
        if (!file.isFile) return@get call.respond(HttpStatusCode.NotFound)
        call.respondFile(file)
    }
}
