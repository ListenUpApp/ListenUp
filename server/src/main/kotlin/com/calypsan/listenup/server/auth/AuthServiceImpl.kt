@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.server.auth

import com.calypsan.listenup.api.AuthServiceAuthed
import com.calypsan.listenup.api.AuthServicePublic
import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.DeviceInfo
import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.dto.auth.RefreshRequest
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.RegisterResult
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.SessionSummary
import com.calypsan.listenup.api.dto.auth.User
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserPermissions
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.auth.UserStatus
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.db.SessionEntity
import com.calypsan.listenup.server.db.UserEntity
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.db.UserStatusColumn
import com.calypsan.listenup.server.db.UserTable
import com.calypsan.listenup.server.services.PublicProfileMaintainer
import com.calypsan.listenup.server.settings.ServerSettingsRepository
import com.calypsan.listenup.server.sync.ShelfRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

private val logger = KotlinLogging.logger {}

/**
 * The contract implementation. Pure domain logic — Ktor types are deliberately
 * absent. Caller identity is fetched through [PrincipalProvider] so the service
 * stays unit-testable without a live request scope.
 *
 * Failures are values: every method returns [AppResult] with a typed
 * [com.calypsan.listenup.api.error.AuthError] in the failure variant. No
 * server-side exception wrapper, no RPC interceptor — failures travel as data
 * over both REST and RPC transports.
 */
