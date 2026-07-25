package com.calypsan.listenup.server

import io.ktor.server.testing.ApplicationTestBuilder

import com.calypsan.listenup.server.testing.publicAuthService

import com.calypsan.listenup.api.SyncStreamService
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.RegisterResult
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.testing.authedService
import com.calypsan.listenup.server.testing.shouldSucceed
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.server.testing.testApplication
import java.nio.file.Files

/**
 * Wiring test for [com.calypsan.listenup.server.di.booksModule].
 *
 * Boots a real `Application.module()` over an isolated SQLite DB with a
 * configured library path. The scanner and books slices load unconditionally
 * now, so this proves the Koin graph resolves without a missing-binding crash —
 * `BookRepository` (`createdAtStart`) and `BookPersister` are both constructed
 * at bootstrap.
 *
 * It then asserts `SyncStreamService.listDomains()` lists `"books"`, which is
 * only possible if `BookRepository`'s `init` block ran and registered the
 * domain with `SyncRegistry` at startup.
 *
 * Approach: `testApplication { module() }` rather than a bare
 * `booksModule().verify()`. `verify()` cannot see the cross-module bindings
 * (`Database`, `ChangeBus`, `SyncRegistry`, the scanner's `scanResultBus` and
 * `CoroutineScope`) that `booksModule` consumes — booting the real `module()`
 * exercises the whole graph exactly as production wires it.
 */
class BooksModuleStartupTest :
    FunSpec({

        suspend fun ApplicationTestBuilder.seedAndLoginAlice(): AuthSession {
            publicAuthService().setupRoot(RegisterRequest("root@x", "x".repeat(8), "Root"))
            val registered =
                publicAuthService().register(RegisterRequest("alice@x", "x".repeat(8), "Alice"))
            return registered
                .shouldBeInstanceOf<AppResult.Success<RegisterResult>>()
                .data
                .shouldBeInstanceOf<RegisterResult.Authenticated>()
                .session
        }

        test("server boots with a library configured and lists 'books' as a sync domain") {
            val libraryRoot = Files.createTempDirectory("listenup-books-module-test-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }

                    val session = seedAndLoginAlice()

                    val domains =
                        authedService<SyncStreamService>(session.accessToken.value)
                            .listDomains()
                            .shouldSucceed()

                    domains shouldContain "books"
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }
    })
