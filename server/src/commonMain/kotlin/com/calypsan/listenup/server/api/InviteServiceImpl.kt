@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.InviteService
import com.calypsan.listenup.api.InviteServicePublic
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.DeviceInfo
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.invite.InviteDto
import com.calypsan.listenup.api.dto.invite.InviteId
import com.calypsan.listenup.api.dto.invite.InvitePreview
import com.calypsan.listenup.api.dto.invite.InviteStatus
import com.calypsan.listenup.api.dto.invite.InviteSummary
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.InviteError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.auth.Argon2Limiter
import com.calypsan.listenup.server.auth.AuthUser
import com.calypsan.listenup.server.auth.Email
import com.calypsan.listenup.server.auth.InviteCodeGenerator
import com.calypsan.listenup.server.auth.InviteRateBucket
import com.calypsan.listenup.server.auth.InviteRateLimiter
import com.calypsan.listenup.server.auth.PasswordPolicy
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.RateDecision
import com.calypsan.listenup.server.auth.SessionIssuer
import com.calypsan.listenup.server.auth.toColumn
import com.calypsan.listenup.server.auth.toContract
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.db.UserStatusColumn
import com.calypsan.listenup.server.db.sqldelight.Invites
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.services.AdminUserRosterMaintainer
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlin.time.Duration.Companion.days
import kotlin.time.ExperimentalTime

/**
 * [InviteService] implementation — the administrative lifecycle of an invite.
 *
 * Persists over SQLDelight's [ListenUpDatabase] (`invites` + `users` are plain, non-syncable
 * server-owned tables). The single-use guarantee (claimed_at stamp), the UNIQUE invite code, and
 * the email-uniqueness probe are preserved verbatim from the Exposed implementation.
 *
 * Every method is admin-gated: the caller must be ROOT or ADMIN, resolved from
 * [principal] (never from request fields). Non-admins receive
 * [AuthError.PermissionDenied]; an absent principal yields [AuthError.SessionExpired].
 *
 * Minting stamps the calling admin into `createdBy`, generates a high-entropy
 * single-use code, and defaults expiry to [DEFAULT_EXPIRY_DAYS] days unless
 * overridden. Listing derives each invite's [InviteStatus] from its claim/expiry
 * state. Revocation is a hard delete, refused once an invite is claimed.
 *
 * Route handlers call [copyWith] to bind each request to the authenticated
 * principal; the Koin singleton carries an unscoped placeholder that yields no
 * principal.
 *
 * The public surface ([lookupInvite], [claimInvite]) is anonymous — no principal,
 * no admin gate. [claimInvite] is the single-use admission path: it creates an
 * ACTIVE account *bypassing* [com.calypsan.listenup.api.dto.auth.RegistrationPolicy]
 * (the invite itself is the admission decision an admin already made), stamps
 * `invited_by`, marks the invite claimed, and mints a session — creating the user
 * with exactly the same shape as `AuthServiceImpl.register`, minus the policy branch.
 */