class AuthServiceImpl(
    internal val db: Database,
    internal val sessions: SessionService,
    internal val hasher: PasswordHasher,
    internal val jwt: JwtConfiguration,
    internal val sessionIssuer: SessionIssuer,
    internal val clock: Clock = Clock.System,
    internal val settings: ServerSettingsRepository,
    internal val principalProvider: PrincipalProvider = PrincipalProvider.None,
    internal val requestUserAgent: String? = null,
    /**
     * Nullable so the auth module can be assembled independently of the shelf
     * module (test environments, phased startup). A null value means starter
     * shelves are silently skipped — registration still succeeds.
     */
    internal val shelfRepository: ShelfRepository? = null,
    internal val publicProfileMaintainer: PublicProfileMaintainer? = null,
) : AuthServicePublic,
    AuthServiceAuthed {
    override suspend fun login(request: LoginRequest): AppResult<AuthSession> {
        if (!Email.isLikelyEmail(request.email)) return AppResult.Failure(AuthError.InvalidCredentials())

        val normalized = Email.normalize(request.email)
        val user =
            suspendTransaction(db) {
                UserEntity.find { UserTable.emailNormalized eq normalized }.firstOrNull()
            } ?: return AppResult.Failure(AuthError.InvalidCredentials())

        // A soft-deleted account must be indistinguishable from a nonexistent one:
        // same InvalidCredentials, so admin deletion is final and existence doesn't leak.
        if (user.deletedAt != null) return AppResult.Failure(AuthError.InvalidCredentials())

        if (!hasher.verify(request.password, user.passwordHash)) {
            return AppResult.Failure(AuthError.InvalidCredentials())
        }

        when (user.status) {
            UserStatusColumn.DENIED -> return AppResult.Failure(AuthError.AccountDenied())
            UserStatusColumn.PENDING_APPROVAL -> return AppResult.Failure(AuthError.PendingApproval())
            UserStatusColumn.ACTIVE -> Unit
        }

        markLastLogin(user.id.value)
        return AppResult.Success(
            sessionIssuer.issue(
                user,
                label = request.sessionLabel,
                deviceInfo = request.deviceInfo,
                userAgent = requestUserAgent,
            ),
        )
    }

    override suspend fun register(request: RegisterRequest): AppResult<RegisterResult> {
        if (!Email.isLikelyEmail(request.email)) return AppResult.Failure(AuthError.InvalidCredentials())

        val normalized = Email.normalize(request.email)
        val empty =
            suspendTransaction(db) {
                UserEntity.all().limit(1).empty()
            }
        if (empty) return AppResult.Failure(AuthError.SetupRequired())

        // Read the policy live so an admin's setRegistrationPolicy takes effect on
        // the next registration without a server restart.
        val policy = settings.registrationPolicy()
        when (policy) {
            RegistrationPolicy.CLOSED -> return AppResult.Failure(AuthError.RegistrationDisabled())
            RegistrationPolicy.OPEN, RegistrationPolicy.APPROVAL_QUEUE -> Unit
        }

        val existing =
            suspendTransaction(db) {
                UserEntity.find { UserTable.emailNormalized eq normalized }.any()
            }
        if (existing) return AppResult.Failure(AuthError.EmailAlreadyExists())

        // Argon2 is CPU-bound and slow on purpose — run it before opening the
        // transaction so we don't hold a DB connection during the hash.
        val passwordHashed = hasher.hash(request.password)
        val now = clock.now().toEpochMilliseconds()
        val user =
            suspendTransaction(db) {
                UserEntity.new(newUserId()) {
                    email = request.email
                    emailNormalized = normalized
                    passwordHash = passwordHashed
                    role = UserRoleColumn.MEMBER
                    displayName = request.displayName
                    status =
                        if (policy == RegistrationPolicy.APPROVAL_QUEUE) {
                            UserStatusColumn.PENDING_APPROVAL
                        } else {
                            UserStatusColumn.ACTIVE
                        }
                    createdAt = now
                    updatedAt = now
                }
            }

        val outcome =
            if (user.status == UserStatusColumn.PENDING_APPROVAL) {
                RegisterResult.PendingApproval(userId = UserId(user.id.value))
            } else {
                RegisterResult.Authenticated(
                    sessionIssuer.issue(
                        user,
                        label = request.sessionLabel,
                        deviceInfo = request.deviceInfo,
                        userAgent = requestUserAgent,
                    ),
                )
            }
        createStarterShelfBestEffort(user.id.value)
        // Only ACTIVE users get a projection row immediately; PENDING_APPROVAL users
        // get their row when the admin approves them (via AdminUserServiceImpl).
        if (user.status == UserStatusColumn.ACTIVE) {
            publicProfileMaintainer?.refreshBestEffort(user.id.value)
        }
        return AppResult.Success(outcome)
    }

    override suspend fun setupRoot(request: RegisterRequest): AppResult<AuthSession> {
        if (!Email.isLikelyEmail(request.email)) return AppResult.Failure(AuthError.InvalidCredentials())

        val empty =
            suspendTransaction(db) {
                UserEntity.all().limit(1).empty()
            }
        if (!empty) return AppResult.Failure(AuthError.SetupAlreadyComplete())

        val passwordHashed = hasher.hash(request.password)
        val now = clock.now().toEpochMilliseconds()
        val user =
            suspendTransaction(db) {
                UserEntity.new(newUserId()) {
                    email = request.email
                    emailNormalized = Email.normalize(request.email)
                    passwordHash = passwordHashed
                    role = UserRoleColumn.ROOT
                    displayName = request.displayName
                    status = UserStatusColumn.ACTIVE
                    createdAt = now
                    updatedAt = now
                }
            }
        createStarterShelfBestEffort(user.id.value)
        publicProfileMaintainer?.refreshBestEffort(user.id.value)
        return AppResult.Success(
            sessionIssuer.issue(
                user,
                label = request.sessionLabel,
                deviceInfo = request.deviceInfo,
                userAgent = requestUserAgent,
            ),
        )
    }

    override suspend fun refreshSession(request: RefreshRequest): AppResult<AuthSession> {
        val rotated =
            sessions.rotate(request.refreshToken)
                ?: return AppResult.Failure(
                    AuthError.InvalidRefreshToken(familyRevoked = sessions.wasReplay(request.refreshToken)),
                )

        val user =
            suspendTransaction(db) {
                UserEntity[rotated.userId.value]
            }
        val role = user.role.toContract()
        val accessJwt = jwt.issue(userId = rotated.userId, sessionId = rotated.sessionId, role = role)
        val accessExp = (clock.now() + jwt.accessTokenTtl).toEpochMilliseconds()
        return AppResult.Success(
            AuthSession(
                accessToken = AccessToken(accessJwt),
                accessTokenExpiresAt = accessExp,
                refreshToken = rotated.refreshToken,
                refreshTokenExpiresAt = rotated.expiresAt,
                sessionId = rotated.sessionId,
                user = user.toContract(),
            ),
        )
    }

    override suspend fun logout(): AppResult<Unit> {
        val p = principalProvider.current() ?: return AppResult.Failure(AuthError.SessionExpired())
        sessions.revoke(p.sessionId, p.userId)
        return AppResult.Success(Unit)
    }

    override suspend fun logoutAll(): AppResult<Unit> {
        val p = principalProvider.current() ?: return AppResult.Failure(AuthError.SessionExpired())
        sessions.revokeAll(p.userId)
        return AppResult.Success(Unit)
    }

    override suspend fun revokeSession(sessionId: SessionId): AppResult<Unit> {
        val p = principalProvider.current() ?: return AppResult.Failure(AuthError.SessionExpired())
        sessions.revoke(sessionId, p.userId)
        return AppResult.Success(Unit)
    }

    /**
     * Produce a copy bound to a specific [PrincipalProvider]. Route handlers
     * call this with `PrincipalProvider { p }` so the per-call principal
     * flows into authenticated methods without [AuthServiceImpl] coupling
     * to Ktor types.
     */
    fun copyWith(provider: PrincipalProvider): AuthServiceImpl =
        AuthServiceImpl(
            db = db,
            sessions = sessions,
            hasher = hasher,
            jwt = jwt,
            sessionIssuer = sessionIssuer,
            clock = clock,
            settings = settings,
            principalProvider = provider,
            requestUserAgent = requestUserAgent,
            shelfRepository = shelfRepository,
            publicProfileMaintainer = publicProfileMaintainer,
        )

    /** Bind the captured User-Agent (REST path only) so login/register/setup persist it. */
    fun withUserAgent(userAgent: String?): AuthServiceImpl =
        AuthServiceImpl(
            db = db,
            sessions = sessions,
            hasher = hasher,
            jwt = jwt,
            sessionIssuer = sessionIssuer,
            clock = clock,
            settings = settings,
            principalProvider = principalProvider,
            requestUserAgent = userAgent,
            shelfRepository = shelfRepository,
            publicProfileMaintainer = publicProfileMaintainer,
        )

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

    override suspend fun currentUser(): AppResult<User> {
        val p = principalProvider.current() ?: return AppResult.Failure(AuthError.SessionExpired())
        val user =
            suspendTransaction(db) {
                UserEntity.findById(p.userId.value)
            } ?: return AppResult.Failure(AuthError.SessionNotFound())
        return AppResult.Success(user.toContract())
    }

    override suspend fun listSessions(): AppResult<List<SessionSummary>> {
        val p = principalProvider.current() ?: return AppResult.Failure(AuthError.SessionExpired())
        val list =
            sessions.listActiveFor(p.userId).map { s ->
                SessionSummary(
                    id = SessionId(s.id.value),
                    label = s.label,
                    deviceInfo = deviceInfoOf(s),
                    userAgent = s.userAgent,
                    createdAt = s.createdAt,
                    lastUsedAt = s.lastUsedAt,
                    current = s.id.value == p.sessionId.value,
                )
            }
        return AppResult.Success(list)
    }

    private suspend fun markLastLogin(userId: String) {
        val now = clock.now().toEpochMilliseconds()
        suspendTransaction(db) {
            UserEntity[userId].lastLoginAt = now
        }
    }

    private fun deviceInfoOf(s: SessionEntity): DeviceInfo? {
        val info =
            DeviceInfo(
                deviceType = s.deviceType,
                platform = s.platform,
                platformVersion = s.platformVersion,
                clientName = s.clientName,
                clientVersion = s.clientVersion,
                deviceName = s.deviceName,
                deviceModel = s.deviceModel,
            )
        return info.takeIf { it != DeviceInfo() }
    }

    // Phase 1 placeholder. UUIDv7 (lexicographically sortable, time-ordered) is the
    // long-term shape — UUIDv4 is fine while ids are TEXT primary keys with no
    // timestamp-driven scan path.
    private fun newUserId(): String = UUID.randomUUID().toString()
}

