@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.AdminUserService
import com.calypsan.listenup.api.dto.activity.ActivityType
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
import com.calypsan.listenup.server.auth.RegistrationPolicyBroadcaster
import com.calypsan.listenup.server.services.ActivityRecorder
import com.calypsan.listenup.server.services.AdminUserRosterMaintainer
import com.calypsan.listenup.server.services.PublicProfileMaintainer
import com.calypsan.listenup.server.auth.RegistrationDecision
import com.calypsan.listenup.server.auth.SessionService
import com.calypsan.listenup.server.auth.AuthUser
import com.calypsan.listenup.server.auth.toAuthUser
import com.calypsan.listenup.server.auth.toColumn
import com.calypsan.listenup.server.auth.toContract
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.db.UserStatusColumn
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.settings.ServerSettingsRepository
import com.calypsan.listenup.server.sync.ChangeBus
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
    private val sql: ListenUpDatabase,
    private val sessions: SessionService,
    private val settings: ServerSettingsRepository,
    private val registrationBroadcaster: RegistrationBroadcaster,
    private val registrationPolicyBroadcaster: RegistrationPolicyBroadcaster,
    private val bus: ChangeBus,
    private val clock: Clock = Clock.System,
    private val principal: PrincipalProvider = PrincipalProvider.None,
    private val publicProfileMaintainer: PublicProfileMaintainer? = null,
    private val activityRecorder: ActivityRecorder? = null,
    /**
     * Nullable so the auth module assembles independently of the collections module
     * (test environments, phased startup). A null value means approved MEMBER users do
     * not receive a default ALL_BOOKS grant — approval still succeeds.
     */
    private val defaultGrantIssuer: DefaultAllBooksGrantIssuer? = null,
    /**
     * Nullable so the auth module assembles independently of the admin-roster module (test
     * environments, phased startup). A null value means admin-roster changes here are not
     * published — the roster self-heals via [AdminUserRosterMaintainer.backfillAll] at startup.
     */
    private val adminUserRosterMaintainer: AdminUserRosterMaintainer? = null,
) : AdminUserService {
    /** Returns a copy scoped to the given [provider]. Route handlers call this per-request. */
    fun copyWith(provider: PrincipalProvider): AdminUserServiceImpl =
        AdminUserServiceImpl(
            sql,
            sessions,
            settings,
            registrationBroadcaster,
            registrationPolicyBroadcaster,
            bus,
            clock,
            provider,
            publicProfileMaintainer,
            activityRecorder,
            defaultGrantIssuer,
            adminUserRosterMaintainer,
        )

    override suspend fun listUsers(): AppResult<List<User>> {
        requireAdmin()?.let { return it }
        return suspendTransaction(sql) {
            AppResult.Success(
                // ACTIVE only: pending/denied registrations have their own surfaces and must not
                // appear in the active roster. Soft-deleted users are excluded as always.
                sql.usersQueries
                    .selectActiveLive()
                    .executeAsList()
                    .map { it.toAuthUser().toContract() },
            )
        }
    }

    override suspend fun listPendingUsers(): AppResult<List<User>> {
        requireAdmin()?.let { return it }
        return suspendTransaction(sql) {
            AppResult.Success(
                sql.usersQueries
                    .selectPendingLive()
                    .executeAsList()
                    .map { it.toAuthUser().toContract() },
            )
        }
    }

    override suspend fun getUser(id: UserId): AppResult<User> {
        requireAdmin()?.let { return it }
        return suspendTransaction(sql) {
            val user =
                activeUser(id)
                    ?: return@suspendTransaction AppResult.Failure(AdminError.UserNotFound())
            AppResult.Success(user.toContract())
        }
    }

    override suspend fun searchUsers(query: String): AppResult<List<User>> {
        requireAdmin()?.let { return it }
        val needle = query.trim()
        return suspendTransaction(sql) {
            AppResult.Success(
                // ACTIVE only: search is an active-roster operation; pending/denied registrations
                // have their own surfaces and must not leak in (sibling of listUsers).
                sql.usersQueries
                    .selectActiveLive()
                    .executeAsList()
                    .map { it.toAuthUser() }
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
        val caller = principal.current() ?: return AppResult.Failure(AuthError.SessionExpired())
        // Captured inside the txn, acted on after commit: a privilege demotion must sever the
        // demoted user's live sessions so a still-valid access token can't outlive its role (≤15m
        // stale-privilege window otherwise — the access JWT embeds the role claim).
        var demoted = false
        val outcome: AppResult<User> =
            suspendTransaction(sql) {
                val user =
                    activeUser(id)
                        ?: return@suspendTransaction AppResult.Failure(AdminError.UserNotFound())

                val roleChange = roleChangeFor(user, patch.role, caller.role)
                roleChange?.let { return@suspendTransaction it }

                // Merge only the non-null patch fields, then write the merged row back.
                val mergedDisplayName = patch.displayName ?: user.displayName
                val mergedRole = patch.role?.toColumn() ?: user.role
                demoted = user.role == UserRoleColumn.ADMIN && mergedRole == UserRoleColumn.MEMBER
                val mergedCanEdit = patch.permissions?.canEdit ?: user.canEdit
                val mergedCanShare = patch.permissions?.canShare ?: user.canShare
                val now = clock.now().toEpochMilliseconds()
                sql.usersQueries.updateAdminFields(
                    display_name = mergedDisplayName,
                    role = mergedRole.name,
                    can_edit = mergedCanEdit.toDbLong(),
                    can_share = mergedCanShare.toDbLong(),
                    updated_at = now,
                    id = id.value,
                )
                AppResult.Success(
                    user
                        .copy(
                            displayName = mergedDisplayName,
                            role = mergedRole,
                            canEdit = mergedCanEdit,
                            canShare = mergedCanShare,
                        ).toContract(),
                )
            }

        // Publish AFTER commit, mirroring deleteUser's capture-then-act shape: the merged-row
        // write is the durable fact, so the roster refresh only fires once it's actually landed.
        if (outcome is AppResult.Success) {
            if (demoted) sessions.revokeAll(id)
            adminUserRosterMaintainer?.refreshBestEffort(id.value)
        }
        return outcome
    }

    override suspend fun deleteUser(id: UserId): AppResult<Unit> {
        val caller = principal.current() ?: return AppResult.Failure(AuthError.SessionExpired())
        if (!caller.role.isAdmin()) return AppResult.Failure(AuthError.PermissionDenied())
        if (caller.userId == id) return AppResult.Failure(AdminError.CannotDeleteSelf())

        val outcome: AppResult<Unit> =
            suspendTransaction(sql) {
                val user =
                    activeUser(id)
                        ?: return@suspendTransaction AppResult.Failure(AdminError.UserNotFound())
                if (user.role == UserRoleColumn.ROOT) {
                    return@suspendTransaction AppResult.Failure(AdminError.CannotModifyRoot())
                }
                if (user.role == UserRoleColumn.ADMIN && countActiveAdmins() <= 1) {
                    return@suspendTransaction AppResult.Failure(AdminError.CannotDeleteLastAdmin())
                }
                sql.usersQueries.markDeletedAt(deleted_at = clock.now().toEpochMilliseconds(), id = id.value)
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
            publicProfileMaintainer?.tombstoneBestEffort(id.value)
            adminUserRosterMaintainer?.removeBestEffort(id.value)
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
        // Capture the approved user's role so the best-effort grant can run after the
        // transaction commits — mirrors InviteServiceImpl.claimInvite's pattern.
        var approvedUserRole: UserRoleColumn? = null
        val outcome: AppResult<PendingRegistrationOutcome> =
            suspendTransaction(sql) {
                val target =
                    sql.usersQueries
                        .selectById(request.userId.value)
                        .executeAsOneOrNull()
                        ?.toAuthUser()
                        ?: return@suspendTransaction AppResult.Failure(AuthError.PermissionDenied())
                if (target.status != UserStatusColumn.PENDING_APPROVAL) {
                    return@suspendTransaction AppResult.Failure(AuthError.PermissionDenied())
                }

                val newStatus = if (request.approved) UserStatusColumn.ACTIVE else UserStatusColumn.DENIED
                sql.usersQueries.updateRegistrationDecision(
                    status = newStatus.name,
                    updated_at = now,
                    approved_by = caller.userId.value,
                    approved_at = now,
                    id = request.userId.value,
                )

                if (request.approved) approvedUserRole = target.role

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
            // Refresh the public-profile projection only on approval; denied users are never
            // active and should not appear in the public roster.
            if (request.approved) {
                val role = approvedUserRole
                if (role != null) defaultGrantIssuer?.grantDefaultAllBooks(request.userId.value, role)
                publicProfileMaintainer?.refreshBestEffort(request.userId.value)
                activityRecorder?.record(request.userId.value, ActivityType.USER_JOINED)
                adminUserRosterMaintainer?.refreshBestEffort(request.userId.value)
            } else {
                // Denied users are never active and must leave the admin roster.
                adminUserRosterMaintainer?.removeBestEffort(request.userId.value)
            }
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
        // Push the new policy to any clients sitting on the login screen so a stale Sign Up
        // button flips the moment registration is closed (or reopened). Persisted first, so a
        // reconnecting subscriber re-reads the same durable value.
        registrationPolicyBroadcaster.notify(policy)
        return AppResult.Success(Unit)
    }

    // ── Private helpers ─────────────────────────────────────────────────────────

    /** null = allowed; a Failure (PermissionDenied / SessionExpired) otherwise. */
    private fun requireAdmin(): AppResult.Failure? {
        val caller = principal.current() ?: return AppResult.Failure(AuthError.SessionExpired())
        return if (caller.role.isAdmin()) null else AppResult.Failure(AuthError.PermissionDenied())
    }

    /** The live (non-deleted) user with [id], or null. Must run inside a SQLDelight transaction. */
    private fun activeUser(id: UserId): AuthUser? =
        sql.usersQueries
            .selectById(id.value)
            .executeAsOneOrNull()
            ?.toAuthUser()
            ?.takeIf { it.deletedAt == null }

    /**
     * Validates a role change against the safety rails. Returns null when the
     * change is allowed (or no role change is requested); a Failure otherwise.
     * Must run inside a transaction (reads [countActiveAdmins]).
     */
    private fun roleChangeFor(
        user: AuthUser,
        newRole: UserRole?,
        callerRole: UserRole,
    ): AppResult.Failure? {
        if (newRole == null || newRole.toColumn() == user.role) return null
        if (user.role == UserRoleColumn.ROOT) return AppResult.Failure(AdminError.CannotModifyRoot())
        // ROOT is a protected tier: only a ROOT caller may promote someone to ROOT.
        if (newRole == UserRole.ROOT && callerRole != UserRole.ROOT) {
            return AppResult.Failure(AuthError.PermissionDenied())
        }
        val demotingAdmin = user.role == UserRoleColumn.ADMIN && newRole == UserRole.MEMBER
        if (demotingAdmin && countActiveAdmins() <= 1) {
            return AppResult.Failure(AdminError.CannotDemoteLastAdmin())
        }
        return null
    }

    /** Count of non-deleted ROOT+ADMIN users. Must run inside a SQLDelight transaction. */
    private fun countActiveAdmins(): Long = sql.usersQueries.countActiveAdmins().executeAsOne()

    private fun UserRole.isAdmin(): Boolean = this == UserRole.ROOT || this == UserRole.ADMIN
}

/** Boolean → SQLite INTEGER (0/1) at the persistence boundary. */
private fun Boolean.toDbLong(): Long = if (this) 1L else 0L
