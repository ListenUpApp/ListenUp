package com.calypsan.listenup.client.domain.model

import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId

/**
 * Authentication state for the application.
 *
 * Drives top-level navigation: each variant maps to a distinct screen flow
 * (server URL entry, setup, login, pending-approval, or the authenticated app).
 */
sealed interface AuthState {
    /** Still determining auth state on startup. */
    data object Initializing : AuthState

    /** No server URL has been configured yet. */
    data object NeedsServerUrl : AuthState

    /** Checking server status to determine if setup is required. */
    data object CheckingServer : AuthState

    /** Server requires initial setup (create root user). */
    data object NeedsSetup : AuthState

    /** Server is ready, user needs to log in. */
    data class NeedsLogin(
        val openRegistration: Boolean = false,
    ) : AuthState

    /**
     * User registered but is waiting for admin approval.
     *
     * `userId` is needed to subscribe to the server-side registration-status
     * watch (RPC/polling); `email` is shown on the pending-approval screen.
     * No credentials are kept client-side — once approved the user retries
     * `login()` from the login screen.
     */
    data class PendingApproval(
        val userId: UserId,
        val email: String,
    ) : AuthState {
        /**
         * The raw user id as a plain `String`.
         *
         * Swift Export exposes [userId] as an opaque `UserId` wrapper with no `.value` accessor,
         * so iOS reads this instead — the same `idString` convention every other Swift-consumed
         * domain model follows.
         */
        val userIdString: String get() = userId.value
    }

    /** User is authenticated with a valid session. */
    data class Authenticated(
        val userId: UserId,
        val sessionId: SessionId,
    ) : AuthState

    /**
     * Session credentials are dead (access token expired and refresh failed) but the user's
     * local data is intact. The shell stays mounted; sync is parked; a non-blocking
     * "Sign in to sync" affordance is the only path to the login screen — never a forced wall.
     */
    data class SessionLapsed(
        val userId: UserId,
    ) : AuthState
}