class InviteServiceImpl(
    private val db: ListenUpDatabase,
    private val codeGenerator: InviteCodeGenerator,
    private val hasher: Argon2Limiter,
    private val sessionIssuer: SessionIssuer,
    private val serverName: String,
    private val clock: Clock = Clock.System,
    private val principal: PrincipalProvider = PrincipalProvider.None,
    /**
     * Nullable so the invite module assembles independently of the collections module
     * (test environments, phased startup). A null value silently skips default grant issuance.
     */
    private val defaultGrantIssuer: DefaultAllBooksGrantIssuer? = null,
    /**
     * Nullable so the invite module assembles independently of the admin-roster module (test
     * environments, phased startup). A null value silently skips the roster-projection refresh.
     */
    private val adminUserRosterMaintainer: AdminUserRosterMaintainer? = null,
    /**
     * The caller's remote host, captured at the `/api/rpc/public` mount and threaded in via
     * [withRemoteHost]. Non-null only on the RPC public path, where [inviteRateLimiter] enforces the
     * per-IP throttle on [claimInvite] / [lookupInvite]; null on REST (throttled by the Ktor
     * `RateLimit` plugin) and in unit tests.
     */
    private val remoteHost: String? = null,
    /**
     * The RPC-path per-IP throttle for the anonymous invite surface (SEC-02). Non-null in
     * production; null in unit tests and on the REST path, where the throttle is a no-op (the Ktor
     * `RateLimit` plugin covers REST).
     */
    private val inviteRateLimiter: InviteRateLimiter? = null,
) : InviteService,
    InviteServicePublic {
    /** Returns a copy scoped to the given [provider]. Route handlers call this per-request. */
    fun copyWith(provider: PrincipalProvider): InviteServiceImpl =
        InviteServiceImpl(
            db,
            codeGenerator,
            hasher,
            sessionIssuer,
            serverName,
            clock,
            provider,
            defaultGrantIssuer,
            adminUserRosterMaintainer,
            remoteHost,
            inviteRateLimiter,
        )

    /**
     * Bind the caller's [remoteHost] so the RPC public mount's per-IP throttle
     * ([inviteRateLimiter]) keys on it. The REST path never calls this — its throttle is the Ktor
     * `RateLimit` plugin.
     */
    fun withRemoteHost(remoteHost: String): InviteServiceImpl =
        InviteServiceImpl(
            db,
            codeGenerator,
            hasher,
            sessionIssuer,
            serverName,
            clock,
            principal,
            defaultGrantIssuer,
            adminUserRosterMaintainer,
            remoteHost,
            inviteRateLimiter,
        )

    override suspend fun createInvite(
        email: String,
        displayName: String,
        role: UserRole,
        expiresInDays: Int?,
    ): AppResult<InviteDto> {
        requireAdmin()?.let { return it }
        val caller = principal.current() ?: return AppResult.Failure(AuthError.SessionExpired())
        if (!Email.isLikelyEmail(email)) return AppResult.Failure(InviteError.InvalidInput())
        if (displayName.isBlank()) return AppResult.Failure(InviteError.InvalidInput())
        if (expiresInDays != null && expiresInDays <= 0) return AppResult.Failure(InviteError.InvalidInput())
        // ROOT is a protected tier: only a ROOT caller may mint a ROOT-granting invite.
        if (role == UserRole.ROOT && caller.role != UserRole.ROOT) {
            return AppResult.Failure(AuthError.PermissionDenied())
        }
        val now = clock.now()
        val invite =
            Invites(
                id = newInviteId(),
                code = codeGenerator.generate(),
                email = email,
                display_name = displayName,
                role = role.toColumn().name,
                created_by = caller.userId.value,
                expires_at = (now + (expiresInDays ?: DEFAULT_EXPIRY_DAYS).days).toEpochMilliseconds(),
                claimed_at = null,
                claimed_by = null,
                created_at = now.toEpochMilliseconds(),
            )
        return suspendTransaction(db) {
            db.invitesQueries.insert(
                id = invite.id,
                code = invite.code,
                email = invite.email,
                display_name = invite.display_name,
                role = invite.role,
                created_by = invite.created_by,
                expires_at = invite.expires_at,
                created_at = invite.created_at,
            )
            AppResult.Success(invite.toDto())
        }
    }

    override suspend fun listInvites(): AppResult<List<InviteSummary>> {
        requireAdmin()?.let { return it }
        val now = clock.now().toEpochMilliseconds()
        return suspendTransaction(db) {
            AppResult.Success(
                db.invitesQueries
                    .selectAll()
                    .executeAsList()
                    .map { InviteSummary(it.toDto(), it.deriveStatus(now)) },
            )
        }
    }

    override suspend fun revokeInvite(id: InviteId): AppResult<Unit> {
        requireAdmin()?.let { return it }
        return suspendTransaction(db) {
            val invite =
                db.invitesQueries.selectById(id = id.value).executeAsOneOrNull()
                    ?: return@suspendTransaction AppResult.Failure(InviteError.NotFound())
            if (invite.claimed_at != null) {
                return@suspendTransaction AppResult.Failure(InviteError.AlreadyClaimed())
            }
            db.invitesQueries.deleteById(id = id.value)
            AppResult.Success(Unit)
        }
    }

    // ── Public (anonymous) surface ──────────────────────────────────────────────

    override suspend fun claimInvite(
        code: String,
        password: String,
        displayName: String?,
        deviceInfo: DeviceInfo?,
    ): AppResult<AuthSession> {
        // Throttle BEFORE any Argon2 work so a brute-force burst can't turn into a CPU/memory DoS
        // (SEC-02, mirrors AuthServiceImpl.login).
        enforceRate(InviteRateBucket.CLAIM)?.let { return AppResult.Failure(it) }
        val now = clock.now().toEpochMilliseconds()
        // Cheap existence/expiry/claimed pre-check BEFORE the expensive Argon2 hash — stops a
        // bogus or dead code from paying for a hash it can never use. claimUserAtomically
        // re-validates the same conditions inside its own transaction, which stays the single-use
        // authority for a genuinely racing claim.
        precheckInviteCode(code, now)?.let { return AppResult.Failure(it) }
        // Reject a weak password before hashing — the invited user's password has no DTO-level
        // gate (unlike register/change), so this is its only length/blank check.
        when (val policyCheck = PasswordPolicy.validate(password)) {
            is AppResult.Failure -> return policyCheck
            is AppResult.Success -> Unit
        }
        // Argon2 is CPU-bound and slow on purpose — hash before opening the
        // transaction so we don't hold a DB connection during it (mirrors register).
        val passwordHashed = hasher.hash(password)
        // The validate → insert-user → mark-claimed steps run atomically in one transaction so the
        // single-use guarantee holds (a row can't be both claimed and have no user). Session issuance
        // is hoisted OUT: it opens its own session transaction, and the SQLDelight transaction body is
        // non-suspending — exactly the boundary AuthServiceImpl.register draws between commit and issue.
        val user: AuthUser =
            when (val claim = claimUserAtomically(code, passwordHashed, displayName, now)) {
                is AppResult.Failure -> return claim
                is AppResult.Success -> claim.data
            }
        // Best-effort default ALL_BOOKS grant — mirrors AuthServiceImpl.register. Runs AFTER the
        // atomic claim commits so the user FK exists for the grant row. The issuer itself never
        // throws (re-raises CancellationException, swallows everything else), so no outer try/catch
        // is needed; a MEMBER claim that fails to grant self-heals on next login. ROOT/ADMIN invites
        // are a no-op inside the issuer (role gate).
        defaultGrantIssuer?.grantDefaultAllBooks(user.id, user.role)
        // Best-effort admin_user_roster refresh — mirrors AuthServiceImpl.register's
        // publicProfileMaintainer wiring. Never throws (see refreshBestEffort); a claim that
        // fails to publish self-heals on the next backfillAll pass.
        adminUserRosterMaintainer?.refreshBestEffort(user.id)
        return AppResult.Success(sessionIssuer.issue(user, label = null, deviceInfo = deviceInfo))
    }

    /**
     * Read-only existence/expiry/claimed probe for [code], run BEFORE [claimInvite] pays for an
     * Argon2 hash. Returns the same typed failures [claimUserAtomically] would raise, or null when
     * the code looks claimable — [claimUserAtomically] re-checks the identical conditions inside its
     * own transaction and remains the single-use authority for a genuinely racing claim.
     */
    private suspend fun precheckInviteCode(
        code: String,
        now: Long,
    ): InviteError? =
        suspendTransaction(db) {
            val invite =
                db.invitesQueries.selectByCode(code = code).executeAsOneOrNull()
                    ?: return@suspendTransaction InviteError.NotFound()
            if (invite.claimed_at != null) return@suspendTransaction InviteError.AlreadyClaimed()
            if (invite.expires_at < now) return@suspendTransaction InviteError.Expired()
            null
        }

    /**
     * The transactional core of [claimInvite]: validate the code, insert the new ACTIVE user, and
     * stamp the claim — all in one transaction. Returns the new [AuthUser] on success, or a typed
     * [InviteError] failure. No suspending calls inside the block (SQLDelight transactions are
     * non-suspending), so session issuance happens at the [claimInvite] call site after commit.
     */
    private suspend fun claimUserAtomically(
        code: String,
        passwordHashed: String,
        displayName: String?,
        now: Long,
    ): AppResult<AuthUser> =
        suspendTransaction(db) {
            val invite =
                db.invitesQueries.selectByCode(code = code).executeAsOneOrNull()
                    ?: return@suspendTransaction AppResult.Failure(InviteError.NotFound())
            if (invite.claimed_at != null) return@suspendTransaction AppResult.Failure(InviteError.AlreadyClaimed())
            if (invite.expires_at < now) return@suspendTransaction AppResult.Failure(InviteError.Expired())

            val normalized = Email.normalize(invite.email)
            if (db.usersQueries.existsByEmailNormalized(email_normalized = normalized).executeAsOne()) {
                return@suspendTransaction AppResult.Failure(InviteError.EmailInUse())
            }

            // Create the user exactly as AuthServiceImpl.register does, minus the
            // policy branch: the invite IS the admission, so status is always ACTIVE.
            val user =
                AuthUser(
                    id = newUserId(),
                    email = invite.email,
                    emailNormalized = normalized,
                    passwordHash = passwordHashed,
                    role = UserRoleColumn.valueOf(invite.role),
                    displayName = displayName ?: invite.display_name,
                    status = UserStatusColumn.ACTIVE,
                    createdAt = now,
                    canEdit = true,
                    canShare = true,
                    approvedBy = null,
                    approvedAt = null,
                    deletedAt = null,
                )
            db.usersQueries.insert(
                id = user.id,
                email = user.email,
                email_normalized = user.emailNormalized,
                password_hash = user.passwordHash,
                role = user.role.name,
                display_name = user.displayName,
                status = user.status.name,
                created_at = now,
                updated_at = now,
                last_login_at = null,
                can_edit = 1L,
                can_share = 1L,
                approved_by = null,
                approved_at = null,
                deleted_at = null,
                // Stamp the invitation graph: this user was invited by the issuing admin.
                invited_by = invite.created_by,
                tagline = null,
                avatar_type = "auto",
                timezone = "UTC",
            )
            // Single-use: stamp the claim so the code can't be redeemed again.
            db.invitesQueries.markClaimed(claimed_at = now, claimed_by = user.id, id = invite.id)
            AppResult.Success(user)
        }

    override suspend fun lookupInvite(code: String): AppResult<InvitePreview> {
        enforceRate(InviteRateBucket.LOOKUP)?.let { return AppResult.Failure(it) }
        val now = clock.now().toEpochMilliseconds()
        return suspendTransaction(db) {
            val invite =
                db.invitesQueries.selectByCode(code = code).executeAsOneOrNull()
                    ?: return@suspendTransaction AppResult.Failure(InviteError.NotFound())
            val invitedByName =
                db.usersQueries
                    .selectById(id = invite.created_by)
                    .executeAsOneOrNull()
                    ?.display_name ?: "An administrator"
            val claimed = invite.claimed_at != null
            val expired = invite.expires_at < now
            AppResult.Success(
                InvitePreview(
                    displayName = invite.display_name,
                    email = invite.email,
                    invitedByName = invitedByName,
                    serverName = serverName,
                    valid = !claimed && !expired,
                    invalidReason =
                        when {
                            claimed -> InviteError.AlreadyClaimed().message
                            expired -> InviteError.Expired().message
                            else -> null
                        },
                ),
            )
        }
    }

    // ── Private helpers ─────────────────────────────────────────────────────────

    /** null = allowed; a Failure (PermissionDenied / SessionExpired) otherwise. */
    private fun requireAdmin(): AppResult.Failure? {
        val caller = principal.current() ?: return AppResult.Failure(AuthError.SessionExpired())
        return if (caller.role.isAdmin()) null else AppResult.Failure(AuthError.PermissionDenied())
    }

    /**
     * Per-IP throttle probe for [bucket]. Returns an [AuthError.RateLimited] to short-circuit the
     * caller when over the ceiling, or null to proceed. A no-op (null) unless BOTH the remote host
     * and the limiter are bound — i.e. only on the RPC public mount (SEC-02). Reuses the shared
     * [AuthError.RateLimited] shape (same one [com.calypsan.listenup.server.auth.AuthServiceImpl]
     * raises) rather than a dedicated [InviteError] subtype — one 429 shape for the client to fold.
     */
    private suspend fun enforceRate(bucket: InviteRateBucket): AuthError? {
        val host = remoteHost ?: return null
        val limiter = inviteRateLimiter ?: return null
        return when (val decision = limiter.check(bucket, host)) {
            RateDecision.Allowed -> null
            is RateDecision.Throttled -> AuthError.RateLimited(retryAfterSeconds = decision.retryAfterSeconds)
        }
    }

    /** Lifecycle status derived from claim then expiry; PENDING when neither applies. */
    private fun Invites.deriveStatus(now: Long): InviteStatus =
        when {
            claimed_at != null -> InviteStatus.CLAIMED
            expires_at < now -> InviteStatus.EXPIRED
            else -> InviteStatus.PENDING
        }

    private fun UserRole.isAdmin(): Boolean = this == UserRole.ROOT || this == UserRole.ADMIN

    // UUIDv4 mirrors AuthServiceImpl.newUserId — TEXT primary key with no time-ordered scan path.
    private fun newInviteId(): String = Uuid.random().toString()

    // Claimed accounts get the same id shape as AuthServiceImpl.register's users.
    private fun newUserId(): String = Uuid.random().toString()

    private companion object {
        const val DEFAULT_EXPIRY_DAYS = 7
    }
}

/**
 * Map a generated `invites` row to the wire [InviteDto]. The role string is mapped through
 * [UserRoleColumn] so an invited role round-trips identically to a persisted user role — exactly
 * what the prior Exposed `InviteEntity.toDto()` did via its `enumerationByName` column.
 */
private fun Invites.toDto(): InviteDto =
    InviteDto(
        id = InviteId(id),
        code = code,
        email = email,
        displayName = display_name,
        role = UserRoleColumn.valueOf(role).toContract(),
        createdBy = created_by,
        expiresAt = expires_at,
        createdAt = created_at,
        claimedAt = claimed_at,
        claimedBy = claimed_by,
    )
