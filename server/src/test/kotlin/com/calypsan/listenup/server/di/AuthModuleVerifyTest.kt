package com.calypsan.listenup.server.di

import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.server.sync.ChangeBus
import com.zaxxer.hikari.HikariDataSource
import io.kotest.core.spec.style.FunSpec
import io.ktor.server.config.MapApplicationConfig
import org.jetbrains.exposed.v1.jdbc.Database
import org.koin.test.verify.verify
import java.nio.file.Files
import java.nio.file.Path

/**
 * Verifies the auth Koin module's dependency graph statically — every
 * constructor parameter on declared bindings can be resolved from another
 * binding (or from the whitelist). Catches DI wiring regressions in CI.
 */
class AuthModuleVerifyTest :
    FunSpec({
        test("authModule's dependency graph is internally consistent") {
            val tmp = Files.createTempFile("listenup-verify-", ".db").toFile().apply { deleteOnExit() }
            val config =
                MapApplicationConfig(
                    "database.jdbcUrl" to "jdbc:sqlite:${tmp.absolutePath}",
                    "auth.refreshPepper" to "x".repeat(32),
                    "jwt.secret" to "x".repeat(32),
                    "jwt.issuer" to "listenup",
                    "jwt.audience" to "listenup-client",
                    "registration.policy" to "OPEN",
                )
            // ByteArray whitelisted: RefreshTokenHasher takes a raw ByteArray pepper
            // that the closure reads from config. RegistrationPolicy whitelisted:
            // ServerSettingsRepository takes a `default` policy read from config the
            // same way. ChangeBus whitelisted: AdminUserServiceImpl resolves it from
            // syncModule, a separate module not loaded here. Verify can't introspect
            // closure bodies, so it sees the constructor param's type and asks for a binding.
            // HikariDataSource, Path, Database: DatabaseHandle's constructor takes these directly;
            // they are constructed inside the factory closure, not injected from the Koin graph.
            authModule(config).verify(
                extraTypes =
                    listOf(
                        ByteArray::class,
                        RegistrationPolicy::class,
                        ChangeBus::class,
                        HikariDataSource::class,
                        Path::class,
                        Database::class,
                    ),
            )
        }
    })
