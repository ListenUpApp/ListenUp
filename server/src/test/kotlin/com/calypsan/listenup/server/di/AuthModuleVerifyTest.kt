package com.calypsan.listenup.server.di

import io.kotest.core.spec.style.FunSpec
import io.ktor.server.config.MapApplicationConfig
import org.koin.test.verify.verify
import java.nio.file.Files

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
            // that the closure reads from config. Verify can't introspect the closure
            // body, so it sees the constructor param's type and asks for a binding.
            authModule(config).verify(extraTypes = listOf(ByteArray::class))
        }
    })
