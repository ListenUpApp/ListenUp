package com.calypsan.listenup.server.auth

import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole

/**
 * The authenticated caller. Built from a verified JWT plus a session-revocation
 * lookup. Available in route handlers via `call.principal<UserPrincipal>()`.
 *
 * Embedding `role` here means handlers don't need a DB hit to gate
 * admin-only operations — at the cost of a role change taking effect only
 * when the access token expires (≤15m). Acceptable tradeoff per the spec.
 */
data class UserPrincipal(
    val userId: UserId,
    val sessionId: SessionId,
    val role: UserRole,
)

fun UserPrincipal.requireAdmin() {
    require(role == UserRole.ROOT || role == UserRole.ADMIN) { "admin required" }
}
