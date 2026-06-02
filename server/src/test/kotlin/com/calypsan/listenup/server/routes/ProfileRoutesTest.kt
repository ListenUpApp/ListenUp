package com.calypsan.listenup.server.routes

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

        test("upload then serve round-trips; bad image returns 422; unauthenticated GET returns 401") {
            val homeDir = Files.createTempDirectory("listenup-profile-routes-")
            try {
                testApplication {
                    useIsolatedTestConfig(homeDir = homeDir.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }

                    val (token, userId) = client.setupRoot()

                    // Upload a valid PNG — expect 204 No Content.
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
                    uploadResponse.status shouldBe HttpStatusCode.NoContent

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
