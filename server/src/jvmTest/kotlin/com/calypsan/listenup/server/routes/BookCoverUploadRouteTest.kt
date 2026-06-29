package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.auth.AdminUserPatch
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.RegisterResult
import com.calypsan.listenup.api.dto.auth.UserPermissions
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
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import org.koin.ktor.ext.inject

/**
 * Integration tests for `PUT /api/v1/books/{id}/cover` (cover upload, canEdit-gated).
 *
 * Boots the full [module] with an isolated SQLite DB, a temp library root, and a
 * temp homeDir so [CoverImageStore] writes under `homeDir/covers/`. Covers four
 * cases:
 *  - ROOT uploads a valid PNG → 204, cover is served at GET /api/v1/covers/{id}.
 *  - A MEMBER without canEdit uploads → 403 Forbidden.
 *  - Bad (non-image) bytes → 422 Unprocessable Entity.
 *  - Oversize file (> 10 MiB cap) → 413 Payload Too Large.
 */
class BookCoverUploadRouteTest :
    FunSpec({
        // Valid minimal PNG: signature + enough zero bytes to pass the magic-number sniff.
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(16)

        test("ROOT uploads valid PNG → 204, cover is subsequently served at GET /api/v1/covers/{id}") {
            val libraryRoot = Files.createTempDirectory("listenup-cover-upload-root-")
            val homeDir = Files.createTempDirectory("listenup-cover-upload-home-")
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
                    repo.upsert(coverUploadFixture(id = "b1", title = "The Way of Kings"))

                    val uploadResponse =
                        client.put("/api/v1/books/b1/cover") {
                            bearerAuth(token)
                            setBody(
                                MultiPartFormDataContent(
                                    formData {
                                        append(
                                            "file",
                                            png,
                                            Headers.build {
                                                append(HttpHeaders.ContentType, "image/png")
                                                append(HttpHeaders.ContentDisposition, "filename=\"cover.png\"")
                                            },
                                        )
                                    },
                                ),
                            )
                        }
                    uploadResponse.status shouldBe HttpStatusCode.NoContent

                    // Cover is now served at the client cover URL.
                    val serveResponse =
                        client.get("/api/v1/covers/b1") {
                            bearerAuth(token)
                        }
                    serveResponse.status shouldBe HttpStatusCode.OK
                    serveResponse.readRawBytes() shouldBe png
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
                homeDir.toFile().deleteRecursively()
            }
        }

        test("MEMBER without canEdit is denied with 403 Forbidden") {
            val libraryRoot = Files.createTempDirectory("listenup-cover-upload-403-")
            val homeDir = Files.createTempDirectory("listenup-cover-upload-home-403-")
            try {
                testApplication {
                    useIsolatedTestConfig(
                        libraryPath = libraryRoot.toString(),
                        homeDir = homeDir.toString(),
                    )
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }
                    // First user → ROOT (seeds the instance). Then register a member and revoke canEdit.
                    val rootToken = client.mintRootToken()
                    val (memberToken, memberId) = client.registerMember("member@x")
                    // Revoke canEdit via the admin PATCH endpoint — MEMBERs default to canEdit=true.
                    client.patch("/api/v1/admin/users/$memberId") {
                        bearerAuth(rootToken)
                        contentType(ContentType.Application.Json)
                        setBody(AdminUserPatch(permissions = UserPermissions(canEdit = false, canShare = true)))
                    }
                    seedTestLibraryAndFolder()

                    val repo by application.inject<BookRepository>()
                    repo.upsert(coverUploadFixture(id = "b1", title = "The Way of Kings"))

                    val response =
                        client.put("/api/v1/books/b1/cover") {
                            bearerAuth(memberToken)
                            setBody(
                                MultiPartFormDataContent(
                                    formData {
                                        append(
                                            "file",
                                            png,
                                            Headers.build {
                                                append(HttpHeaders.ContentType, "image/png")
                                                append(HttpHeaders.ContentDisposition, "filename=\"cover.png\"")
                                            },
                                        )
                                    },
                                ),
                            )
                        }
                    response.status shouldBe HttpStatusCode.Forbidden
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
                homeDir.toFile().deleteRecursively()
            }
        }

        test("upload with bad (non-image) bytes returns 422 Unprocessable Entity") {
            val libraryRoot = Files.createTempDirectory("listenup-cover-upload-422-")
            val homeDir = Files.createTempDirectory("listenup-cover-upload-home-422-")
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
                    repo.upsert(coverUploadFixture(id = "b1", title = "The Way of Kings"))

                    val response =
                        client.put("/api/v1/books/b1/cover") {
                            bearerAuth(token)
                            setBody(
                                MultiPartFormDataContent(
                                    formData {
                                        append(
                                            "file",
                                            "not-an-image".encodeToByteArray(),
                                            Headers.build {
                                                append(HttpHeaders.ContentType, "image/png")
                                                append(HttpHeaders.ContentDisposition, "filename=\"bad.png\"")
                                            },
                                        )
                                    },
                                ),
                            )
                        }
                    response.status shouldBe HttpStatusCode.UnprocessableEntity
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
                homeDir.toFile().deleteRecursively()
            }
        }

        test("upload with oversize Content-Length returns 413 Payload Too Large") {
            val libraryRoot = Files.createTempDirectory("listenup-cover-upload-413-")
            val homeDir = Files.createTempDirectory("listenup-cover-upload-home-413-")
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
                    repo.upsert(coverUploadFixture(id = "b1", title = "The Way of Kings"))

                    val oversizeBytes = ByteArray((COVER_MAX_BYTES_TEST + 1).toInt()) { 0 }
                    val response =
                        client.put("/api/v1/books/b1/cover") {
                            bearerAuth(token)
                            setBody(
                                MultiPartFormDataContent(
                                    formData {
                                        append(
                                            "file",
                                            oversizeBytes,
                                            Headers.build {
                                                append(HttpHeaders.ContentType, "image/png")
                                                append(HttpHeaders.ContentDisposition, "filename=\"big.png\"")
                                            },
                                        )
                                    },
                                ),
                            )
                        }
                    response.status shouldBe HttpStatusCode.PayloadTooLarge
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
                homeDir.toFile().deleteRecursively()
            }
        }
    })

/** The cover max-bytes cap mirrored from BooksModule for test assertions (10 MiB). */
private const val COVER_MAX_BYTES_TEST = 10L * 1024 * 1024

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

/**
 * Registers a second user under the OPEN policy (ACTIVE MEMBER, no canEdit) and
 * returns (accessToken, userId).
 */
private suspend fun HttpClient.registerMember(email: String): Pair<String, String> {
    val session =
        post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(email, "x".repeat(8), "Member"))
        }.body<AppResult<RegisterResult>>()
            .shouldBeInstanceOf<AppResult.Success<RegisterResult>>()
            .data
            .shouldBeInstanceOf<RegisterResult.Authenticated>()
            .session
    return session.accessToken.value to session.user.id.value
}

/** Minimal book fixture suitable for cover-upload tests. */
private fun coverUploadFixture(
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
