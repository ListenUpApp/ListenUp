@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.InviteService
import com.calypsan.listenup.api.InviteServicePublic
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.invite.InviteDto
import com.calypsan.listenup.api.dto.invite.InviteId
import com.calypsan.listenup.api.dto.invite.InvitePreview
import com.calypsan.listenup.api.dto.invite.InviteStatus
import com.calypsan.listenup.api.dto.invite.InviteSummary
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.InviteError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.auth.Email
import com.calypsan.listenup.server.auth.InviteCodeGenerator
import com.calypsan.listenup.server.auth.PasswordHasher
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.SessionIssuer
import com.calypsan.listenup.server.auth.toColumn
import com.calypsan.listenup.server.db.InviteEntity
import com.calypsan.listenup.server.db.InviteTable
import com.calypsan.listenup.server.db.UserEntity
import com.calypsan.listenup.server.db.UserStatusColumn
import com.calypsan.listenup.server.db.UserTable
import com.calypsan.listenup.server.db.toDto
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.ExperimentalTime

/**
 * [InviteService] implementation — the administrative lifecycle of an invite.
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
    private val db: Database,
    private val codeGenerator: InviteCodeGenerator,
    private val hasher: PasswordHasher,
    private val sessionIssuer: SessionIssuer,
    private val serverName: String,
    private val clock: Clock = Clock.System,
    private val principal: PrincipalProvider = PrincipalProvider.None,
) : InviteService,
    InviteServicePublic {
    /** Returns a copy scoped to the given [provider]. Route handlers call this per-request. */
    fun copyWith(provider: PrincipalProvider): InviteServiceImpl =
        InviteServiceImpl(db, codeGenerator, hasher, sessionIssuer, serverName, clock, provider)

    override suspend fun createInvite(
        email: String,
        displayName: String,
        role: UserRole,
        expiresInDays: Int?,
    ): AppResult<InviteDto> {
        requireAdmin()?.let { return it }
        val caller = principal.current() ?: return AppResult.Failure(AuthError.SessionExpired())
        if (!Email.isLikelyEmail(email)) return AppResult.Failure(InviteError.InvalidInput())
        val now = clock.now()
        return suspendTransaction(db) {
            val invite =
                InviteEntity.new(newInviteId()) {
                    code = codeGenerator.generate()
                    this.email = email
                    this.displayName = displayName
                    this.role = role.toColumn()
                    createdBy = caller.userId.value
                    expiresAt = (now + (expiresInDays ?: DEFAULT_EXPIRY_DAYS).days).toEpochMilliseconds()
                    createdAt = now.toEpochMilliseconds()
                }
            AppResult.Success(invite.toDto())
        }
    }

    override suspend fun listInvites(): AppResult<List<InviteSummary>> {
        requireAdmin()?.let { return it }
        val now = clock.now().toEpochMilliseconds()
        return suspendTransaction(db) {
            AppResult.Success(
                InviteEntity.all().map { InviteSummary(it.toDto(), it.deriveStatus(now)) },
            )
        }
    }

    override suspend fun revokeInvite(id: InviteId): AppResult<Unit> {
        requireAdmin()?.let { return it }
        return suspendTransaction(db) {
            val invite =
                InviteEntity.findById(id.value)
                    ?: return@suspendTransaction AppResult.Failure(InviteError.NotFound())
            if (invite.claimedAt != null) {
                return@suspendTransaction AppResult.Failure(InviteError.AlreadyClaimed())
            }
            invite.delete()
            AppResult.Success(Unit)
        }
    }

    // ── Public (anonymous) surface ──────────────────────────────────────────────

    override suspend fun claimInvite(
        code: String,
        password: String,
        displayName: String?,
    ): AppResult<AuthSession> {
        // Argon2 is CPU-bound and slow on purpose — hash before opening the
        // transaction so we don't hold a DB connection during it (mirrors register).
        val passwordHashed = hasher.hash(password)
        val now = clock.now().toEpochMilliseconds()
        return suspendTransaction(db) {
            val invite =
                InviteEntity.find { InviteTable.code eq code }.firstOrNull()
                    ?: return@suspendTransaction AppResult.Failure(InviteError.NotFound())
            if (invite.claimedAt != null) return@suspendTransaction AppResult.Failure(InviteError.AlreadyClaimed())
            if (invite.expiresAt < now) return@suspendTransaction AppResult.Failure(InviteError.Expired())

            val normalized = Email.normalize(invite.email)
            if (UserEntity.find { UserTable.emailNormalized eq normalized }.any()) {
                return@suspendTransaction AppResult.Failure(InviteError.EmailInUse())
            }

            // Create the user exactly as AuthServiceImpl.register does, minus the
            // policy branch: the invite IS the admission, so status is always ACTIVE.
            val user =
                UserEntity.new(newUserId()) {
                    email = invite.email
                    emailNormalized = normalized
                    passwordHash = passwordHashed
                    role = invite.role
                    this.displayName = displayName ?: invite.displayName
                    status = UserStatusColumn.ACTIVE
                    invitedBy = invite.createdBy
                    createdAt = now
                    updatedAt = now
                }
            // Single-use: stamp the claim so the code can't be redeemed again.
            invite.claimedAt = now
            invite.claimedBy = user.id.value
            AppResult.Success(sessionIssuer.issue(user, label = null))
        }
    }

    override suspend fun lookupInvite(code: String): AppResult<InvitePreview> {
        val now = clock.now().toEpochMilliseconds()
        return suspendTransaction(db) {
            val invite =
                InviteEntity.find { InviteTable.code eq code }.firstOrNull()
                    ?: return@suspendTransaction AppResult.Failure(InviteError.NotFound())
            val invitedByName = UserEntity.findById(invite.createdBy)?.displayName ?: "An administrator"
            val claimed = invite.claimedAt != null
            val expired = invite.expiresAt < now
            AppResult.Success(
                InvitePreview(
                    displayName = invite.displayName,
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

    /** Lifecycle status derived from claim then expiry; PENDING when neither applies. */
    private fun InviteEntity.deriveStatus(now: Long): InviteStatus =
        when {
            claimedAt != null -> InviteStatus.CLAIMED
            expiresAt < now -> InviteStatus.EXPIRED
            else -> InviteStatus.PENDING
        }

    private fun UserRole.isAdmin(): Boolean = this == UserRole.ROOT || this == UserRole.ADMIN

    // UUIDv4 mirrors AuthServiceImpl.newUserId — TEXT primary key with no time-ordered scan path.
    private fun newInviteId(): String = UUID.randomUUID().toString()

    // Claimed accounts get the same id shape as AuthServiceImpl.register's users.
    private fun newUserId(): String = UUID.randomUUID().toString()

    private companion object {
        const val DEFAULT_EXPIRY_DAYS = 7
    }
}
