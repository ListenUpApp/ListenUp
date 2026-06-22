package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookChapterPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.CoverPayload
import com.calypsan.listenup.api.sync.CoverSource
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.patch
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
 * Regression tests for the book mutation routes' per-request principal scoping.
 *
 * Every Book-domain service method that mutates state calls `requireCanEdit()`,
 * which reads `principal.current()`. The DI-bound `BookService` singleton carries
 * an `unscopedPlaceholder` that THROWS on `.current()` (it exists to catch exactly
 * this wiring mistake), so a route handler that calls the raw service without first
 * scoping it via `copyWith(PrincipalProvider { ... })` returns HTTP 500.
 *
 * `PATCH /books/{id}`, `PUT /books/{id}/{contributors,series,genres}`, and
 * `DELETE /books/{id}/cover` previously did NOT scope and so 500'd for every
 * authenticated caller (no integration test exercised them — that gap is why the
 * bug shipped). Each test below drives the route as ROOT and asserts the real
 * outcome (204), which fails with 500 until the handler scopes the service.
 */
class BookMutationRoutesScopingTest :
    FunSpec({

        test("PATCH /api/v1/books/{id} as ROOT applies the patch (204, not 500)") {
            runMutationTest { client, token ->
                val response =
                    client.patch("/api/v1/books/b1") {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        setBody("""{"title":"Updated Title"}""")
                    }
                response.status shouldBe HttpStatusCode.NoContent
            }
        }

        test("PUT /api/v1/books/{id}/contributors as ROOT replaces contributors (204, not 500)") {
            runMutationTest { client, token ->
                val response =
                    client.put("/api/v1/books/b1/contributors") {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        setBody("[]")
                    }
                response.status shouldBe HttpStatusCode.NoContent
            }
        }

        test("PUT /api/v1/books/{id}/series as ROOT replaces series (204, not 500)") {
            runMutationTest { client, token ->
                val response =
                    client.put("/api/v1/books/b1/series") {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        setBody("[]")
                    }
                response.status shouldBe HttpStatusCode.NoContent
            }
        }

        test("PUT /api/v1/books/{id}/genres as ROOT replaces genres (204, not 500)") {
            runMutationTest { client, token ->
                val response =
                    client.put("/api/v1/books/b1/genres") {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        setBody("[]")
                    }
                response.status shouldBe HttpStatusCode.NoContent
            }
        }

        test("DELETE /api/v1/books/{id}/cover as ROOT removes the cover (204, not 500)") {
            runMutationTest(withCover = true) { client, token ->
                val response =
                    client.delete("/api/v1/books/b1/cover") {
                        bearerAuth(token)
                    }
                response.status shouldBe HttpStatusCode.NoContent
            }
        }
    })

/**
 * Boots the full [module] with an isolated SQLite DB + temp library/home dirs, mints a
 * ROOT token, seeds a single book (`b1`), and yields `(client, token)` to [exercise].
 * [withCover] seeds an UPLOADED cover so the `DELETE /cover` path reaches a real 204.
 */
private fun runMutationTest(
    withCover: Boolean = false,
    exercise: suspend (HttpClient, String) -> Unit,
) {
    val libraryRoot = Files.createTempDirectory("listenup-book-mutation-")
    val homeDir = Files.createTempDirectory("listenup-book-mutation-home-")
    try {
        testApplication {
            useIsolatedTestConfig(
                libraryPath = libraryRoot.toString(),
                homeDir = homeDir.toString(),
            )
            application { module() }
            val client = createClient { install(ContentNegotiation) { json(contractJson) } }
            val token = client.mintRootToken()
            seedTestLibraryAndFolder()

            val repo by application.inject<BookRepository>()
            repo.upsert(mutationFixture(id = "b1", title = "The Way of Kings", withCover = withCover))

            exercise(client, token)
        }
    } finally {
        libraryRoot.toFile().deleteRecursively()
        homeDir.toFile().deleteRecursively()
    }
}

/** Mints a ROOT access token via the setup flow and returns it. */
private suspend fun HttpClient.mintRootToken(): String {
    val session =
        post("/api/v1/auth/setup") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest("root@x", "x".repeat(8), "Root"))
        }.body<AppResult<AuthSession>>()
            .let { it as AppResult.Success<AuthSession> }
            .data
    return session.accessToken.value
}

/** Minimal book fixture; [withCover] seeds an UPLOADED cover so DELETE /cover succeeds. */
private fun mutationFixture(
    id: String,
    title: String,
    withCover: Boolean,
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
        cover = if (withCover) CoverPayload(source = CoverSource.UPLOADED, hash = "testhash") else null,
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
