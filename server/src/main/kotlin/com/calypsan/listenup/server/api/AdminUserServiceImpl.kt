@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.AdminUserService
import com.calypsan.listenup.api.dto.auth.AdminUserPatch
import com.calypsan.listenup.api.dto.auth.PendingRegistrationDecision
import com.calypsan.listenup.api.dto.auth.PendingRegistrationOutcome
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.dto.auth.User
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.error.AdminError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.SyncControl
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.RegistrationBroadcaster
import com.calypsan.listenup.server.auth.RegistrationDecision
import com.calypsan.listenup.server.auth.SessionService
import com.calypsan.listenup.server.auth.toColumn
import com.calypsan.listenup.server.auth.toContract
import com.calypsan.listenup.server.db.UserEntity
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.db.UserStatusColumn
import com.calypsan.listenup.server.db.UserTable
import com.calypsan.listenup.server.settings.ServerSettingsRepository
import com.calypsan.listenup.server.sync.ChangeBus
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * [AdminUserService] implementation — the administrative lifecycle of an account.
 *
 * Every method is admin-gated: the caller must be ROOT or ADMIN, resolved from
 * [principal] (never from request fields). Non-admins receive
 * [AuthError.PermissionDenied]; an absent principal yields [AuthError.SessionExpired].
 *
 * Soft-delete is the deletion model: [deleteUser] stamps `deletedAt` rather than
 * removing the row (so authored content isn't orphaned), and *every* read excludes
 * soft-deleted users.
 *
 * Safety rails protect the instance from becoming unmanageable:
 * - the ROOT account can never be modified or deleted ([AdminError.CannotModifyRoot]),
 * - the last admin can't be demoted or deleted ([AdminError.CannotDemoteLastAdmin] /
 *   [AdminError.CannotDeleteLastAdmin], counting non-deleted ROOT+ADMIN),
 * - an admin can't delete their own account here ([AdminError.CannotDeleteSelf]).
 *
 * Route handlers call [copyWith] to bind each request to the authenticated principal;
 * the Koin singleton carries an unscoped placeholder that yields no principal.
 */
