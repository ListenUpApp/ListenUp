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

/**
 * Whether the authenticated shell hosts this state.
 *
 * The single definition of that set, read by the navigation router (which shell to compose) and by
 * the startup readiness check (whether to resolve the library-setup gate at all). It was previously
 * hand-written at each site, so adding a variant to [AuthState] silently did the wrong thing in the
 * ones nobody remembered to update.
 *
 * [AuthState.SessionLapsed] belongs to the set on purpose: credentials are dead but local data is
 * intact, so the shell stays mounted with a non-blocking "Sign in to sync" affordance — never a
 * forced wall. Every other variant is owned by a pre-login flow (server URL, setup, login,
 * pending approval) or is a transient startup state that has not decided yet.
 *
 * Written as an exhaustive `when` deliberately: a new [AuthState] variant must fail this build and
 * be classified explicitly, rather than defaulting to "not in the shell" wherever it is read.
 *
 * Note this is NOT the inverse of the Android Auto browse gate (`browseNeedsSignIn`): browse
 * deliberately serves the Room mirror during [AuthState.Initializing] / [AuthState.CheckingServer]
 * rather than flashing a sign-in prompt, whereas those states are emphatically not "in the shell".
 */
val AuthState.isInShell: Boolean
    get() =
        when (this) {
            is AuthState.Authenticated,
            is AuthState.SessionLapsed,
            -> true

            is AuthState.Initializing,
            is AuthState.NeedsServerUrl,
            is AuthState.CheckingServer,
            is AuthState.NeedsSetup,
            is AuthState.NeedsLogin,
            is AuthState.PendingApproval,
            -> false
        }
