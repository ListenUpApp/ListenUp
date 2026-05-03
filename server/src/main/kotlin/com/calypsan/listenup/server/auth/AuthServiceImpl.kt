package com.calypsan.listenup.server.auth

import com.calypsan.listenup.api.AuthService
import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.dto.auth.PendingRegistrationDecision
import com.calypsan.listenup.api.dto.auth.PendingRegistrationOutcome
import com.calypsan.listenup.api.dto.auth.RefreshRequest
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.RegisterResult
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.SessionSummary
import com.calypsan.listenup.api.dto.auth.User
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.auth.UserStatus
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.server.db.UserEntity
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.db.UserStatusColumn
import com.calypsan.listenup.server.db.UserTable
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.Clock
import java.util.UUID

/** Instance-level registration mode. Drives `register()` branching. */
enum class RegistrationPolicy { OPEN, APPROVAL_QUEUE, CLOSED }

/**
 * The contract implementation. Pure domain logic — Ktor types are deliberately
 * absent. Caller identity is fetched through [PrincipalProvider] so the service
 * stays unit-testable without a live request scope.
 *
 * Failures cross the suspend-call boundary as [AuthException] wrapping a typed
 * [AuthError]. The Phase D RPC exception interceptor unwraps these for the wire.
 */
class AuthServiceImpl(
    internal val db: Database,
    internal val sessions: SessionService,
    internal val hasher: PasswordHasher,
    internal val jwt: JwtConfiguration,
    internal val clock: Clock = Clock.systemUTC(),
    internal val registrationPolicy: RegistrationPolicy = RegistrationPolicy.OPEN,
    internal val principalProvider: PrincipalProvider = PrincipalProvider.None,
) : AuthService {
    override suspend fun login(request: LoginRequest): AuthSession {
        if (!Email.isLikelyEmail(request.email)) throw AuthException(AuthError.InvalidCredentials())

        val normalized = Email.normalize(request.email)
        val user =
            newSuspendedTransaction(Dispatchers.IO, db) {
                UserEntity.find { UserTable.emailNormalized eq normalized }.firstOrNull()
            } ?: throw AuthException(AuthError.InvalidCredentials())

        if (!hasher.verify(request.password, user.passwordHash)) {
            throw AuthException(AuthError.InvalidCredentials())
        }

        when (user.status) {
            UserStatusColumn.DENIED -> throw AuthException(AuthError.AccountDenied())
            UserStatusColumn.PENDING_APPROVAL -> throw AuthException(AuthError.PendingApproval())
            UserStatusColumn.ACTIVE -> Unit
        }

        markLastLogin(user.id.value)
        return issueSession(user, label = request.sessionLabel)
    }

    override suspend fun register(request: RegisterRequest): RegisterResult {
        if (!Email.isLikelyEmail(request.email)) throw AuthException(AuthError.InvalidCredentials())

        val normalized = Email.normalize(request.email)
        val empty =
            newSuspendedTransaction(Dispatchers.IO, db) {
                UserEntity.all().limit(1).empty()
            }
        if (empty) throw AuthException(AuthError.SetupRequired())

        when (registrationPolicy) {
            RegistrationPolicy.CLOSED -> throw AuthException(AuthError.RegistrationDisabled())
            RegistrationPolicy.OPEN, RegistrationPolicy.APPROVAL_QUEUE -> Unit
        }

        val existing =
            newSuspendedTransaction(Dispatchers.IO, db) {
                UserEntity.find { UserTable.emailNormalized eq normalized }.any()
            }
        if (existing) throw AuthException(AuthError.EmailAlreadyExists())

        // Argon2 is CPU-bound and slow on purpose — run it before opening the
        // transaction so we don't hold a DB connection during the hash.
        val passwordHashed = hasher.hash(request.password)
        val now = clock.millis()
        val user =
            newSuspendedTransaction(Dispatchers.IO, db) {
                UserEntity.new(newUserId()) {
                    email = request.email
                    emailNormalized = normalized
                    passwordHash = passwordHashed
                    role = UserRoleColumn.MEMBER
                    displayName = request.displayName
                    status =
                        if (registrationPolicy == RegistrationPolicy.APPROVAL_QUEUE) {
                            UserStatusColumn.PENDING_APPROVAL
                        } else {
                            UserStatusColumn.ACTIVE
                        }
                    createdAt = now
                    updatedAt = now
                }
            }

        return if (user.status == UserStatusColumn.PENDING_APPROVAL) {
            RegisterResult.PendingApproval
        } else {
            RegisterResult.Authenticated(issueSession(user, label = request.sessionLabel))
        }
    }

    override suspend fun setupRoot(request: RegisterRequest): AuthSession {
        if (!Email.isLikelyEmail(request.email)) throw AuthException(AuthError.InvalidCredentials())

        val empty =
            newSuspendedTransaction(Dispatchers.IO, db) {
                UserEntity.all().limit(1).empty()
            }
        if (!empty) throw AuthException(AuthError.SetupAlreadyComplete())

        val passwordHashed = hasher.hash(request.password)
        val now = clock.millis()
        val user =
            newSuspendedTransaction(Dispatchers.IO, db) {
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
        return issueSession(user, label = request.sessionLabel)
    }

    override suspend fun refreshSession(request: RefreshRequest): AuthSession {
        val rotated =
            sessions.rotate(request.refreshToken)
                ?: throw AuthException(
                    AuthError.InvalidRefreshToken(familyRevoked = sessions.wasReplay(request.refreshToken)),
                )

        val user =
            newSuspendedTransaction(Dispatchers.IO, db) {
                UserEntity[rotated.userId.value]
            }
        val role = user.role.toContract()
        val accessJwt = jwt.issue(userId = rotated.userId, sessionId = rotated.sessionId, role = role)
        val accessExp = clock.instant().plus(jwt.accessTokenTtl).toEpochMilli()
        return AuthSession(
            accessToken = AccessToken(accessJwt),
            accessTokenExpiresAt = accessExp,
            refreshToken = rotated.refreshToken,
            refreshTokenExpiresAt = rotated.expiresAt,
            sessionId = rotated.sessionId,
            user = user.toContract(),
        )
    }

    override suspend fun logout() {
        val p = principalProvider.current() ?: throw AuthException(AuthError.SessionExpired())
        sessions.revoke(p.sessionId, p.userId)
    }

    override suspend fun logoutAll() {
        val p = principalProvider.current() ?: throw AuthException(AuthError.SessionExpired())
        sessions.revokeAll(p.userId)
    }

    /** Test affordance: produce a copy with a different [PrincipalProvider]. */
    internal fun copyWith(provider: PrincipalProvider): AuthServiceImpl =
        AuthServiceImpl(
            db = db,
            sessions = sessions,
            hasher = hasher,
            jwt = jwt,
            clock = clock,
            registrationPolicy = registrationPolicy,
            principalProvider = provider,
        )

    override suspend fun currentUser(): User {
        val p = principalProvider.current() ?: throw AuthException(AuthError.SessionExpired())
        val user =
            newSuspendedTransaction(Dispatchers.IO, db) {
                UserEntity.findById(p.userId.value)
            } ?: throw AuthException(AuthError.SessionNotFound())
        return user.toContract()
    }

    override suspend fun listSessions(): List<SessionSummary> {
        val p = principalProvider.current() ?: throw AuthException(AuthError.SessionExpired())
        return sessions.listActiveFor(p.userId).map { s ->
            SessionSummary(
                id = SessionId(s.id.value),
                label = s.label,
                createdAt = s.createdAt,
                lastUsedAt = s.lastUsedAt,
                current = s.id.value == p.sessionId.value,
            )
        }
    }

    override suspend fun decidePendingRegistration(request: PendingRegistrationDecision): PendingRegistrationOutcome {
        val p = principalProvider.current() ?: throw AuthException(AuthError.SessionExpired())
        if (p.role != UserRole.ROOT && p.role != UserRole.ADMIN) {
            throw AuthException(AuthError.PermissionDenied())
        }

        // Don't leak existence-or-state of the target — admin actions only succeed
        // against a genuinely pending row; everything else is PermissionDenied.
        val target =
            newSuspendedTransaction(Dispatchers.IO, db) {
                UserEntity.findById(request.userId.value)
            } ?: throw AuthException(AuthError.PermissionDenied())
        if (target.status != UserStatusColumn.PENDING_APPROVAL) {
            throw AuthException(AuthError.PermissionDenied())
        }

        val now = clock.millis()
        val newStatus = if (request.approved) UserStatusColumn.ACTIVE else UserStatusColumn.DENIED
        newSuspendedTransaction(Dispatchers.IO, db) {
            target.status = newStatus
            target.updatedAt = now
        }
        return if (request.approved) PendingRegistrationOutcome.Approved else PendingRegistrationOutcome.Denied
    }

    private suspend fun issueSession(
        userEntity: UserEntity,
        label: String?,
    ): AuthSession {
        val userId = UserId(userEntity.id.value)
        val role = userEntity.role.toContract()
        val issued = sessions.createSession(userId, label = label)
        val accessJwt = jwt.issue(userId = userId, sessionId = issued.sessionId, role = role)
        val expiresAt = clock.instant().plus(jwt.accessTokenTtl).toEpochMilli()
        return AuthSession(
            accessToken = AccessToken(accessJwt),
            accessTokenExpiresAt = expiresAt,
            refreshToken = issued.refreshToken,
            refreshTokenExpiresAt = issued.expiresAt,
            sessionId = issued.sessionId,
            user = userEntity.toContract(),
        )
    }

    private suspend fun markLastLogin(userId: String) {
        val now = clock.millis()
        newSuspendedTransaction(Dispatchers.IO, db) {
            UserEntity[userId].lastLoginAt = now
        }
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
