package com.calypsan.listenup.server.auth

import com.calypsan.listenup.api.dto.activity.ActivityType
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.RegisterResult
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.notifications.NotificationEvent
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.api.DefaultAllBooksGrantIssuer
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.db.UserStatusColumn
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.logging.loggerFor
import com.calypsan.listenup.server.notifications.NotificationAudience
import com.calypsan.listenup.server.notifications.NotificationEmitter
import com.calypsan.listenup.server.services.ActivityRecorder
import com.calypsan.listenup.server.services.AdminUserRosterMaintainer
import com.calypsan.listenup.server.services.PublicProfileMaintainer
import com.calypsan.listenup.server.sync.ShelfRepository
import kotlinx.coroutines.CancellationException
import kotlin.uuid.Uuid

private val logger = loggerFor<AuthRegistrationSideEffects>()

/**
 * Handles session issuance and best-effort post-insert side effects after a user registration
 * or initial root setup commits in [AuthServiceImpl].
 */
internal class AuthRegistrationSideEffects(
    private val sessionIssuer: SessionIssuer,
    private val shelfRepository: ShelfRepository?,
    private val publicProfileMaintainer: PublicProfileMaintainer?,
    private val activityRecorder: ActivityRecorder?,
    private val defaultGrantIssuer: DefaultAllBooksGrantIssuer?,
    private val adminUserRosterMaintainer: AdminUserRosterMaintainer?,
    private val notifications: NotificationEmitter?,
) {
    /** Issues the session (or queues the applicant) and runs the side-effects of a freshly inserted sign-up. */
    suspend fun completeRegistration(
        request: RegisterRequest,
        user: AuthUser,
        status: UserStatusColumn,
        requestUserAgent: String?,
    ): AppResult<RegisterResult> {
        val outcome =
            if (status == UserStatusColumn.PENDING_APPROVAL) {
                RegisterResult.PendingApproval(userId = UserId(user.id))
            } else {
                RegisterResult.Authenticated(
                    sessionIssuer.issue(
                        user = user,
                        label = request.sessionLabel,
                        deviceInfo = request.deviceInfo,
                        userAgent = requestUserAgent,
                    ),
                )
            }
        createStarterShelfBestEffort(user.id)
        // Both ACTIVE and PENDING_APPROVAL users belong in the admin roster — the admin
        // pending-approvals list reads PENDING_APPROVAL rows straight out of it — so this
        // refresh runs unconditionally, unlike the ACTIVE-only side-effects below.
        adminUserRosterMaintainer?.refreshBestEffort(user.id)
        if (status == UserStatusColumn.PENDING_APPROVAL) {
            notifyAdminsOfPendingRegistration(user.id)
        }
        // Only ACTIVE users get side-effects immediately; PENDING_APPROVAL users
        // get theirs when the admin approves them (via AdminUserServiceImpl).
        if (status == UserStatusColumn.ACTIVE) {
            defaultGrantIssuer?.grantDefaultAllBooks(user.id, UserRoleColumn.MEMBER)
            publicProfileMaintainer?.refreshBestEffort(user.id)
            activityRecorder?.record(user.id, ActivityType.USER_JOINED)
        }
        return AppResult.Success(outcome)
    }

    /** Runs post-insert setup side-effects and issues the initial ROOT session. */
    suspend fun completeRootSetup(
        request: RegisterRequest,
        user: AuthUser,
        requestUserAgent: String?,
    ): AppResult<AuthSession> {
        createStarterShelfBestEffort(user.id)
        adminUserRosterMaintainer?.refreshBestEffort(user.id)
        publicProfileMaintainer?.refreshBestEffort(user.id)
        activityRecorder?.record(user.id, ActivityType.USER_JOINED)
        return AppResult.Success(
            sessionIssuer.issue(
                user = user,
                label = request.sessionLabel,
                deviceInfo = request.deviceInfo,
                userAgent = requestUserAgent,
            ),
        )
    }

    /**
     * Notifies every admin so a pending registration is noticed rather than waited on (#1068).
     *
     * This closes the registration loop. [NotificationEvent.RegistrationDecision] already reaches the
     * registrant once a decision exists — but nothing woke the person who has to make it, so an admin
     * whose app was closed learned of a request only by happening to open the Admin screen. The
     * request was never lost (it lands in the synced admin roster either way), it could simply sit
     * unseen for days.
     *
     * Best-effort, and deliberately after the row is committed: a notification is a wake-up
     * accelerant, not the record. The emitter owns audience fan-out, per-user preference checks,
     * inbox-row minting, and push delivery — and its contract already forbids throwing (short of
     * cancellation), so a relay outage can never fail someone's registration.
     */
    private suspend fun notifyAdminsOfPendingRegistration(pendingUserId: String) {
        notifications?.emit(
            NotificationEvent.RegistrationApproval(userId = pendingUserId),
            to = NotificationAudience.Admins,
        )
    }

    /**
     * Best-effort starter-shelf creation — called immediately after a new user row is
     * committed. Failure is logged and swallowed so a shelf-infra hiccup never
     * rolls back or fails registration. [CancellationException] is re-raised so
     * structured-concurrency cancellation is never eaten.
     */
    private suspend fun createStarterShelfBestEffort(userId: String) {
        shelfRepository ?: return
        try {
            shelfRepository.createStarterShelf(userId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "starter shelf creation failed for user $userId — registration still succeeds" }
        }
    }
}

/**
 * Build the in-memory [AuthUser] for a brand-new account with its default shape: a
 * freshly-minted id, last_login_at NULL, can_edit true, approval / invite / tagline fields NULL,
 * avatar_type "auto", timezone "UTC". The value is both inserted ([insertAuthUser]) and handed to
 * [SessionIssuer], so the issued session reflects the persisted row without a re-read.
 */
internal fun newAuthUser(
    email: String,
    emailNormalized: String,
    passwordHash: String,
    role: UserRoleColumn,
    displayName: String,
    status: UserStatusColumn,
    now: Long,
): AuthUser =
    AuthUser(
        // UUIDv4. UUIDv7 (lexicographically sortable, time-ordered) would be the
        // long-term shape — UUIDv4 is fine while ids are TEXT primary keys with no
        // timestamp-driven scan path.
        id = Uuid.random().toString(),
        email = email,
        emailNormalized = emailNormalized,
        passwordHash = passwordHash,
        role = role,
        displayName = displayName,
        status = status,
        createdAt = now,
        canEdit = true,
        approvedBy = null,
        approvedAt = null,
        deletedAt = null,
    )

/**
 * Persist [user] into the `users` table. created_at / updated_at are set to the user's
 * createdAt (a fresh insert), and the boolean permission flags map to 0/1. Must run inside a
 * `suspendTransaction`.
 */
internal fun insertAuthUser(
    db: ListenUpDatabase,
    user: AuthUser,
) {
    db.usersQueries.insert(
        id = user.id,
        email = user.email,
        email_normalized = user.emailNormalized,
        password_hash = user.passwordHash,
        role = user.role.name,
        display_name = user.displayName,
        status = user.status.name,
        created_at = user.createdAt,
        updated_at = user.createdAt,
        last_login_at = null,
        can_edit = if (user.canEdit) 1L else 0L,
        approved_by = user.approvedBy,
        approved_at = user.approvedAt,
        deleted_at = user.deletedAt,
        invited_by = null,
        tagline = null,
        avatar_type = "auto",
        timezone = "UTC",
    )
}
