package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.CollectionSyncPayload
import com.calypsan.listenup.api.sync.Page
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
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
 * Regression guard for the `?collectionIds=` sibling of the `?bookIds=` allowlist bug
 * (fixed for `?bookIds=` in `721a28cd1`): a domain whose root table has no `collection_id`
 * column must reject a targeted `?collectionIds=` fetch with 400, never 500.
 */
class CollectionIdsAllowlistTest :
    FunSpec({

        test("a ?collectionIds= fetch on books (driver-wired, no collection_id column) returns 400, not 500") {
            val libraryRoot = Files.createTempDirectory("listenup-collids-allowlist-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }
                    val client = jsonClient()

                    val token = client.mintRootToken()

                    val response =
                        client.get("/api/v1/sync/books?collectionIds=c1,c2") {
                            bearerAuth(token)
                        }
                    response.status shouldBe HttpStatusCode.BadRequest
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }

        test("a ?collectionIds= fetch on collection_books still returns the matching rows") {
            val libraryRoot = Files.createTempDirectory("listenup-collids-allowlist-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }
                    val client = jsonClient()

                    val token = client.mintRootToken()
                    seedTestLibraryAndFolder()

                    val sql by application.inject<ListenUpDatabase>()
                    sql.seedTestBook("b1")
                    sql.seedTestBook("b2")

                    val collections by application.inject<CollectionRepository>()
                    val memberships by application.inject<CollectionBookRepository>()

                    collections.upsert(collectionFixture("col-a"))
                    collections.upsert(collectionFixture("col-b"))
                    memberships.upsert(membershipFixture("col-a", "b1"))
                    memberships.upsert(membershipFixture("col-b", "b2"))

                    val response =
                        client.get("/api/v1/sync/collection_books?collectionIds=col-a") {
                            bearerAuth(token)
                        }
                    response.status shouldBe HttpStatusCode.OK
                    val page: Page<CollectionBookSyncPayload> = response.body()
                    page.items.map { it.bookId } shouldContainExactly listOf("b1")
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
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

private fun collectionFixture(id: String): CollectionSyncPayload =
    CollectionSyncPayload(
        id = id,
        libraryId = "test-library",
        ownerId = "root",
        name = id,
        isInbox = false,
        revision = 0L,
        updatedAt = 0L,
    )

private fun membershipFixture(
    collectionId: String,
    bookId: String,
): CollectionBookSyncPayload =
    CollectionBookSyncPayload(
        id = "$collectionId:$bookId",
        collectionId = collectionId,
        bookId = bookId,
        createdAt = 0L,
        revision = 0L,
    )
