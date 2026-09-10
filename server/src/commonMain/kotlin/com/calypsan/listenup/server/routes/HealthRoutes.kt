package com.calypsan.listenup.server.routes

import com.calypsan.listenup.server.api.ServerIdentity
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlin.time.TimeMark
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What `/healthz` answers. Server-only on purpose: this is an operator surface, not part of the
 * client contract, so it stays out of `:contract` and off every client's export surface.
 *
 * Every key is `@SerialName`-pinned because two things outside this codebase read them by name —
 * `server/scripts/serve-smoke.sh` (the release gate) greps the raw body for `"status":"ok"` and for
 * the expected `"version"`, and an operator's own monitoring may do the same. Renaming a key here
 * disarms the gate silently rather than failing it.
 *
 * @property status constant `"ok"`; reaching this route at all is the health signal.
 * @property version the build's advertised version, injected from the repo-root `VERSION` file.
 * @property schemaVersion the highest applied migration, or `null` on a fresh/empty database — a
 *   non-null value proves migrations ran to completion before the server began serving.
 * @property uptimeSeconds seconds since routes were installed, so an operator can tell a
 *   long-running instance from one that is crash-looping.
 */
@Serializable
internal data class HealthResponse(
    @SerialName("status") val status: String,
    @SerialName("version") val version: String,
    @SerialName("schemaVersion") val schemaVersion: String?,
    @SerialName("uptimeSeconds") val uptimeSeconds: Long,
)

/**
 * The unauthenticated health endpoint. It MUST stay a sibling of the `authenticate` blocks in
 * `ApplicationRoutes` — a health check behind auth breaks the release smoke gate and any container
 * `HEALTHCHECK`, both of which call it with no credentials.
 *
 * Deliberately reports nothing sensitive: a version and a schema version an operator needs to tell
 * which build is running and whether it migrated, and no configuration, paths, or secrets.
 *
 * @param schemaVersion read per request rather than snapshotted at boot, because a restore swaps the
 *   database file and migrates it forward while the server keeps serving.
 * @param startedAt the mark uptime is measured from — monotonic, so a host clock change cannot make
 *   uptime jump or go negative.
 */
internal fun Route.healthRoutes(
    schemaVersion: () -> String?,
    startedAt: TimeMark,
) {
    get("/healthz") {
        call.respond(
            HealthResponse(
                status = "ok",
                version = ServerIdentity.VERSION,
                schemaVersion = schemaVersion(),
                uptimeSeconds = startedAt.elapsedNow().inWholeSeconds,
            ),
        )
    }
}
