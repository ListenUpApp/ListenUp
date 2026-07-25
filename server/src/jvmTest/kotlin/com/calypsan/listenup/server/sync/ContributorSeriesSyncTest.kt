package com.calypsan.listenup.server.sync

import com.calypsan.listenup.server.testing.publicAuthService

import io.ktor.server.testing.ApplicationTestBuilder

import com.calypsan.listenup.api.SyncStreamService
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.ContributorSyncPayload
import com.calypsan.listenup.api.sync.SeriesSyncPayload
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.testing.authedService
import com.calypsan.listenup.server.testing.rows
import com.calypsan.listenup.server.testing.shouldSucceed
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
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
 * 1. [SyncStreamService.listDomains] lists `"contributors"` and `"series"`.
 * 2. After `resolveOrCreate`, [SyncStreamService.pullDomain] for `"contributors"`
 *    (`since = 0`) returns the created contributor.
 * 3. After `resolveOrCreate`, [SyncStreamService.pullDomain] for `"series"`
 *    (`since = 0`) returns the created series.
 *
 * If either assertion fails it signals a wiring gap — the repository is not
 * `createdAtStart`, `booksModule` is not installed, or the domain did not
 * register — not a test gap. The test is intentionally a guard, not new
 * behaviour.
 *
 * Mirrors the approach of [BooksSyncCatchUpTest] and [BooksModuleStartupTest].
 */
class ContributorSeriesSyncTest :
    FunSpec({

        test("listDomains() lists 'contributors' and 'series' when booksModule is installed") {
            val libraryRoot = Files.createTempDirectory("listenup-contributor-series-sync-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }

                    val token = mintAccessToken()

                    val domains = authedService<SyncStreamService>(token).listDomains().shouldSucceed()
                    domains shouldContain "contributors"
                    domains shouldContain "series"
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }

        test("pullDomain(contributors, since=0) returns a contributor after resolveOrCreate") {
            val libraryRoot = Files.createTempDirectory("listenup-contributor-catchup-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }

                    val token = mintAccessToken()

                    val contributors by application.inject<ContributorRepository>()
                    contributors.resolveOrCreate("Some Author", sortName = null)

                    val page =
                        authedService<SyncStreamService>(token)
                            .pullDomain("contributors", since = 0, limit = 500)
                            .shouldSucceed()
                    val decoded = page.rows(ContributorSyncPayload.serializer())
                    decoded shouldHaveSize 1
                    decoded.first().name shouldBe "Some Author"
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }

        test("pullDomain(series, since=0) returns a series after resolveOrCreate") {
            val libraryRoot = Files.createTempDirectory("listenup-series-catchup-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }

                    val token = mintAccessToken()

                    val series by application.inject<SeriesRepository>()
                    series.resolveOrCreate("Some Series")

                    val page =
                        authedService<SyncStreamService>(token)
                            .pullDomain("series", since = 0, limit = 500)
                            .shouldSucceed()
                    val decoded = page.rows(SeriesSyncPayload.serializer())
                    decoded shouldHaveSize 1
                    decoded.first().name shouldBe "Some Series"
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }
    })

private suspend fun ApplicationTestBuilder.mintAccessToken(): String {
    publicAuthService().setupRoot(RegisterRequest("root@x", "x".repeat(8), "Root"))
    val response =
        publicAuthService().login(LoginRequest("root@x", "x".repeat(8)))
    return response
        .let { it as AppResult.Success<AuthSession> }
        .data
        .accessToken
        .value
}
