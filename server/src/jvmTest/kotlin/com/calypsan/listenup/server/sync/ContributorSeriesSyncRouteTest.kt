package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.ContributorSyncPayload
import com.calypsan.listenup.api.sync.DomainList
import com.calypsan.listenup.api.sync.Page
import com.calypsan.listenup.api.sync.SeriesSyncPayload
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
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
import org.koin.ktor.ext.inject

/**
 * Integration test confirming [ContributorRepository] and [SeriesRepository]
 * ride the generic sync substrate end-to-end.
 *
 * Boots the full [module] with a temp library path (so `booksModule` installs
 * and both repositories self-register with [SyncRegistry] at startup). Then
 * asserts:
 *
 * 1. `GET /api/v1/sync/domains` lists `"contributors"` and `"series"`.
 * 2. After `resolveOrCreate`, `GET /api/v1/sync/contributors?since=0` returns
 *    the created contributor.
 * 3. After `resolveOrCreate`, `GET /api/v1/sync/series?since=0` returns the
 *    created series.
 *
 * If either assertion fails it signals a wiring gap — the repository is not
 * `createdAtStart`, `booksModule` is not installed, or the domain did not
 * register — not a test gap. The test is intentionally a guard, not new
 * behaviour.
 *
 * Mirrors the approach of [BooksSyncCatchUpTest] and [BooksModuleStartupTest].
 */
class ContributorSeriesSyncRouteTest :
    FunSpec({

        test("GET /api/v1/sync/domains lists 'contributors' and 'series' when booksModule is installed") {
            val libraryRoot = Files.createTempDirectory("listenup-contributor-series-sync-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }

                    val token = client.mintAccessToken()

                    val response =
                        client.get("/api/v1/sync/domains") {
                            bearerAuth(token)
                        }

                    response.status shouldBe HttpStatusCode.OK
                    val domains = response.body<DomainList>().domains
                    domains shouldContain "contributors"
                    domains shouldContain "series"
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }

        test("GET /api/v1/sync/contributors?since=0 returns a contributor after resolveOrCreate") {
            val libraryRoot = Files.createTempDirectory("listenup-contributor-catchup-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }

                    val token = client.mintAccessToken()

                    val contributors by application.inject<ContributorRepository>()
                    contributors.resolveOrCreate("Some Author", sortName = null)

                    val response =
                        client.get("/api/v1/sync/contributors?since=0") {
                            bearerAuth(token)
                        }

                    response.status shouldBe HttpStatusCode.OK
                    val page = response.body<Page<ContributorSyncPayload>>()
                    page.items shouldHaveSize 1
                    page.items.first().name shouldBe "Some Author"
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }

        test("GET /api/v1/sync/series?since=0 returns a series after resolveOrCreate") {
            val libraryRoot = Files.createTempDirectory("listenup-series-catchup-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }

                    val token = client.mintAccessToken()

                    val series by application.inject<SeriesRepository>()
                    series.resolveOrCreate("Some Series")

                    val response =
                        client.get("/api/v1/sync/series?since=0") {
                            bearerAuth(token)
                        }

                    response.status shouldBe HttpStatusCode.OK
                    val page = response.body<Page<SeriesSyncPayload>>()
                    page.items shouldHaveSize 1
                    page.items.first().name shouldBe "Some Series"
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }
    })

private suspend fun HttpClient.mintAccessToken(): String {
    post("/api/v1/auth/setup") {
        contentType(ContentType.Application.Json)
        setBody(RegisterRequest("root@x", "x".repeat(8), "Root"))
    }
    val response =
        post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest("root@x", "x".repeat(8)))
        }
    return response
        .body<AppResult<AuthSession>>()
        .let { it as AppResult.Success<AuthSession> }
        .data
        .accessToken
        .value
}
