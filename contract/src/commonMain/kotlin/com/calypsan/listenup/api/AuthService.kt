package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.dto.auth.PasswordResetStatusEvent
import com.calypsan.listenup.api.dto.auth.PasswordResetTicket
import com.calypsan.listenup.api.dto.auth.RefreshRequest
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.RegisterResult
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.dto.auth.RegistrationStatusEvent
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.SessionSummary
import com.calypsan.listenup.api.dto.auth.SocketTicket
import com.calypsan.listenup.api.dto.auth.User
import com.calypsan.listenup.api.push.PushPlatform
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
     * Trade a valid access token for a single-use [SocketTicket] that authenticates ONE WebSocket
     * upgrade.
     *
     * For browsers only, and not by preference: the DOM `WebSocket` constructor takes
     * `(url, protocols)` and no headers, so a browser cannot present a bearer token on the upgrade
     * the way every native client does. The URL is the only carrier left, and a reverse proxy logs
     * URLs — so what goes there is a ticket, never the JWT.
     *
     * On the PUBLIC mount because it is what a client calls when it cannot yet open the authed one.
     * That is not a hole: the access token is the argument, it is verified here exactly as the
     * bearer provider verifies it, and it travels inside the WebSocket payload rather than a URL.
     */
    suspend fun issueSocketTicket(accessToken: String): AppResult<SocketTicket>

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
     * Registers [token] as a pre-auth **registration watch token**: while the registration for
     * [userId] stays pending, an admin decision is pushed to this device
     * (`PushPayload.RegistrationDecision`) so a backgrounded/closed app still learns the outcome
     * — the background counterpart to [observeRegistrationStatus]'s foreground stream (#1068).
     *
     * Trust model: possession of the unguessable [userId] handle IS the credential, exactly as
     * it is for the observe stream. Rate-limited per IP alongside the other public auth
     * endpoints. Always succeeds without revealing anything: when push is disabled, or [userId]
     * matches no pending registration, nothing is stored and the reply is indistinguishable
     * from the stored case. Watch rows are re-upserted on every call (safe to repeat), carry a
     * server-side TTL, and are evicted the moment the registration is decided.
     */
    suspend fun registerRegistrationWatchToken(
        userId: String,
        token: String,
        platform: PushPlatform,
    ): AppResult<Unit>

    /**
     * Live registration policy for the pre-auth surface (the login screen's Sign Up affordance).
     * Emits the current policy immediately on subscribe, then every change; the server also
     * re-reads persisted state on a fixed cadence (never-stranded backstop for a missed live
     * push). Never completes on its own — the consumer unsubscribes when it leaves the login
     * surface, and resubscribes on drop.
     */
    fun observeRegistrationPolicy(): Flow<RpcEvent<RegistrationPolicy>>

    /**
     * Opens a password-reset request for the account registered to [email].
     *
     * **Always succeeds**, whether or not an account exists — the returned ticket is
     * indistinguishable in shape for a known and an unknown address. This endpoint must never
     * become an account-existence oracle.
     *
     * [deviceClaim] is a high-entropy secret the caller mints and retains; the server persists
     * only a keyed hash of it, and the same value must be presented again at completion. It
     * binds the reset to the install that asked for it.
     */
    suspend fun requestPasswordReset(
        email: String,
        deviceClaim: String,
    ): AppResult<PasswordResetTicket>

    /**
     * Streams the state of the reset request identified by [ticketId]. Emits current state
     * immediately, then live updates, and COMPLETES when the state turns terminal — the
     * completion IS the signal.
     *
     * ⛔ **An unrecognised ticket must NOT error.** [requestPasswordReset] deliberately returns
     * a minted-but-unpersisted ticket for an address with no account; if this stream answered
     * "no such ticket", that guarantee would be undone one call later. An unknown ticket
     * therefore behaves like a real request nobody has approved: emits `PENDING`, then
     * completes as `EXPIRED` once the TTL elapses.
     * [com.calypsan.listenup.api.error.AuthError.ResetRequestNotFound] belongs to
     * [completePasswordReset] and must never appear here.
     */
    fun observePasswordResetStatus(ticketId: String): Flow<RpcEvent<PasswordResetStatusEvent>>

    /**
     * Completes an approved reset. Requires BOTH the [claimSecret] minted by the requesting
     * device and the [code] the admin conveyed out of band. On success every session for the
     * account is revoked — if the reset followed a compromise, leaving the intruder signed in
     * would defeat it. Consumes an attempt on failure; five failures kill the request.
     */
    suspend fun completePasswordReset(
        ticketId: String,
        claimSecret: String,
        code: String,
        newPassword: String,
    ): AppResult<Unit>

    /**
     * Resets root's password against the one-time token a server operator arms by setting
     * `LISTENUP_ROOT_RESET` at boot — see the KDoc on
     * [com.calypsan.listenup.api.error.AuthError.RootResetUnavailable]. Root has no admin above
     * them, so [requestPasswordReset]/[completePasswordReset]'s approval flow cannot rescue them;
     * this is a separate, host-access-authorised path.
     *
     * Unarmed, expired, already-consumed, and simply-wrong [token]s all fail identically with
     * [com.calypsan.listenup.api.error.AuthError.RootResetUnavailable] — the caller must not be
     * able to tell them apart.
     */
    suspend fun resetRootPassword(
        token: String,
        newPassword: String,
    ): AppResult<Unit>
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
