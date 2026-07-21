@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.server.auth

import com.calypsan.listenup.api.AuthServiceAuthed
import com.calypsan.listenup.api.AuthServicePublic
import com.calypsan.listenup.api.dto.activity.ActivityType
import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.DEVICE_FIELD_MAX
import com.calypsan.listenup.api.dto.auth.DeviceInfo
import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.dto.auth.RefreshRequest
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.RegisterResult
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.dto.auth.RegistrationStatusEvent
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.SessionSummary
import com.calypsan.listenup.api.dto.auth.User
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.streaming.RpcEvent
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.db.UserStatusColumn
import com.calypsan.listenup.server.api.DefaultAllBooksGrantIssuer
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.Sessions
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.services.ActivityRecorder
import com.calypsan.listenup.server.services.AdminUserRosterMaintainer
import com.calypsan.listenup.server.services.PublicProfileMaintainer
import com.calypsan.listenup.server.settings.ServerSettingsRepository
import com.calypsan.listenup.server.sync.ShelfRepository
import com.calypsan.listenup.server.logging.loggerFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onSubscription
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlin.time.ExperimentalTime

private val logger = loggerFor<AuthServiceImpl>()

/**
 * The contract implementation. Pure domain logic — Ktor types are deliberately
 * absent. Caller identity is fetched through [PrincipalProvider] so the service
 * stays unit-testable without a live request scope.
 *
 * Persists over SQLDelight's [ListenUpDatabase] (the `users` table is a plain, non-syncable
 * server-owned aggregate). The Argon2id `password_hash`, the email-uniqueness probe, and the
 * ACTIVE/PENDING/DENIED status gate are all preserved verbatim from the Exposed implementation.
 *
 * Failures are values: every method returns [AppResult] with a typed
 * [com.calypsan.listenup.api.error.AuthError] in the failure variant. No
 * server-side exception wrapper, no RPC interceptor — failures travel as data
 * over both REST and RPC transports.
 */
