package com.calypsan.listenup.server

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.RegisterResult
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.DomainList
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import java.nio.file.Files

/**
 * Wiring test for [com.calypsan.listenup.server.di.booksModule].
 *
 * Boots a real `Application.module()` over an isolated SQLite DB with a
 * configured library path, so the `if (resolvedLibraryPath != null)` branch
 * installs the scanner and books slices. This proves the Koin graph resolves
 * without a missing-binding crash — `BookRepository` (`createdAtStart`) and
 * `BookPersister` are both constructed at bootstrap.
 *
 * It then asserts `GET /api/v1/sync/domains` lists `"books"`, which is only
 * possible if `BookRepository`'s `init` block ran and registered the domain
 * with `SyncRegistry` at startup.
 *
 * Approach: `testApplication { module() }` rather than a bare
 * `booksModule().verify()`. `verify()` cannot see the cross-module bindings
 * (`Database`, `ChangeBus`, `SyncRegistry`, the scanner's `scanResultBus` and
 * `CoroutineScope`) that `booksModule` consumes — booting the real `module()`
 * exercises the whole graph exactly as production wires it.
 */
class BooksModuleStartupTest :
    FunSpec({

        suspend fun HttpClient.seedAndLoginAlice(): AuthSession {
            post("/api/v1/auth/setup") {
                contentType(ContentType.Application.Json)
                setBody(RegisterRequest("root@x", "x".repeat(8), "Root"))
            }
            val registered =
                post("/api/v1/auth/register") {
                    contentType(ContentType.Application.Json)
                    setBody(RegisterRequest("alice@x", "x".repeat(8), "Alice"))
                }.body<AppResult<RegisterResult>>()
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
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }

                    val session = client.seedAndLoginAlice()

                    val response =
                        client.get("/api/v1/sync/domains") {
                            bearerAuth(session.accessToken.value)
                        }

                    response.status shouldBe HttpStatusCode.OK
                    val domains = response.body<DomainList>().domains
                    domains shouldContain "books"
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }
    })
