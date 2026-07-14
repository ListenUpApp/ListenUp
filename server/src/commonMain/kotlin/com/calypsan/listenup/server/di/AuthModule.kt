@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.server.di

import com.calypsan.listenup.api.AdminSettingsService
import com.calypsan.listenup.api.InstanceService
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.server.api.AdminSettingsServiceImpl
import com.calypsan.listenup.server.api.AdminUserServiceImpl
import com.calypsan.listenup.server.api.DefaultAllBooksGrantIssuer
import com.calypsan.listenup.server.api.InviteServiceImpl
import com.calypsan.listenup.server.api.InstanceServiceImpl
import com.calypsan.listenup.server.sync.CollectionGrantRepository
import com.calypsan.listenup.server.sync.CollectionRepository
import com.calypsan.listenup.server.services.LibraryRegistry
import com.calypsan.listenup.server.auth.Argon2Limiter
import com.calypsan.listenup.server.auth.AuthServiceImpl
import com.calypsan.listenup.server.auth.DEFAULT_ARGON2_PARALLELISM
import com.calypsan.listenup.server.auth.InviteCodeGenerator
import com.calypsan.listenup.server.auth.LoginRateLimiter
import com.calypsan.listenup.server.auth.JwtConfiguration
import com.calypsan.listenup.server.auth.PasswordHasher
import com.calypsan.listenup.server.auth.RefreshTokenGenerator
import com.calypsan.listenup.server.auth.RefreshTokenHasher
import com.calypsan.listenup.server.auth.RegistrationBroadcaster
import com.calypsan.listenup.server.auth.RegistrationPolicyBroadcaster
import com.calypsan.listenup.server.auth.SessionIssuer
import com.calypsan.listenup.server.auth.SessionService
import com.calypsan.listenup.server.auth.resolveServerSecrets
import com.calypsan.listenup.server.db.DatabaseConfig
import com.calypsan.listenup.server.db.DatabaseFactory
import com.calypsan.listenup.server.db.DatabaseHandle
import com.calypsan.listenup.server.db.resolveDatabaseUrl
import com.calypsan.listenup.server.db.resolveListenupHome
import app.cash.sqldelight.db.SqlDriver
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.io.readEnv
import com.calypsan.listenup.server.io.userHomeDir
import com.calypsan.listenup.server.push.PushConfig
import com.calypsan.listenup.server.scheduler.ExpiredSessionCleanupTask
import com.calypsan.listenup.server.settings.ServerSettingsRepository
import com.calypsan.listenup.server.sync.ShelfRepository
import io.ktor.server.config.ApplicationConfig
import org.koin.core.module.Module
import org.koin.core.scope.Scope
import org.koin.dsl.module
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

/**
 * Koin server module wiring the auth slice end-to-end. [config] is the Ktor
 * application config — read once at startup, never re-read.
 *
 * Construction order is dependency order: `Database` first (everything needs
 * it); primitives (`Clock`, generators, hashers); then services that compose
 * primitives; then the top-level `AuthServiceImpl`. [pushRelayUrl] is resolved
 * once at startup (`Application.resolvePushRelayUrl`) and threaded through here
 * the same way `homeDir` reaches domain modules — see `installDependencies`.
 */
