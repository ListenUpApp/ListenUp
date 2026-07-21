@file:MustUseReturnValues

package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.RegisterResult
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.SessionSummary
import com.calypsan.listenup.api.result.AppResult

/**
 * Domain port for the auth contract. Speaks the contract-layer types
 * directly — `LoginRequest` / `RegisterRequest` in, `AppResult<AuthSession>`
 * / `AppResult<RegisterResult>` out — so callers exhaustively `when` over
 * `AuthError` instead of catching exceptions.
 *
 * Implementations route through the split auth RPC channels (public + authed).
 * Token persistence is a caller concern: this port does not mutate `AuthSession` state.
 *
 * Note: `checkRegistrationStatus(userId)` is intentionally absent. It was
 * the REST-era polling fallback for "is my registration approved yet?" —
 * after the F4 product change, the canonical signal is "user retries
 * `login()` and it now succeeds when admin approval flips status to
 * ACTIVE." Real-time approval notifications come from the RPC
 * `RegistrationStatusStream`; there is no polling fallback in the auth
 * contract.
 */
interface AuthRepository {
    /** Authenticate with credentials. Returns a session on success. */
    suspend fun login(request: LoginRequest): AppResult<AuthSession>

    /**
     * Register a new account. Returns either an immediate session
     * (open registration) or `PendingApproval` (closed-with-queue instance).
     */
    suspend fun register(request: RegisterRequest): AppResult<RegisterResult>

    /**
     * Bootstrap the root user on a fresh instance. Errors with
     * `AuthError.SetupAlreadyComplete` if any user already exists.
     */
    suspend fun setup(request: RegisterRequest): AppResult<AuthSession>

    /**
     * Revoke the caller's current session on the server. The session is
     * read from the bearer JWT — no client-side identifier needed.
     */
    suspend fun logout(): AppResult<Unit>

    /**
     * Trade the locally-stored refresh token for a new access/refresh pair.
     *
     * Reads the refresh token from `AuthSession`. If none is present, fails
     * with `AuthError.SessionExpired` — the caller is effectively logged
     * out and there is nothing to refresh.
     *
     * Routes through the public RPC mount because the refresh token is the
     * credential; no bearer is required (and attaching one would trigger
     * a refresh loop).
     *
     * Per this port's contract, this method does NOT mutate `AuthSession`
     * state on success — the bearer-plugin glue persists the new tokens.
     */
    suspend fun refreshAccessToken(): AppResult<AuthSession>

    /** List the caller's active sessions (devices). */
    suspend fun listSessions(): AppResult<List<SessionSummary>>

    /** Revoke a specific session by id ("sign out this device"). Owner-scoped server-side. */
    suspend fun revokeSession(sessionId: SessionId): AppResult<Unit>

    /** Revoke every session for the caller ("sign out everywhere"). */
    suspend fun logoutAll(): AppResult<Unit>
}
