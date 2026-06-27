package com.calypsan.listenup.server.auth

import com.calypsan.listenup.api.dto.auth.SessionId

/**
 * The single capability the JWT auth plugin needs from the session layer: is this session still
 * live (not revoked, not expired)? A still-valid access JWT must not outlive a revoked session, so
 * [com.calypsan.listenup.server.plugins.installJwtAuth] re-checks liveness on every authenticated
 * request.
 *
 * Depending on this narrow `fun interface` rather than the concrete (DB-backed, jvmMain)
 * `SessionService` keeps the auth plugin in commonMain — the production server passes
 * `sessions::isLive`; tests pass a trivial stub.
 */
fun interface SessionLiveness {
    suspend fun isLive(sessionId: SessionId): Boolean
}