fun authModule(
    config: ApplicationConfig,
    pushRelayUrl: String,
): Module {
    val secrets = resolveServerSecrets(config)
    return module {
        single<Clock> { Clock.System }

        single<DatabaseHandle> {
            DatabaseFactory.init(
                DatabaseConfig(jdbcUrl = config.resolveJdbcUrl()),
            )
        }

        // The repos' SQLDelight driver IS the restore-swappable one held by DatabaseHandle, so a
        // restore swap reaches every repository. Resolving DatabaseHandle first forces
        // MigrationRunner.migrate() before the driver opens the file (the driver never calls
        // Schema.create). Bound as its own single so the access-filtered raw reads ([BookAccessPolicy],
        // the access-scoped repos, [SearchServiceImpl]) execute engine-neutral SQL through the SAME
        // driver instance that backs [ListenUpDatabase] — sharing the connection so a raw query inside
        // a `suspendTransaction(db)` participates in the open transaction.
        single<SqlDriver> { get<DatabaseHandle>().sqlDriver }

        // The SQLDelight database every repository runs on, built over the shared [SqlDriver] single.
        single<ListenUpDatabase> { ListenUpDatabase(get<SqlDriver>()) }

        single { PasswordHasher() }
        // C3: bounded-parallelism gate for ALL auth-path Argon2 (login/dummy/register/setup/invite).
        single { Argon2Limiter(hasher = get<PasswordHasher>(), permits = config.argon2Parallelism()) }
        // C3: per-IP RPC-path auth throttle — the counterpart to the REST `RateLimit` plugin.
        single { LoginRateLimiter(clock = get()) }
        single { RefreshTokenGenerator() }
        single {
            RefreshTokenHasher(pepper = secrets.refreshPepper.encodeToByteArray())
        }

        single {
            // Lost-response reuse-grace window (C4). Configurable so an operator can tune it and so
            // integration tests can pin the family-revoke path with a real Clock.System by setting 0.
            val graceSeconds =
                config.propertyOrNull("auth.refreshReuseGraceSeconds")?.getString()?.toLong()
                    ?: DEFAULT_REUSE_GRACE_SECONDS
            SessionService(
                db = get<ListenUpDatabase>(),
                tokenHasher = get(),
                tokenGenerator = get(),
                refreshTtl = REFRESH_TOKEN_TTL_DAYS.days,
                reuseGracePeriod = graceSeconds.seconds,
                clock = get(),
            )
        }

        single {
            JwtConfiguration(
                secret = secrets.jwtSecret,
                issuer = config.property("jwt.issuer").getString(),
                audience = config.property("jwt.audience").getString(),
                clock = get(),
            )
        }

        single { ServerSettingsRepository(sql = get(), default = config.registrationPolicy()) }

        single { PushConfig(relayUrl = pushRelayUrl) }

        single { SessionIssuer(sessions = get(), jwt = get(), clock = get()) }

        single { buildDefaultAllBooksGrantIssuer() }

        single {
            AuthServiceImpl(
                db = get<ListenUpDatabase>(),
                sessions = get(),
                hasher = get(),
                jwt = get(),
                sessionIssuer = get(),
                clock = get(),
                settings = get(),
                // Per-IP RPC throttle (C3). The per-call remote host is bound at the mount via
                // withRemoteHost; the singleton carries the limiter but no host (throttle inert).
                loginRateLimiter = get(),
                // Nullable — shelf module may not be loaded (e.g. during authModule-only verify tests).
                // When shelfModule is assembled, ShelfRepository is resolved and starter shelves are created.
                shelfRepository = getOrNull<ShelfRepository>(),
                // Nullable — publicProfileModule may not be loaded in minimal test containers.
                publicProfileMaintainer = getOrNull(),
                // Nullable — playbackModule (which binds ActivityRecorder) may not be loaded.
                activityRecorder = getOrNull(),
                defaultGrantIssuer = getOrNull(),
                // Nullable — the admin-roster module may not be loaded in minimal test containers.
                adminUserRosterMaintainer = getOrNull(),
            )
        }

        single {
            AdminUserServiceImpl(
                sql = get<ListenUpDatabase>(),
                sessions = get(),
                settings = get(),
                registrationBroadcaster = get(),
                registrationPolicyBroadcaster = get(),
                bus = get(),
                clock = get(),
                publicProfileMaintainer = getOrNull(),
                activityRecorder = getOrNull(),
                defaultGrantIssuer = getOrNull(),
                // Nullable — the admin-roster module may not be loaded in minimal test containers.
                adminUserRosterMaintainer = getOrNull(),
            )
        }

        single {
            AdminSettingsServiceImpl(
                settings = get(),
                changeBus = get(),
                libraryRegistry = get(),
                libraryRepository = get(),
            )
        }
        single<AdminSettingsService> { get<AdminSettingsServiceImpl>() }

        single { InviteCodeGenerator() }

        single {
            InviteServiceImpl(
                db = get<ListenUpDatabase>(),
                codeGenerator = get(),
                hasher = get(),
                sessionIssuer = get(),
                serverName = config.serverName(),
                clock = get(),
                defaultGrantIssuer = getOrNull(),
                adminUserRosterMaintainer = getOrNull(),
            )
        }

        single<InstanceService> {
            InstanceServiceImpl(
                sql = get<ListenUpDatabase>(),
                settings = get(),
                instanceIdentity = get(),
                pushConfig = get(),
            )
        }

        single { ExpiredSessionCleanupTask(sessionService = get(), clock = get()) }

        single { RegistrationBroadcaster() }

        single { RegistrationPolicyBroadcaster() }
    }
}

