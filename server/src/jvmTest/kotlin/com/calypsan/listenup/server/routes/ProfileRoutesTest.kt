package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.profile.AvatarUploadResponse
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.post
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
 * Integration test for the avatar binary routes:
 *  - POST /api/v1/profile/avatar  — multipart upload; validates image, stores, updates user record.
 *  - GET  /api/v1/avatars/{userId} — serves stored avatar by user id.
 *
 * Runs the full [module] with an isolated SQLite DB and a temp homeDir so the
 * avatar ImageStore writes under `homeDir/avatars/`.
 */
class ProfileRoutesTest :
    FunSpec({
        // Valid minimal PNG: 8-byte PNG signature + 16 zero bytes (enough for magic-number sniff).
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(16)

        test("GET avatar with path-traversal key returns 404 not file bytes") {
            val homeDir = Files.createTempDirectory("listenup-profile-routes-traversal-")
            try {
                testApplication {
                    useIsolatedTestConfig(homeDir = homeDir.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }
                    val (token, _) = client.setupRoot()

                    // Ktor route parameter captures the decoded value; a traversal attempt must
                    // not return 200 with bytes — the ImageStore safeResolve rejects it with null
                    // (→ 404).  Ktor may also reject path-separator injection at the routing layer
                    // (→ 400 or 404).  Either way, OK (200) is forbidden.
                    val response =
                        client.get("/api/v1/avatars/..%2F..%2Fbuild.gradle.kts") {
                            bearerAuth(token)
                        }
                    check(response.status != HttpStatusCode.OK) {
                        "traversal key must not return 200; got ${response.status}"
                    }
                }
            } finally {
                homeDir.toFile().deleteRecursively()
            }
        }

        test("upload with oversized Content-Length returns 413 PayloadTooLarge") {
            val homeDir = Files.createTempDirectory("listenup-profile-routes-413-")
            try {
                testApplication {
                    useIsolatedTestConfig(homeDir = homeDir.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }
                    val (token, _) = client.setupRoot()

                    // A part whose declared Content-Length exceeds the 5 MiB cap — the handler
                    // should reject before buffering the bytes.
                    // Ktor's formData builder automatically sets Content-Length on a ByteArray
                    // part — the server handler reads it to reject before buffering.
                    val oversizeBytes = ByteArray((AVATAR_MAX_BYTES + 1).toInt()) { 0 }
                    val response =
                        client.post("/api/v1/profile/avatar") {
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
                homeDir.toFile().deleteRecursively()
            }
        }

        test("upload then serve round-trips; bad image returns 422; unauthenticated GET returns 401") {
            val homeDir = Files.createTempDirectory("listenup-profile-routes-")
            try {
                testApplication {
                    useIsolatedTestConfig(homeDir = homeDir.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }

                    val (token, userId) = client.setupRoot()
                    val beforeUpload = System.currentTimeMillis()

                    // Upload a valid PNG — expect 200 OK carrying the server avatar version.
                    val uploadResponse =
                        client.post("/api/v1/profile/avatar") {
                            bearerAuth(token)
                            setBody(
                                MultiPartFormDataContent(
                                    formData {
                                        append(
                                            "file",
                                            png,
                                            Headers.build {
                                                append(HttpHeaders.ContentType, "image/png")
                                                append(
                                                    HttpHeaders.ContentDisposition,
                                                    "filename=\"avatar.png\"",
                                                )
                                            },
                                        )
                                    },
                                ),
                            )
                        }
                    uploadResponse.status shouldBe HttpStatusCode.OK
                    // The response body carries the server's fresh avatar version (client writes it verbatim).
                    (uploadResponse.body<AvatarUploadResponse>().avatarUpdatedAt >= beforeUpload) shouldBe true

                    // Serve the uploaded avatar — expect the exact bytes back.
                    val serveResponse =
                        client.get("/api/v1/avatars/$userId") {
                            bearerAuth(token)
                        }
                    serveResponse.status shouldBe HttpStatusCode.OK
                    serveResponse.readRawBytes() shouldBe png

                    // Upload bytes that don't carry a recognised image magic number — expect 422.
                    val badUploadResponse =
                        client.post("/api/v1/profile/avatar") {
                            bearerAuth(token)
                            setBody(
                                MultiPartFormDataContent(
                                    formData {
                                        append(
                                            "file",
                                            "not-an-image".encodeToByteArray(),
                                            Headers.build {
                                                append(HttpHeaders.ContentType, "image/png")
                                                append(
                                                    HttpHeaders.ContentDisposition,
                                                    "filename=\"bad.png\"",
                                                )
                                            },
                                        )
                                    },
                                ),
                            )
                        }
                    badUploadResponse.status shouldBe HttpStatusCode.UnprocessableEntity

                    // Unauthenticated GET must be rejected — expect 401.
                    val unauthResponse = client.get("/api/v1/avatars/$userId")
                    unauthResponse.status shouldBe HttpStatusCode.Unauthorized
                }
            } finally {
                homeDir.toFile().deleteRecursively()
            }
        }

        test("avatar upload refreshes the public-profile projection so the change syncs to clients") {
            val homeDir = Files.createTempDirectory("listenup-profile-routes-projection-")
            try {
                testApplication {
                    useIsolatedTestConfig(homeDir = homeDir.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }
                    val (token, userId) = client.setupRoot()

                    client
                        .post("/api/v1/profile/avatar") {
                            bearerAuth(token)
                            setBody(
                                MultiPartFormDataContent(
                                    formData {
                                        append(
                                            "file",
                                            png,
                                            Headers.build {
                                                append(HttpHeaders.ContentType, "image/png")
                                                append(HttpHeaders.ContentDisposition, "filename=\"avatar.png\"")
                                            },
                                        )
                                    },
                                ),
                            )
                        }.status shouldBe HttpStatusCode.OK

                    // The public_profiles projection is what every client syncs (the uploader reads its
                    // own avatar back through it, and other devices receive it). It MUST reflect the new
                    // avatar type, or the upload silently never propagates.
                    val db by application.inject<ListenUpDatabase>()
                    db.publicProfilesQueries
                        .selectByIds(listOf(userId))
                        .executeAsOneOrNull()
                        ?.avatar_type shouldBe "image"
                }
            } finally {
                homeDir.toFile().deleteRecursively()
            }
        }
    })

/** Runs first-user setup and returns (accessToken, userId). */
private suspend fun HttpClient.setupRoot(): Pair<String, String> {
    val session =
        post("/api/v1/auth/setup") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest("root@x", "x".repeat(8), "Root"))
        }.body<AppResult<AuthSession>>()
            .let { it as AppResult.Success<AuthSession> }
            .data
    return session.accessToken.value to session.user.id.value
}
