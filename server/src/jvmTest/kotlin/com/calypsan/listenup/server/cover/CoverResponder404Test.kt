package com.calypsan.listenup.server.cover

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.CoverPayload
import com.calypsan.listenup.api.sync.CoverSource
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.testing.filesystemCoverBook
import com.calypsan.listenup.server.testing.publicAuthService
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.koin.ktor.ext.inject
import java.nio.file.Files

/**
 * A cover the DB records but whose bytes cannot be produced is a 404 — and that 404 must be
 * forgettable. The responder used to append the year-long `immutable` `Cache-Control` (and the
 * `ETag`) *before* resolving the bytes, so the miss shipped with them and a client that saw it
 * once would never ask again, even after the cover became servable.
 *
 * The fixture is an **embedded** cover on purpose: filesystem and managed covers are stat'ed while
 * `coverInfo` is resolved, so a missing file 404s before any header is written. Embedded artwork
 * is only discovered when the audio file is parsed at serve time — an audio file that exists but
 * carries no artwork is the case where the headers were already on the response.
 */
class CoverResponder404Test :
    FunSpec({

        suspend fun ApplicationTestBuilder.mintAccessToken(): String {
            publicAuthService().setupRoot(RegisterRequest("root@x", "x".repeat(8), "Root"))
            return publicAuthService()
                .login(LoginRequest("root@x", "x".repeat(8)))
                .shouldBeInstanceOf<AppResult.Success<AuthSession>>()
                .data
                .accessToken
                .value
        }

        test("a cover 404 carries no immutable Cache-Control") {
            val libraryRoot = Files.createTempDirectory("listenup-cover-404-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }
                    val token = mintAccessToken()
                    seedTestLibraryAndFolder(folderPath = libraryRoot.toString())

                    // The row promises embedded artwork with a hash; the audio file is present but
                    // holds nothing parseable, so the bytes cannot be produced.
                    val bookDir = Files.createDirectories(libraryRoot.resolve("books/b1"))
                    Files.write(bookDir.resolve("01.m4b"), ByteArray(64))
                    val repo by application.inject<BookRepository>()
                    repo.upsert(
                        filesystemCoverBook(id = "b1", hash = "abc123")
                            .copy(cover = CoverPayload(source = CoverSource.EMBEDDED, hash = "abc123")),
                    )

                    val response = client.get("/api/v1/books/b1/cover") { bearerAuth(token) }

                    response.status shouldBe HttpStatusCode.NotFound
                    response.headers[HttpHeaders.CacheControl].shouldBeNull()
                    response.headers[HttpHeaders.ETag].shouldBeNull()
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }
    })