private const val REFRESH_TOKEN_TTL_DAYS = 30L

/** Default lost-response reuse-grace window in seconds (C4). Overridable via `auth.refreshReuseGraceSeconds`. */
private const val DEFAULT_REUSE_GRACE_SECONDS = 60L

/** Concurrent-Argon2 ceiling (C3): `auth.argon2Parallelism` if set, else [DEFAULT_ARGON2_PARALLELISM]. */
private fun ApplicationConfig.argon2Parallelism(): Int =
    propertyOrNull("auth.argon2Parallelism")?.getString()?.toIntOrNull() ?: DEFAULT_ARGON2_PARALLELISM

/**
 * The default ALL_BOOKS grant issuer, or null when its deps are absent. Nullable because booksModule
 * (LibraryRegistry) and syncModule (Collection[Grant]Repository) may not be loaded in minimal test
 * containers. Extracted from [authModule] so the module factory stays under the length budget.
 */
private fun Scope.buildDefaultAllBooksGrantIssuer(): DefaultAllBooksGrantIssuer? {
    val collectionRepository = getOrNull<CollectionRepository>() ?: return null
    val grantRepository = getOrNull<CollectionGrantRepository>() ?: return null
    val libraryRegistry = getOrNull<LibraryRegistry>() ?: return null
    return DefaultAllBooksGrantIssuer(
        collectionGrantRepository = grantRepository,
        collectionRepository = collectionRepository,
        libraryRegistry = libraryRegistry,
        clock = get(),
    )
}

private const val DEFAULT_SERVER_NAME = "ListenUp"

private fun ApplicationConfig.registrationPolicy(): RegistrationPolicy {
    val raw = propertyOrNull("registration.policy")?.getString() ?: return RegistrationPolicy.APPROVAL_QUEUE
    return runCatching { RegistrationPolicy.valueOf(raw) }.getOrDefault(RegistrationPolicy.APPROVAL_QUEUE)
}

/** The instance's display name, shown on the invite landing page. Defaults to [DEFAULT_SERVER_NAME]. */
private fun ApplicationConfig.serverName(): String =
    propertyOrNull("app.serverName")?.getString()?.takeIf { it.isNotBlank() } ?: DEFAULT_SERVER_NAME

/**
 * The effective JDBC URL: an explicit `database.jdbcUrl` (tests inject this) wins;
 * otherwise the SQLite DB defaults into `$LISTENUP_HOME/listenup.db`, with
 * `LISTENUP_HOME` defaulting to `~/ListenUp`. Reads env / system properties here
 * at the config edge so [resolveDatabaseUrl] stays pure.
 */
private fun ApplicationConfig.resolveJdbcUrl(): String {
    val configured = propertyOrNull("database.jdbcUrl")?.getString().orEmpty()
    // Honour `listenup.home` (config) so the DB lands under the SAME home as covers/spool — not just
    // $LISTENUP_HOME. Keeps all server data under one directory.
    val home =
        resolveListenupHome(
            configuredHome = propertyOrNull("listenup.home")?.getString(),
            envHome = readEnv("LISTENUP_HOME"),
            userHome = userHomeDir(),
        )
    return resolveDatabaseUrl(configuredUrl = configured, listenupHome = home)
}
