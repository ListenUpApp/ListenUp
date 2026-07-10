package com.calypsan.listenup.server.di

import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.db.SwappableSqlDriver
import com.calypsan.listenup.server.services.LibraryRegistry
import com.calypsan.listenup.server.services.LibraryRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.CollectionGrantRepository
import com.calypsan.listenup.server.sync.CollectionRepository
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
            // that the closure reads from config. RegistrationPolicy whitelisted:
            // ServerSettingsRepository takes a `default` policy read from config the
            // same way. ChangeBus whitelisted: AdminUserServiceImpl resolves it from
            // syncModule, a separate module not loaded here. Verify can't introspect
            // closure bodies, so it sees the constructor param's type and asks for a binding.
            // SwappableSqlDriver: DatabaseHandle's constructor takes this directly; it is
            // constructed inside the DatabaseFactory.init() closure, not injected from the Koin graph.
            // LibraryRegistry, LibraryRepository: AdminSettingsServiceImpl deps resolved from
            // booksModule/libraryModule, both loaded at application startup but absent here.
            // PrincipalProvider: PushServiceImpl's Koin singleton carries an inline unscoped
            // placeholder literal (`PrincipalProvider { error(...) }`) constructed directly inside
            // the factory lambda, not resolved via `get()` — verify() reflects on the constructor
            // signature and can't see that, so it asks for a binding this module never declares.
            authModule(config, "https://push.example.com").verify(
                extraTypes =
                    listOf(
                        ByteArray::class,
                        RegistrationPolicy::class,
                        ChangeBus::class,
                        SwappableSqlDriver::class,
                        LibraryRegistry::class,
                        LibraryRepository::class,
                        // DefaultAllBooksGrantIssuer deps resolved from syncModule/booksModule
                        // (both loaded at application startup but absent here):
                        CollectionGrantRepository::class,
                        CollectionRepository::class,
                        PrincipalProvider::class,
                    ),
            )
        }
    })
