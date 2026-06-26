package com.calypsan.listenup.server.routes

import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.api.BookAccessPolicy
import com.calypsan.listenup.server.audio.CoverUrlSigner
import com.calypsan.listenup.server.auth.UserRoleLookup
import com.calypsan.listenup.server.cover.CoverResponder
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * Cast cover route. NOT JWT-gated — the URL signature IS the auth, mirroring
 * [audioRoutes]. A Chromecast receiver fetches the cover with no Authorization
 * header, so `playback/prepare` mints a signed `?u&exp&sig` query via
 * [CoverUrlSigner] and this route validates it on every request.
 *
 * The signature proves *who*, not *role*, so after it verifies we resolve the
 * caller's role and gate through [BookAccessPolicy]; a book the caller cannot
 * reach answers 404 (never 403 — no existence probe). A forged/missing
 * signature is the distinct auth failure and answers 403.
 */
internal fun Route.coverCastRoutes(
    coverResponder: CoverResponder,
    signer: CoverUrlSigner,
    roleLookup: UserRoleLookup,
    accessPolicy: BookAccessPolicy,
) {
    get("/api/v1/cover-cast/{bookId}") {
        val bookId = call.parameters["bookId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val exp = call.request.queryParameters["exp"]?.toLongOrNull()
        val sig = call.request.queryParameters["sig"]
        val userId = call.request.queryParameters["u"]
        if (exp == null || sig == null || userId == null || !signer.verify(userId, bookId, exp, sig)) {
            return@get call.respond(HttpStatusCode.Forbidden)
        }
        val role = roleLookup.roleOf(userId) ?: return@get call.respond(HttpStatusCode.NotFound)
        if (!accessPolicy.canAccess(userId, role, bookId)) {
            return@get call.respond(HttpStatusCode.NotFound)
        }
        coverResponder.respondCover(call, BookId(bookId))
    }
}
