package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.dto.auth.RefreshRequest
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.RegisterResult
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.dto.auth.RegistrationStatusEvent
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.SessionSummary
import com.calypsan.listenup.api.dto.auth.User
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.streaming.RpcEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.rpc.annotations.Rpc

/**
 * Public auth contract — anonymous callers welcome. Mounted at `/api/rpc/public`
 * (RPC) and under `/api/v1/auth/{login,register,setup,refresh}` (REST).
 *
 * Every method returns [AppResult] — failures are values, not thrown
 * exceptions. The typed [com.calypsan.listenup.api.error.AuthError] payload
 * survives both REST and RPC transports because it's in-band data, not an
 * exception serialized as a stack trace.
 */
@Rpc
interface AuthServicePublic {
    /** Issue an auth session for valid credentials. Rate-limited per IP. */
    suspend fun login(request: LoginRequest): AppResult<AuthSession>

    /**
     * Register a new account. Returns either an immediate session
     * (open registration) or PendingApproval (closed-with-queue instance).
     * Errors `SetupRequired` if zero users exist.
     */
    suspend fun register(request: RegisterRequest): AppResult<RegisterResult>

    /** Bootstrap the root user on a fresh instance. Errors `SetupAlreadyComplete` if any user exists. */
    suspend fun setupRoot(request: RegisterRequest): AppResult<AuthSession>

    /**
     * Trade a refresh token for a new access/refresh pair. The old refresh
     * token is invalidated (rotation). A replay of an already-rotated token
     * in the same family triggers a family-wide revoke.
     */
    suspend fun refreshSession(request: RefreshRequest): AppResult<AuthSession>

    /**
     * Streams the approval status of a pending registration. Emits the current status
     * immediately, then live updates, and COMPLETES the moment the status turns terminal
     * (approved/denied) — the completion IS the signal; consumers must not reconnect a
     * completed watch. Pre-auth: served on the public RPC channel, keyed by the [userId]
     * returned from [RegisterResult.PendingApproval]. An unknown [userId] emits a single
     * [RpcEvent.Error] carrying [com.calypsan.listenup.api.error.AuthError.RegistrationNotFound]
     * and completes — it never hangs.
     */
    fun observeRegistrationStatus(userId: String): Flow<RpcEvent<RegistrationStatusEvent>>

    /**
     * Live registration policy for the pre-auth surface (the login screen's Sign Up affordance).
     * Emits the current policy immediately on subscribe, then every change; the server also
     * re-reads persisted state on a fixed cadence (never-stranded backstop for a missed live
     * push). Never completes on its own — the consumer unsubscribes when it leaves the login
     * surface, and resubscribes on drop.
     */
    fun observeRegistrationPolicy(): Flow<RpcEvent<RegistrationPolicy>>
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
    suspend fun logout(): AppResult<Unit>

    /** Revoke every session for the caller's user. */
    suspend fun logoutAll(): AppResult<Unit>

    /** Return the caller's user. */
    suspend fun currentUser(): AppResult<User>

    /** List the caller's active sessions. */
    suspend fun listSessions(): AppResult<List<SessionSummary>>

    /**
     * Revoke one of the caller's sessions by id ("sign out this device").
     * Owner-scoped: revoking a session that isn't the caller's is a silent
     * no-op that still returns Success — no existence leak. Idempotent.
     */
    suspend fun revokeSession(sessionId: SessionId): AppResult<Unit>
}
