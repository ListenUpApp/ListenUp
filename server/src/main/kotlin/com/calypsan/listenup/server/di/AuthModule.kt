@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.server.di

import com.calypsan.listenup.server.auth.AuthServiceImpl
import com.calypsan.listenup.server.auth.JwtConfiguration
import com.calypsan.listenup.server.auth.PasswordHasher
import com.calypsan.listenup.server.auth.RefreshTokenGenerator
import com.calypsan.listenup.server.auth.RefreshTokenHasher
import com.calypsan.listenup.server.auth.RegistrationPolicy
import com.calypsan.listenup.server.auth.SessionService
import com.calypsan.listenup.server.db.DatabaseConfig
import com.calypsan.listenup.server.db.DatabaseFactory
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
                DatabaseConfig(jdbcUrl = config.property("database.jdbcUrl").getString()),
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

        single {
            AuthServiceImpl(
                db = get(),
                sessions = get(),
                hasher = get(),
                jwt = get(),
                clock = get(),
                registrationPolicy = config.registrationPolicy(),
            )
        }
    }

private const val REFRESH_TOKEN_TTL_DAYS = 30L

private fun ApplicationConfig.registrationPolicy(): RegistrationPolicy {
    val raw = propertyOrNull("registration.policy")?.getString() ?: return RegistrationPolicy.OPEN
    return runCatching { RegistrationPolicy.valueOf(raw) }.getOrDefault(RegistrationPolicy.OPEN)
}