internal fun UserRoleColumn.toContract(): UserRole =
    when (this) {
        UserRoleColumn.ROOT -> UserRole.ROOT
        UserRoleColumn.ADMIN -> UserRole.ADMIN
        UserRoleColumn.MEMBER -> UserRole.MEMBER
    }

internal fun UserRole.toColumn(): UserRoleColumn =
    when (this) {
        UserRole.ROOT -> UserRoleColumn.ROOT
        UserRole.ADMIN -> UserRoleColumn.ADMIN
        UserRole.MEMBER -> UserRoleColumn.MEMBER
    }

internal fun UserStatusColumn.toContract(): UserStatus =
    when (this) {
        UserStatusColumn.ACTIVE -> UserStatus.ACTIVE
        UserStatusColumn.PENDING_APPROVAL -> UserStatus.PENDING_APPROVAL
        UserStatusColumn.DENIED -> UserStatus.DENIED
    }

internal fun UserEntity.toContract(): User =
    User(
        id = UserId(id.value),
        email = email,
        displayName = displayName,
        role = role.toContract(),
        status = status.toContract(),
        createdAt = createdAt,
        permissions = UserPermissions(canEdit = canEdit, canShare = canShare),
        approvedBy = approvedBy,
        approvedAt = approvedAt,
    )

/**
 * Strategy for handlers asking "who is the current caller?" without coupling
 * [AuthServiceImpl] to Ktor types. The default is [None] (used in unit tests);
 * Phase D wires the Ktor-backed implementation that reads [UserPrincipal] from
 * `call.principal()`.
 */
fun interface PrincipalProvider {
    fun current(): UserPrincipal?

    object None : PrincipalProvider {
        override fun current(): UserPrincipal? = null
    }
}
