package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.ChapterInput
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookChapterPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import org.koin.ktor.ext.inject

/**
 * Integration test for `PUT /api/v1/books/{id}/chapters`.
 *
 * Boots the full [module] with an isolated SQLite DB and a temp library directory.
 * JWT is minted by walking the real auth REST surface, matching the pattern
 * established in BookRoutesTest and BookCoverRouteTest.
 */
class BookChaptersRouteTest :
    FunSpec({

        suspend fun HttpClient.mintAccessToken(): String {
            post("/api/v1/auth/setup") {
                contentType(ContentType.Application.Json)
                setBody(RegisterRequest("root@x", "x".repeat(8), "Root"))
            }
            return post("/api/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(LoginRequest("root@x", "x".repeat(8)))
            }.body<AppResult<AuthSession>>()
                .shouldBeInstanceOf<AppResult.Success<AuthSession>>()
                .data
                .accessToken
                .value
        }

        test("PUT /api/v1/books/{id}/chapters replaces chapters and returns 204") {
            val libraryRoot = Files.createTempDirectory("listenup-chapters-route-test-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }

                    val token = client.mintAccessToken()
                    seedTestLibraryAndFolder()

                    val repo by application.inject<BookRepository>()
                    repo.upsert(chaptersRouteFixture(id = "b1", title = "The Way of Kings"))

                    val response =
                        client.put("/api/v1/books/b1/chapters") {
                            bearerAuth(token)
                            contentType(ContentType.Application.Json)
                            setBody(
                                listOf(
                                    ChapterInput(id = "ch1", title = "Prologue", startTime = 0L, duration = 600_000L),
                                    ChapterInput(id = "ch2", title = "Act One", startTime = 600_000L, duration = 600_000L),
                                ),
                            )
                        }

                    response.status shouldBe HttpStatusCode.NoContent
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }
    })

private fun chaptersRouteFixture(
    id: String,
    title: String,
): BookSyncPayload =
    BookSyncPayload(
        id = id,
        libraryId = LibraryId("test-library"),
        folderId = FolderId("test-folder"),
        title = title,
        sortTitle = title,
        subtitle = null,
        description = null,
        publishYear = null,
        publisher = null,
        language = null,
        isbn = null,
        asin = null,
        abridged = false,
        explicit = false,
        totalDuration = 3_600_000L,
        cover = null,
        rootRelPath = "books/$id",
        inode = null,
        scannedAt = 1_730_000_000_000L,
        contributors = emptyList(),
        series = emptyList(),
        audioFiles =
            listOf(
                BookAudioFilePayload(
                    id = "af-$id",
                    index = 0,
                    filename = "01.m4b",
                    format = "m4b",
                    codec = "aac",
                    duration = 3_600_000L,
                    size = 500_000_000L,
                ),
            ),
        chapters =
            listOf(
                BookChapterPayload(id = "ch-$id", title = "Prologue", duration = 1_000_000L, startTime = 0L),
            ),
        revision = 0L,
        updatedAt = 0L,
        createdAt = 0L,
        deletedAt = null,
    )
