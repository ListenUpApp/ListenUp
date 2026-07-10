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
import com.calypsan.listenup.server.auth.AuthServiceImpl
import com.calypsan.listenup.server.auth.InviteCodeGenerator
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
import org.koin.dsl.module
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
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
        single { RefreshTokenGenerator() }
        single {
            RefreshTokenHasher(pepper = secrets.refreshPepper.encodeToByteArray())
        }

        single {
            SessionService(
                db = get<ListenUpDatabase>(),
                tokenHasher = get(),
                tokenGenerator = get(),
                refreshTtl = REFRESH_TOKEN_TTL_DAYS.days,
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

        // Nullable — booksModule (which binds LibraryRegistry) and syncModule (which binds
        // CollectionRepository / CollectionGrantRepository) may not be loaded in minimal
        // test containers. getOrNull() returns null when any dependency is absent.
        single {
            val collectionRepository = getOrNull<CollectionRepository>()
            val grantRepository = getOrNull<CollectionGrantRepository>()
            val libraryRegistry = getOrNull<LibraryRegistry>()
            if (collectionRepository != null && grantRepository != null && libraryRegistry != null) {
                DefaultAllBooksGrantIssuer(
                    collectionGrantRepository = grantRepository,
                    collectionRepository = collectionRepository,
                    libraryRegistry = libraryRegistry,
                    clock = get(),
                )
            } else {
                null
            }
        }

        single {
            AuthServiceImpl(
                db = get<ListenUpDatabase>(),
                sessions = get(),
                hasher = get(),
                jwt = get(),
                sessionIssuer = get(),
                clock = get(),
                settings = get(),
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
