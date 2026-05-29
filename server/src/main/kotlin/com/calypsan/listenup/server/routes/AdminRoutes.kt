package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.server.plugins.userPrincipalOrNull
import com.calypsan.listenup.server.services.SearchReindexService
import com.calypsan.listenup.server.services.UserStatsBackfillService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

/**
 * Admin-only maintenance endpoints.
 *
 *  - `POST /api/v1/admin/stats/backfill?userId=<id>` — rebuilds the materialized
 *    `user_stats` row for the given user from scratch by replaying all raw
 *    `listening_events` and counting finished `playback_positions`. Idempotent.
 *    Returns 200 on success, 400 when `userId` is missing, 403 when the caller
 *    is not ROOT or ADMIN.
 *  - `POST /api/v1/admin/search/reindex` — rebuilds every FTS5 index from source.
 *    Recovery path for index drift after a failed migration. Returns 200 with
 *    `{"reindexedBooks": <n>}`, 403 when the caller is not ROOT or ADMIN.
 *
 * Mounted inside the `authenticate(JWT_PROVIDER)` block — the auth gate is
 * JWT-level; role gate is enforced here.
 */
fun Route.adminRoutes(
    backfillService: UserStatsBackfillService,
    searchReindexService: SearchReindexService,
) {
    post("/api/v1/admin/stats/backfill") {
        val p = call.userPrincipalOrNull() ?: return@post call.respond(HttpStatusCode.Unauthorized)
        if (p.role != UserRole.ROOT && p.role != UserRole.ADMIN) {
            return@post call.respond(HttpStatusCode.Forbidden)
        }
        val userId =
            call.request.queryParameters["userId"]
                ?: return@post call.respond(HttpStatusCode.BadRequest)
        backfillService.backfillFor(userId)
        call.respond(HttpStatusCode.OK)
    }

    post("/api/v1/admin/search/reindex") {
        val p = call.userPrincipalOrNull() ?: return@post call.respond(HttpStatusCode.Unauthorized)
        if (p.role != UserRole.ROOT && p.role != UserRole.ADMIN) {
            return@post call.respond(HttpStatusCode.Forbidden)
        }
        val count = searchReindexService.reindexAll()
        call.respond(HttpStatusCode.OK, mapOf("reindexedBooks" to count))
    }
}
