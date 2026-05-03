package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.dto.auth.PendingRegistrationDecision
import com.calypsan.listenup.api.dto.auth.PendingRegistrationOutcome
import com.calypsan.listenup.api.dto.auth.RefreshRequest
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.RegisterResult
import com.calypsan.listenup.api.dto.auth.SessionSummary
import com.calypsan.listenup.api.dto.auth.User
import kotlinx.rpc.annotations.Rpc

/**
 * Public auth contract — anonymous callers welcome. Mounted at `/api/rpc/public`
 * (RPC) and under `/api/v1/auth/{login,register,setup,refresh}` (REST).
 *
 * Every method may throw a typed `AuthError` (or `InternalError` for unmapped
 * failures); the RPC exception interceptor and REST `StatusPages` handler
 * unwrap server-side `AuthException` values into the typed wire error.
 */
@Rpc
interface AuthServicePublic {
    /** Issue an auth session for valid credentials. Rate-limited per IP. */
    suspend fun login(request: LoginRequest): AuthSession

    /**
     * Register a new account. Returns either an immediate session
     * (open registration) or PendingApproval (closed-with-queue instance).
     * Errors `SetupRequired` if zero users exist.
     */
    suspend fun register(request: RegisterRequest): RegisterResult

    /** Bootstrap the root user on a fresh instance. Errors `SetupAlreadyComplete` if any user exists. */
    suspend fun setupRoot(request: RegisterRequest): AuthSession

    /**
     * Trade a refresh token for a new access/refresh pair. The old refresh
     * token is invalidated (rotation). A replay of an already-rotated token
     * in the same family triggers a family-wide revoke.
     */
    suspend fun refreshSession(request: RefreshRequest): AuthSession
}

/**
 * Authenticated auth contract — requires a valid bearer JWT. Mounted at
 * `/api/rpc/authed` (RPC) and under `/api/v1/auth/{logout,logout/all,
 * current-user,sessions,pending-registrations/decision}` (REST).
 *
 * The trust boundary is reflected in the type — the public/authed split
 * makes "this method needs a session" a compile-time fact, not a runtime
 * check buried in route configuration.
 */
@Rpc
interface AuthServiceAuthed {
    /** Revoke the caller's current session. Idempotent. */
    suspend fun logout()

    /** Revoke every session for the caller's user. */
    suspend fun logoutAll()

    /** Return the caller's user. */
    suspend fun currentUser(): User

    /** List the caller's active sessions. */
    suspend fun listSessions(): List<SessionSummary>

    /**
     * Approve or deny a pending registration. Admin/root only.
     * Approval flips the user's status to ACTIVE; the applicant's next login()
     * succeeds without any extra step. No redemption token is issued — applicant
     * notification (email, push, polling) is a separate concern.
     */
    suspend fun decidePendingRegistration(request: PendingRegistrationDecision): PendingRegistrationOutcome
}