class AdminUserServiceImpl(
    private val db: Database,
    private val sessions: SessionService,
    private val settings: ServerSettingsRepository,
    private val registrationBroadcaster: RegistrationBroadcaster,
    private val bus: ChangeBus,
    private val clock: Clock = Clock.System,
    private val principal: PrincipalProvider = PrincipalProvider.None,
) : AdminUserService {
    /** Returns a copy scoped to the given [provider]. Route handlers call this per-request. */
    fun copyWith(provider: PrincipalProvider): AdminUserServiceImpl =
        AdminUserServiceImpl(db, sessions, settings, registrationBroadcaster, bus, clock, provider)

    override suspend fun listUsers(): AppResult<List<User>> {
        requireAdmin()?.let { return it }
        return suspendTransaction(db) {
            AppResult.Success(
                UserEntity.find { UserTable.deletedAt eq null }.map { it.toContract() },
            )
        }
    }

    override suspend fun listPendingUsers(): AppResult<List<User>> {
        requireAdmin()?.let { return it }
        return suspendTransaction(db) {
            AppResult.Success(
                UserEntity
                    .find {
                        (UserTable.deletedAt eq null) and
                            (UserTable.status eq UserStatusColumn.PENDING_APPROVAL)
                    }.map { it.toContract() },
            )
        }
    }

    override suspend fun getUser(id: UserId): AppResult<User> {
        requireAdmin()?.let { return it }
        return suspendTransaction(db) {
            val user =
                activeUser(id)
                    ?: return@suspendTransaction AppResult.Failure(AdminError.UserNotFound())
            AppResult.Success(user.toContract())
        }
    }

    override suspend fun searchUsers(query: String): AppResult<List<User>> {
        requireAdmin()?.let { return it }
        val needle = query.trim()
        return suspendTransaction(db) {
            AppResult.Success(
                UserEntity
                    .find { UserTable.deletedAt eq null }
                    .filter {
                        it.displayName.contains(needle, ignoreCase = true) ||
                            it.email.contains(needle, ignoreCase = true)
                    }.map { it.toContract() },
            )
        }
    }

    override suspend fun updateUser(
        id: UserId,
        patch: AdminUserPatch,
    ): AppResult<User> {
        requireAdmin()?.let { return it }
        return suspendTransaction(db) {
            val user =
                activeUser(id)
                    ?: return@suspendTransaction AppResult.Failure(AdminError.UserNotFound())

            val roleChange = roleChangeFor(user, patch.role)
            roleChange?.let { return@suspendTransaction it }

            patch.displayName?.let { user.displayName = it }
            patch.role?.let { user.role = it.toColumn() }
            patch.permissions?.let {
                user.canEdit = it.canEdit
                user.canShare = it.canShare
            }
            user.updatedAt = clock.now().toEpochMilliseconds()
            AppResult.Success(user.toContract())
        }
    }

    override suspend fun deleteUser(id: UserId): AppResult<Unit> {
        val caller = principal.current() ?: return AppResult.Failure(AuthError.SessionExpired())
        if (!caller.role.isAdmin()) return AppResult.Failure(AuthError.PermissionDenied())
        if (caller.userId == id) return AppResult.Failure(AdminError.CannotDeleteSelf())

        val outcome: AppResult<Unit> =
            suspendTransaction(db) {
                val user =
                    activeUser(id)
                        ?: return@suspendTransaction AppResult.Failure(AdminError.UserNotFound())
                if (user.role == UserRoleColumn.ROOT) {
                    return@suspendTransaction AppResult.Failure(AdminError.CannotModifyRoot())
                }
                if (user.role == UserRoleColumn.ADMIN && countActiveAdmins() <= 1) {
                    return@suspendTransaction AppResult.Failure(AdminError.CannotDeleteLastAdmin())
                }
                user.deletedAt = clock.now().toEpochMilliseconds()
                AppResult.Success(Unit)
            }

        // The soft-delete commit is the durable fact, so we act only on Success. Publish the
        // control frame BEFORE revoking: revokeAll marks the session row but doesn't kill the
        // user's live firehose coroutine, so the still-authed connection receives UserDeleted
        // before its session dies — letting the client clear auth in real time.
        if (outcome is AppResult.Success) {
            bus.publishControl(
                SyncControl.UserDeleted(reason = "Your account was removed by an administrator."),
                id.value,
            )
            sessions.revokeAll(id)
        }
        return outcome
    }

    override suspend fun decidePendingRegistration(
        request: PendingRegistrationDecision,
    ): AppResult<PendingRegistrationOutcome> {
        val caller = principal.current() ?: return AppResult.Failure(AuthError.SessionExpired())
        if (!caller.role.isAdmin()) return AppResult.Failure(AuthError.PermissionDenied())

        // Don't leak existence-or-state of the target — admin actions only succeed
        // against a genuinely pending row; everything else is PermissionDenied.
        val now = clock.now().toEpochMilliseconds()
        val outcome: AppResult<PendingRegistrationOutcome> =
            suspendTransaction(db) {
                val target =
                    UserEntity.findById(request.userId.value)
                        ?: return@suspendTransaction AppResult.Failure(AuthError.PermissionDenied())
                if (target.status != UserStatusColumn.PENDING_APPROVAL) {
                    return@suspendTransaction AppResult.Failure(AuthError.PermissionDenied())
                }

                target.status = if (request.approved) UserStatusColumn.ACTIVE else UserStatusColumn.DENIED
                target.updatedAt = now
                target.approvedBy = caller.userId.value
                target.approvedAt = now

                AppResult.Success(
                    if (request.approved) PendingRegistrationOutcome.Approved else PendingRegistrationOutcome.Denied,
                )
            }

        // Notify the waiting (unauthenticated) registrant only after a real decision commits —
        // a no-op drop if they aren't currently listening, recovered on their next login retry.
        if (outcome is AppResult.Success) {
            registrationBroadcaster.notify(
                request.userId.value,
                if (request.approved) RegistrationDecision.Approved else RegistrationDecision.Denied(null),
            )
        }
        return outcome
    }

    override suspend fun getRegistrationPolicy(): AppResult<RegistrationPolicy> {
        requireAdmin()?.let { return it }
        return AppResult.Success(settings.registrationPolicy())
    }

    override suspend fun setRegistrationPolicy(policy: RegistrationPolicy): AppResult<Unit> {
        requireAdmin()?.let { return it }
        settings.setRegistrationPolicy(policy)
        return AppResult.Success(Unit)
    }

    // ── Private helpers ─────────────────────────────────────────────────────────

    /** null = allowed; a Failure (PermissionDenied / SessionExpired) otherwise. */
    private fun requireAdmin(): AppResult.Failure? {
        val caller = principal.current() ?: return AppResult.Failure(AuthError.SessionExpired())
        return if (caller.role.isAdmin()) null else AppResult.Failure(AuthError.PermissionDenied())
    }

    /** The live (non-deleted) user with [id], or null. Must run inside a transaction. */
    private fun activeUser(id: UserId): UserEntity? = UserEntity.findById(id.value)?.takeIf { it.deletedAt == null }

    /**
     * Validates a role change against the safety rails. Returns null when the
     * change is allowed (or no role change is requested); a Failure otherwise.
     * Must run inside a transaction (reads [countActiveAdmins]).
     */
    private fun roleChangeFor(
        user: UserEntity,
        newRole: UserRole?,
    ): AppResult.Failure? {
        if (newRole == null || newRole.toColumn() == user.role) return null
        if (user.role == UserRoleColumn.ROOT) return AppResult.Failure(AdminError.CannotModifyRoot())
        val demotingAdmin = user.role == UserRoleColumn.ADMIN && newRole == UserRole.MEMBER
        if (demotingAdmin && countActiveAdmins() <= 1) {
            return AppResult.Failure(AdminError.CannotDemoteLastAdmin())
        }
        return null
    }

    /** Count of non-deleted ROOT+ADMIN users. Must run inside a transaction. */
    private fun countActiveAdmins(): Long =
        UserEntity
            .find {
                (UserTable.deletedAt eq null) and
                    (UserTable.role inList listOf(UserRoleColumn.ROOT, UserRoleColumn.ADMIN))
            }.count()

    private fun UserRole.isAdmin(): Boolean = this == UserRole.ROOT || this == UserRole.ADMIN
}
