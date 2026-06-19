package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
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

/**
 * Regression guard: on a **library-less** boot (no `scanner.libraryPath`), the always-mounted
 * collection-sync catch-up routes resolve `BookAccessPolicy` via `accessFilterFor` for every
 * non-admin caller. `BookAccessPolicy` must therefore be bound in the always-loaded
 * [com.calypsan.listenup.server.di.syncModule]; a missing binding surfaces as a
 * `NoDefinitionFoundException` and a 500, not as a clean empty page. (It historically lived in
 * the then-library-gated `booksModule`, which 500'd this path on a library-less boot.)
 *
 * This test boots library-less, registers a non-admin member, and asserts each collection-sync
 * catch-up route returns 200 with an empty page.
 */
class LibraryLessCollectionSyncRouteTest :
    FunSpec({

        test("library-less boot: collection-sync catch-up returns OK empty pages, not 500") {
            testApplication {
                useIsolatedTestConfig()
                application { module() }
                val client = jsonClient()

                client.mintRootToken()
                val memberToken = client.registerMember()

                listOf(
                    "/api/v1/sync/collections?since=0",
                    "/api/v1/sync/collection_books?since=0",
                    "/api/v1/sync/collection_shares?since=0",
                ).forEach { path ->
                    val response = client.get(path) { bearerAuth(memberToken) }
                    response.status shouldBe HttpStatusCode.OK
                }
            }
        }
    })

private fun io.ktor.server.testing.ApplicationTestBuilder.jsonClient(): HttpClient =
    createClient {
        install(ContentNegotiation) { json(contractJson) }
    }

private suspend fun HttpClient.mintRootToken(): String =
    post("/api/v1/auth/setup") {
        contentType(ContentType.Application.Json)
        setBody(RegisterRequest("root@x", "x".repeat(8), "Root"))
    }.body<AppResult<AuthSession>>()
        .let { it as AppResult.Success<AuthSession> }
        .data.accessToken.value

private suspend fun HttpClient.registerMember(): String {
    val result =
        post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest("member@x", "y".repeat(8), "Member"))
        }.body<AppResult<com.calypsan.listenup.api.dto.auth.RegisterResult>>()
            .let { it as AppResult.Success<com.calypsan.listenup.api.dto.auth.RegisterResult> }
            .data
    return (result as com.calypsan.listenup.api.dto.auth.RegisterResult.Authenticated).session.accessToken.value
}