class AuthServiceImpl(
    internal val db: ListenUpDatabase,
    internal val sessions: SessionService,
    internal val hasher: Argon2Limiter,
    internal val jwt: JwtConfiguration,
    internal val sessionIssuer: SessionIssuer,
    internal val clock: Clock = Clock.System,
    internal val settings: ServerSettingsRepository,
    internal val principalProvider: PrincipalProvider = PrincipalProvider.None,
    internal val requestUserAgent: String? = null,
    /**
     * The caller's remote host, captured at the `/api/rpc/public` mount and threaded in via
     * [withRemoteHost]. Non-null only on the RPC public path, where [loginRateLimiter] enforces the
     * per-IP throttle; null on REST (throttled by the Ktor `RateLimit` plugin) and in unit tests.
     */
    internal val remoteHost: String? = null,
    /**
     * The RPC-path per-IP auth throttle (C3). Non-null in production; null in unit tests and on the
     * REST path, where the throttle is a no-op (the Ktor `RateLimit` plugin covers REST).
     */
    internal val loginRateLimiter: LoginRateLimiter? = null,
    /**
     * Nullable so the auth module can be assembled independently of the shelf
     * module (test environments, phased startup). A null value means starter
     * shelves are silently skipped — registration still succeeds.
     */
    internal val shelfRepository: ShelfRepository? = null,
    internal val publicProfileMaintainer: PublicProfileMaintainer? = null,
    internal val activityRecorder: ActivityRecorder? = null,
    /**
     * Nullable so the auth module assembles independently of the collections module
     * (test environments, phased startup). A null value means MEMBER users are created
     * without a default ALL_BOOKS grant — user creation still succeeds.
     */
    internal val defaultGrantIssuer: DefaultAllBooksGrantIssuer? = null,
    /**
     * Nullable so the auth module assembles independently of the admin-roster module (test
     * environments, phased startup). A null value silently skips the roster-projection refresh.
     */
    internal val adminUserRosterMaintainer: AdminUserRosterMaintainer? = null,
    /**
     * Fan-out of admin approve/deny decisions to a live [observeRegistrationStatus] watcher.
     * Defaulted (rather than required) so the many direct-construction unit tests are unaffected;
     * production DI ([com.calypsan.listenup.server.di.authModule]) binds the shared Koin
     * singleton — the same instance [com.calypsan.listenup.server.api.AdminUserServiceImpl]
     * notifies on a decision.
     */
    internal val registrationBroadcaster: RegistrationBroadcaster = RegistrationBroadcaster(),
    /**
     * Fan-out of admin policy writes to live [observeRegistrationPolicy] watchers. Defaulted for
     * the direct-construction unit tests; production DI binds the shared Koin singleton — the
     * same instance [com.calypsan.listenup.server.api.AdminUserServiceImpl] notifies on a
     * policy change.
     */
    internal val registrationPolicyBroadcaster: RegistrationPolicyBroadcaster = RegistrationPolicyBroadcaster(),
) : AuthServicePublic,
    AuthServiceAuthed {
    override suspend fun login(request: LoginRequest): AppResult<AuthSession> {
        // Throttle BEFORE any Argon2 work so a brute-force burst can't turn into a CPU/memory DoS.
        enforceRate(AuthRateBucket.LOGIN)?.let { return AppResult.Failure(it) }
        if (!Email.isLikelyEmail(request.email)) return AppResult.Failure(AuthError.InvalidCredentials())

        val normalized = Email.normalize(request.email)
        val user =
            suspendTransaction(db) {
                db.usersQueries.selectByEmailNormalized(normalized).executeAsOneOrNull()
            }?.toAuthUser()

        // A soft-deleted account must be indistinguishable from a nonexistent one, and a
        // nonexistent one from a wrong password. Skipping Argon2 on the unknown/deleted path
        // would leak account existence via response time (Argon2 is the dominant cost), so run a
        // throwaway hash of the same input to equalize timing before failing (C12).
        if (user == null || user.deletedAt != null) {
            hasher.hash(request.password)
            return AppResult.Failure(AuthError.InvalidCredentials())
        }

        if (!hasher.verify(request.password, user.passwordHash)) {
            return AppResult.Failure(AuthError.InvalidCredentials())
        }

        when (user.status) {
            UserStatusColumn.DENIED -> return AppResult.Failure(AuthError.AccountDenied())
            UserStatusColumn.PENDING_APPROVAL -> return AppResult.Failure(AuthError.PendingApproval())
            UserStatusColumn.ACTIVE -> Unit
        }

        markLastLogin(user.id, request.timezone)
        // Best-effort self-heal: if this MEMBER's ALL_BOOKS grant was never issued (or was
        // somehow lost), re-assert it on login. The issuer is idempotent — it checks for a
        // live grant first and skips the upsert when one already exists — so this is a cheap
        // no-op on the happy path. ROOT/ADMIN are a no-op inside the issuer (role gate).
        // Placed after status checks and before session issuance so it can't block the caller.
        defaultGrantIssuer?.grantDefaultAllBooks(user.id, user.role)
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
        enforceRate(AuthRateBucket.REGISTER)?.let { return AppResult.Failure(it) }
        if (!Email.isLikelyEmail(request.email)) return AppResult.Failure(AuthError.InvalidCredentials())

        val normalized = Email.normalize(request.email)
        val empty =
            suspendTransaction(db) {
                !db.usersQueries.hasAnyUser().executeAsOne()
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
                db.usersQueries.existsByEmailNormalized(normalized).executeAsOne()
            }
        if (existing) return AppResult.Failure(AuthError.EmailAlreadyExists())

        when (val policyCheck = PasswordPolicy.validate(request.password)) {
            is AppResult.Failure -> return policyCheck
            is AppResult.Success -> Unit
        }

        // Argon2 is CPU-bound and slow on purpose — run it before opening the
        // transaction so we don't hold a DB connection during the hash.
        val passwordHashed = hasher.hash(request.password)
        val now = clock.now().toEpochMilliseconds()
        val status =
            if (policy == RegistrationPolicy.APPROVAL_QUEUE) {
                UserStatusColumn.PENDING_APPROVAL
            } else {
                UserStatusColumn.ACTIVE
            }
        val user =
            newAuthUser(
                email = request.email,
                emailNormalized = normalized,
                passwordHash = passwordHashed,
                role = UserRoleColumn.MEMBER,
                displayName = request.displayName,
                status = status,
                now = now,
            )
        suspendTransaction(db) { insert(user) }

        val outcome =
            if (status == UserStatusColumn.PENDING_APPROVAL) {
                RegisterResult.PendingApproval(userId = UserId(user.id))
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
        createStarterShelfBestEffort(user.id)
        // Both ACTIVE and PENDING_APPROVAL users belong in the admin roster — the admin
        // pending-approvals list reads PENDING_APPROVAL rows straight out of it — so this
        // refresh runs unconditionally, unlike the ACTIVE-only side-effects below.
        adminUserRosterMaintainer?.refreshBestEffort(user.id)
        // Only ACTIVE users get side-effects immediately; PENDING_APPROVAL users
        // get theirs when the admin approves them (via AdminUserServiceImpl).
        if (status == UserStatusColumn.ACTIVE) {
            defaultGrantIssuer?.grantDefaultAllBooks(user.id, UserRoleColumn.MEMBER)
            publicProfileMaintainer?.refreshBestEffort(user.id)
            activityRecorder?.record(user.id, ActivityType.USER_JOINED)
        }
        return AppResult.Success(outcome)
    }

    override suspend fun setupRoot(request: RegisterRequest): AppResult<AuthSession> {
        if (!Email.isLikelyEmail(request.email)) return AppResult.Failure(AuthError.InvalidCredentials())

        val empty =
            suspendTransaction(db) {
                !db.usersQueries.hasAnyUser().executeAsOne()
            }
        if (!empty) return AppResult.Failure(AuthError.SetupAlreadyComplete())

        when (val policyCheck = PasswordPolicy.validate(request.password)) {
            is AppResult.Failure -> return policyCheck
            is AppResult.Success -> Unit
        }

        val passwordHashed = hasher.hash(request.password)
        val normalized = Email.normalize(request.email)
        val now = clock.now().toEpochMilliseconds()
        val user =
            newAuthUser(
                email = request.email,
                emailNormalized = normalized,
                passwordHash = passwordHashed,
                role = UserRoleColumn.ROOT,
                displayName = request.displayName,
                status = UserStatusColumn.ACTIVE,
                now = now,
            )
        // Re-check emptiness INSIDE the insert transaction (C6). The pre-check above is a cheap
        // fast-fail (no Argon2 once setup is done); Argon2 then widens a TOCTOU window during which a
        // second concurrent setupRoot could also observe an empty table. SQLite serializes writers,
        // so re-reading hasAnyUser within the write transaction makes the loser observe the winner's
        // ROOT row and abort — never a second ROOT.
        val inserted =
            suspendTransaction(db) {
                if (db.usersQueries.hasAnyUser().executeAsOne()) {
                    false
                } else {
                    insert(user)
                    true
                }
            }
        if (!inserted) return AppResult.Failure(AuthError.SetupAlreadyComplete())
        createStarterShelfBestEffort(user.id)
        adminUserRosterMaintainer?.refreshBestEffort(user.id)
        publicProfileMaintainer?.refreshBestEffort(user.id)
        activityRecorder?.record(user.id, ActivityType.USER_JOINED)
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
        enforceRate(AuthRateBucket.REFRESH)?.let { return AppResult.Failure(it) }
        val rotated =
            sessions.rotate(request.refreshToken)
                ?: return AppResult.Failure(
                    AuthError.InvalidRefreshToken(familyRevoked = sessions.wasReplay(request.refreshToken)),
                )

        val user =
            suspendTransaction(db) {
                db.usersQueries
                    .selectById(rotated.userId.value)
                    .executeAsOne()
                    .toAuthUser()
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

    /**
     * Emits [userId]'s persisted registration status, then live updates, completing on the first
     * terminal (approved/denied) value — see the [AuthServicePublic.observeRegistrationStatus]
     * KDoc for the completion contract. An unknown [userId] emits a single
     * [AuthError.RegistrationNotFound] and completes, rather than waiting forever.
     */
    override fun observeRegistrationStatus(userId: String): Flow<RpcEvent<RegistrationStatusEvent>> =
        flow {
            // C3-style per-IP throttle (mirrors login/register/refresh): each open subscription
            // runs a poll loop for as long as the registration stays pending, so an unbounded
            // stream of subscribe attempts is a resource-exhaustion vector on its own.
            enforceRate(AuthRateBucket.OBSERVE_REGISTRATION_STATUS)?.let {
                emit(RpcEvent.Error(it))
                return@flow
            }
            val initial = readRegistrationStatus(db, userId)
            if (initial == null) {
                emit(RpcEvent.Error(AuthError.RegistrationNotFound()))
                return@flow
            }
            emit(RpcEvent.Data(initial))
            if (initial.status == STATUS_PENDING) {
                val terminal =
                    merge(
                        registrationBroadcaster.subscribe(userId).map { it.toEvent() },
                        pollUntilTerminal(userId) { id ->
                            readRegistrationStatus(db, id) ?: RegistrationStatusEvent(status = STATUS_PENDING)
                        },
                    ).first()
                emit(RpcEvent.Data(terminal))
            }
        }

    /**
     * Emits the current persisted instance-wide [RegistrationPolicy], then every change — a live
     * broadcast lands instantly, and a periodic persisted re-read ([pollRegistrationPolicy])
     * backstops a missed push. Never completes on its own; see the
     * [AuthServicePublic.observeRegistrationPolicy] KDoc for the consumer contract.
     */
    override fun observeRegistrationPolicy(): Flow<RpcEvent<RegistrationPolicy>> =
        flow {
            // Same C3-style per-IP throttle as observeRegistrationStatus: each open subscription
            // holds a poll loop for the connection's lifetime, so an unbounded stream of subscribe
            // attempts is a resource-exhaustion vector of its own.
            enforceRate(AuthRateBucket.OBSERVE_REGISTRATION_POLICY)?.let {
                emit(RpcEvent.Error(it))
                return@flow
            }
            // Emit the current policy the instant the broadcaster collector registers
            // (onSubscription), closing the subscribe→notify window — a change pushed right after
            // subscribe can't slip through (the broadcaster is replay = 0). The periodic re-read
            // is the never-stranded net; distinctUntilChanged keeps an unchanged re-read silent.
            emitAll(
                merge(
                    registrationPolicyBroadcaster.subscribe().onSubscription { emit(settings.registrationPolicy()) },
                    pollRegistrationPolicy { settings.registrationPolicy() },
                ).distinctUntilChanged()
                    .map { RpcEvent.Data(it) },
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
            remoteHost = remoteHost,
            loginRateLimiter = loginRateLimiter,
            shelfRepository = shelfRepository,
            publicProfileMaintainer = publicProfileMaintainer,
            activityRecorder = activityRecorder,
            defaultGrantIssuer = defaultGrantIssuer,
            adminUserRosterMaintainer = adminUserRosterMaintainer,
            registrationBroadcaster = registrationBroadcaster,
            registrationPolicyBroadcaster = registrationPolicyBroadcaster,
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
            remoteHost = remoteHost,
            loginRateLimiter = loginRateLimiter,
            shelfRepository = shelfRepository,
            publicProfileMaintainer = publicProfileMaintainer,
            activityRecorder = activityRecorder,
            defaultGrantIssuer = defaultGrantIssuer,
            adminUserRosterMaintainer = adminUserRosterMaintainer,
            registrationBroadcaster = registrationBroadcaster,
            registrationPolicyBroadcaster = registrationPolicyBroadcaster,
        )

    /**
     * Bind the caller's [remoteHost] so the RPC public mount's per-IP throttle ([loginRateLimiter])
     * keys on it. The REST path never calls this — its throttle is the Ktor `RateLimit` plugin.
     */
    fun withRemoteHost(remoteHost: String): AuthServiceImpl =
        AuthServiceImpl(
            db = db,
            sessions = sessions,
            hasher = hasher,
            jwt = jwt,
            sessionIssuer = sessionIssuer,
            clock = clock,
            settings = settings,
            principalProvider = principalProvider,
            requestUserAgent = requestUserAgent,
            remoteHost = remoteHost,
            loginRateLimiter = loginRateLimiter,
            shelfRepository = shelfRepository,
            publicProfileMaintainer = publicProfileMaintainer,
            activityRecorder = activityRecorder,
            defaultGrantIssuer = defaultGrantIssuer,
            adminUserRosterMaintainer = adminUserRosterMaintainer,
            registrationBroadcaster = registrationBroadcaster,
            registrationPolicyBroadcaster = registrationPolicyBroadcaster,
        )

    /**
     * Per-IP throttle probe for [bucket]. Returns an [AuthError.RateLimited] to short-circuit the
     * caller when over the ceiling, or null to proceed. A no-op (null) unless BOTH the remote host
     * and the limiter are bound — i.e. only on the RPC public mount.
     */
    private suspend fun enforceRate(bucket: AuthRateBucket): AuthError? {
        val host = remoteHost ?: return null
        val limiter = loginRateLimiter ?: return null
        return when (val decision = limiter.check(bucket, host)) {
            RateDecision.Allowed -> null
            is RateDecision.Throttled -> AuthError.RateLimited(retryAfterSeconds = decision.retryAfterSeconds)
        }
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

    override suspend fun currentUser(): AppResult<User> {
        val p = principalProvider.current() ?: return AppResult.Failure(AuthError.SessionExpired())
        val user =
            suspendTransaction(db) {
                db.usersQueries.selectById(p.userId.value).executeAsOneOrNull()
            }?.toAuthUser() ?: return AppResult.Failure(AuthError.SessionNotFound())
        return AppResult.Success(user.toContract())
    }

    override suspend fun listSessions(): AppResult<List<SessionSummary>> {
        val p = principalProvider.current() ?: return AppResult.Failure(AuthError.SessionExpired())
        val list =
            sessions.listActiveFor(p.userId).map { s ->
                SessionSummary(
                    id = SessionId(s.id),
                    label = s.label,
                    deviceInfo = deviceInfoOf(s),
                    userAgent = s.user_agent,
                    createdAt = s.created_at,
                    lastUsedAt = s.last_used_at,
                    current = s.id == p.sessionId.value,
                )
            }
        return AppResult.Success(list)
    }

    private suspend fun markLastLogin(
        userId: String,
        timezone: String,
    ) {
        val now = clock.now().toEpochMilliseconds()
        suspendTransaction(db) {
            db.usersQueries.updateLastLoginAndTimezone(last_login_at = now, timezone = timezone, id = userId)
        }
    }

    private fun deviceInfoOf(s: Sessions): DeviceInfo? {
        // The read path must not assume stored rows honour the current DeviceInfo
        // invariants: legacy sessions (pre-validation) may hold blank
        // or over-long fields. Sanitize before constructing so DeviceInfo.init's
        // length `require` — the correct *inbound* guard — never throws on outbound data.
        fun field(value: String?): String? = value?.takeIf { it.isNotBlank() }?.take(DEVICE_FIELD_MAX)
        val info =
            DeviceInfo(
                deviceType = field(s.device_type),
                platform = field(s.platform),
                platformVersion = field(s.platform_version),
                clientName = field(s.client_name),
                clientVersion = field(s.client_version),
                deviceName = field(s.device_name),
                deviceModel = field(s.device_model),
            )
        return info.takeIf { it != DeviceInfo() }
    }

    /**
     * Build the in-memory [AuthUser] for a brand-new account with the exact default shape the
     * Exposed `UserEntity.new { … }` path produced: a freshly-minted id, last_login_at NULL,
     * can_edit / can_share true, approval / invite / tagline fields NULL, avatar_type "auto",
     * timezone "UTC". The value is both inserted ([insert]) and handed to [SessionIssuer], so the
     * issued session reflects the persisted row without a re-read.
     */
    private fun newAuthUser(
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
            canShare = true,
            approvedBy = null,
            approvedAt = null,
            deletedAt = null,
        )

    /**
     * Persist [user] into the `users` table. created_at / updated_at are set to the user's
     * createdAt (a fresh insert), and the boolean permission flags map to 0/1 — the same encoding
     * the Exposed `bool` adapter used. Must run inside a [suspendTransaction].
     */
    private fun insert(user: AuthUser) {
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
            can_share = if (user.canShare) 1L else 0L,
            approved_by = user.approvedBy,
            approved_at = user.approvedAt,
            deleted_at = user.deletedAt,
            invited_by = null,
            tagline = null,
            avatar_type = "auto",
            timezone = "UTC",
        )
    }
}
