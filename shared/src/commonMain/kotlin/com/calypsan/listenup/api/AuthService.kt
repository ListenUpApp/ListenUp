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
 * The auth contract. Source of truth — implemented by `:server`,
 * consumed by the client via the kotlinx.rpc-generated proxy.
 *
 * Every method may throw a typed `AuthError` (or `InternalError` for
 * unmapped failures); the RPC exception interceptor on the server side
 * converts internal exceptions into typed values before they cross the wire.
 */
@Rpc
interface AuthService {
    /** Issue an auth session for valid credentials. Rate-limited per IP. */
    suspend fun login(request: LoginRequest): AuthSession

    /**
     * Register a new account. Returns either an immediate session
     * (open registration) or PendingApproval (closed-with-queue instance).
     * Errors `SetupRequired` if zero users exist.
     */
    suspend fun register(request: RegisterRequest): RegisterResult

    /** Bootstrap the root user on a fresh instance. Errors if any user exists. */
    suspend fun setupRoot(request: RegisterRequest): AuthSession

    /**
     * Trade a refresh token for a new access/refresh pair. The old refresh
     * token is invalidated (rotation). A replay of an already-rotated token
     * in the same family triggers a family-wide revoke.
     */
    suspend fun refreshSession(request: RefreshRequest): AuthSession

    /** Revoke the caller's current session. Authenticated, idempotent. */
    suspend fun logout()

    /** Revoke every session for the caller's user. Authenticated. */
    suspend fun logoutAll()

    /** Return the caller's user. Authenticated. */
    suspend fun currentUser(): User

    /** List the caller's active sessions. Authenticated. */
    suspend fun listSessions(): List<SessionSummary>

    /**
     * Approve or deny a pending registration. Admin/root only.
     * Approved branch returns a one-time PendingRegistrationToken the
     * applicant redeems on their next login() call.
     */
    suspend fun decidePendingRegistration(request: PendingRegistrationDecision): PendingRegistrationOutcome
}
