@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.server.di

import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.server.api.AdminUserServiceImpl
import com.calypsan.listenup.server.api.InviteServiceImpl
import com.calypsan.listenup.server.auth.AuthServiceImpl
import com.calypsan.listenup.server.auth.InviteCodeGenerator
import com.calypsan.listenup.server.auth.JwtConfiguration
import com.calypsan.listenup.server.auth.PasswordHasher
import com.calypsan.listenup.server.auth.RefreshTokenGenerator
import com.calypsan.listenup.server.auth.RefreshTokenHasher
import com.calypsan.listenup.api.InstanceService
import com.calypsan.listenup.server.api.InstanceServiceImpl
import com.calypsan.listenup.server.auth.SessionIssuer
import com.calypsan.listenup.server.auth.SessionService
import com.calypsan.listenup.server.db.DatabaseConfig
import com.calypsan.listenup.server.db.DatabaseFactory
import com.calypsan.listenup.server.db.resolveDatabaseUrl
import com.calypsan.listenup.server.db.resolveListenupHome
import com.calypsan.listenup.server.scheduler.ExpiredSessionCleanupTask
import com.calypsan.listenup.server.settings.ServerSettingsRepository
import io.ktor.server.config.ApplicationConfig
import org.jetbrains.exposed.v1.jdbc.Database
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
 * primitives; then the top-level `AuthServiceImpl`.
 */
fun authModule(config: ApplicationConfig): Module =
    module {
        single<Clock> { Clock.System }

        single<Database> {
            DatabaseFactory.init(
                DatabaseConfig(jdbcUrl = config.resolveJdbcUrl()),
            )
        }

        single { PasswordHasher() }
        single { RefreshTokenGenerator() }
        single {
            RefreshTokenHasher(pepper = config.property("auth.refreshPepper").getString().toByteArray())
        }

        single {
            SessionService(
                db = get(),
                tokenHasher = get(),
                tokenGenerator = get(),
                refreshTtl = REFRESH_TOKEN_TTL_DAYS.days,
                clock = get(),
            )
        }

        single {
            JwtConfiguration(
                secret = config.property("jwt.secret").getString(),
                issuer = config.property("jwt.issuer").getString(),
                audience = config.property("jwt.audience").getString(),
                clock = get(),
            )
        }

        single { ServerSettingsRepository(db = get(), default = config.registrationPolicy()) }

        single { SessionIssuer(sessions = get(), jwt = get(), clock = get()) }

        single {
            AuthServiceImpl(
                db = get(),
                sessions = get(),
                hasher = get(),
                jwt = get(),
                sessionIssuer = get(),
                clock = get(),
                settings = get(),
            )
        }

        single {
            AdminUserServiceImpl(
                db = get(),
                sessions = get(),
                settings = get(),
                clock = get(),
            )
        }

        single { InviteCodeGenerator() }

        single {
            InviteServiceImpl(
                db = get(),
                codeGenerator = get(),
                hasher = get(),
                sessionIssuer = get(),
                serverName = config.serverName(),
                clock = get(),
            )
        }

        single<InstanceService> {
            InstanceServiceImpl(
                db = get(),
                settings = get(),
            )
        }

        single { ExpiredSessionCleanupTask(sessionService = get(), clock = get()) }
    }

private const val REFRESH_TOKEN_TTL_DAYS = 30L

private const val DEFAULT_SERVER_NAME = "ListenUp"

private fun ApplicationConfig.registrationPolicy(): RegistrationPolicy {
    val raw = propertyOrNull("registration.policy")?.getString() ?: return RegistrationPolicy.OPEN
    return runCatching { RegistrationPolicy.valueOf(raw) }.getOrDefault(RegistrationPolicy.OPEN)
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
    val home = resolveListenupHome(System.getenv("LISTENUP_HOME"), System.getProperty("user.home"))
    return resolveDatabaseUrl(configuredUrl = configured, listenupHome = home)
}
