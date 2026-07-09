package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.auth.AdminUserPatch
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.RegisterResult
import com.calypsan.listenup.api.dto.auth.UserPermissions
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.io.hashBytesSha256
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.ChannelProvider
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
import io.ktor.utils.io.ByteReadChannel
import java.nio.file.Files
import org.koin.ktor.ext.inject

/**
 * Integration tests for the two contributor / series image UPLOAD routes:
 *  - `PUT /api/v1/contributors/{id}/image`
 *  - `PUT /api/v1/series/{id}/cover`
 *
 * Boots the full [module] with an isolated SQLite DB, a temp library root, and a temp homeDir so the
 * routes write content-addressed images under `homeDir/contributors/` and `homeDir/series/`. Covers:
 *  - ROOT uploads a valid JPEG → 204; the row's imagePath/coverPath is set (content-addressed),
 *    the file lands under homeDir, and the sibling GET route serves the bytes back.
 *  - A MEMBER without canEdit → 403 (the service's internal requireCanEdit gate).
 *  - Missing file part → 400. Oversized declared part (> 10 MiB) → 413.
 */
class MetadataImageUploadRouteTest :
    FunSpec({
        // Valid minimal JPEG: SOI + APP0 marker + padding to pass the magic-number sniff.
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(16)

        fun jpegPart() =
            MultiPartFormDataContent(
                formData {
                    append(
                        "file",
                        jpeg,
                        Headers.build {
                            append(HttpHeaders.ContentType, "image/jpeg")
                            append(HttpHeaders.ContentDisposition, "filename=\"image.jpg\"")
                        },
                    )
                },
            )

        test("PUT /api/v1/contributors/{id}/image as ROOT → 204, imagePath set, file served back") {
            val libraryRoot = Files.createTempDirectory("listenup-img-upload-contrib-")
            val homeDir = Files.createTempDirectory("listenup-img-upload-contrib-home-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString(), homeDir = homeDir.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }
                    val token = client.mintRootToken()

                    val contributorRepo by application.inject<ContributorRepository>()
                    val id = contributorRepo.resolveOrCreate("Brandon Sanderson", sortName = null)

                    val uploadResponse =
                        client.put("/api/v1/contributors/${id.value}/image") {
                            bearerAuth(token)
                            setBody(jpegPart())
                        }
                    uploadResponse.status shouldBe HttpStatusCode.NoContent

                    val stored = contributorRepo.findById(id.value)?.imagePath
                    stored shouldNotBe null
                    stored!! shouldStartWith "contributors/"
                    Files.exists(homeDir.resolve(stored)) shouldBe true

                    val serveResponse =
                        client.get("/api/v1/contributors/${id.value}/photo") { bearerAuth(token) }
                    serveResponse.status shouldBe HttpStatusCode.OK
                    serveResponse.readRawBytes() shouldBe jpeg
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
                homeDir.toFile().deleteRecursively()
            }
        }

        test("PUT /api/v1/series/{id}/cover as ROOT → 204, coverPath set, file served back") {
            val libraryRoot = Files.createTempDirectory("listenup-img-upload-series-")
            val homeDir = Files.createTempDirectory("listenup-img-upload-series-home-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString(), homeDir = homeDir.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }
                    val token = client.mintRootToken()

                    val seriesRepo by application.inject<SeriesRepository>()
                    val id = seriesRepo.resolveOrCreate("The Stormlight Archive")

                    val uploadResponse =
                        client.put("/api/v1/series/${id.value}/cover") {
                            bearerAuth(token)
                            setBody(jpegPart())
                        }
                    uploadResponse.status shouldBe HttpStatusCode.NoContent

                    val stored = seriesRepo.findById(id.value)?.coverPath
                    stored shouldNotBe null
                    stored!! shouldStartWith "series/"
                    Files.exists(homeDir.resolve(stored)) shouldBe true

                    val serveResponse =
                        client.get("/api/v1/series/${id.value}/cover") { bearerAuth(token) }
                    serveResponse.status shouldBe HttpStatusCode.OK
                    serveResponse.readRawBytes() shouldBe jpeg
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
                homeDir.toFile().deleteRecursively()
            }
        }

        test("PUT /api/v1/contributors/{id}/image as a MEMBER without canEdit → 403 Forbidden") {
            val libraryRoot = Files.createTempDirectory("listenup-img-upload-403-")
            val homeDir = Files.createTempDirectory("listenup-img-upload-403-home-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString(), homeDir = homeDir.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }
                    val rootToken = client.mintRootToken()
                    val (memberToken, memberId) = client.registerMember("member@x")
                    client.patch("/api/v1/admin/users/$memberId") {
                        bearerAuth(rootToken)
                        contentType(ContentType.Application.Json)
                        setBody(AdminUserPatch(permissions = UserPermissions(canEdit = false, canShare = true)))
                    }

                    val contributorRepo by application.inject<ContributorRepository>()
                    val id = contributorRepo.resolveOrCreate("Denied Author", sortName = null)

                    val response =
                        client.put("/api/v1/contributors/${id.value}/image") {
                            bearerAuth(memberToken)
                            setBody(jpegPart())
                        }
                    response.status shouldBe HttpStatusCode.Forbidden
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
                homeDir.toFile().deleteRecursively()
            }
        }

        test("PUT /api/v1/contributors/{id}/image with no file part → 400 Bad Request") {
            val libraryRoot = Files.createTempDirectory("listenup-img-upload-400-")
            val homeDir = Files.createTempDirectory("listenup-img-upload-400-home-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString(), homeDir = homeDir.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }
                    val token = client.mintRootToken()

                    val contributorRepo by application.inject<ContributorRepository>()
                    val id = contributorRepo.resolveOrCreate("No File Author", sortName = null)

                    val response =
                        client.put("/api/v1/contributors/${id.value}/image") {
                            bearerAuth(token)
                            setBody(MultiPartFormDataContent(formData { append("notafile", "oops") }))
                        }
                    response.status shouldBe HttpStatusCode.BadRequest
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
                homeDir.toFile().deleteRecursively()
            }
        }

        test("PUT /api/v1/series/{id}/cover with an oversize part → 413 Payload Too Large") {
            val libraryRoot = Files.createTempDirectory("listenup-img-upload-413-")
            val homeDir = Files.createTempDirectory("listenup-img-upload-413-home-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString(), homeDir = homeDir.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }
                    val token = client.mintRootToken()

                    val seriesRepo by application.inject<SeriesRepository>()
                    val id = seriesRepo.resolveOrCreate("Oversize Series")

                    val oversize = ByteArray((IMAGE_MAX_BYTES_TEST + 1).toInt()) { 0 }
                    val response =
                        client.put("/api/v1/series/${id.value}/cover") {
                            bearerAuth(token)
                            setBody(
                                MultiPartFormDataContent(
                                    formData {
                                        append(
                                            "file",
                                            oversize,
                                            Headers.build {
                                                append(HttpHeaders.ContentType, "image/jpeg")
                                                append(HttpHeaders.ContentDisposition, "filename=\"big.jpg\"")
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

        test("PUT /api/v1/series/{id}/cover oversize part without a declared Content-Length → 413") {
            val libraryRoot = Files.createTempDirectory("listenup-img-upload-413-nolen-")
            val homeDir = Files.createTempDirectory("listenup-img-upload-413-nolen-home-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString(), homeDir = homeDir.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }
                    val token = client.mintRootToken()

                    val seriesRepo by application.inject<SeriesRepository>()
                    val id = seriesRepo.resolveOrCreate("Oversize Streaming Series")

                    // ChannelProvider with a null size emits the part with NO per-part Content-Length
                    // header, so the cap must fire during the streaming read, not via the declared length.
                    val oversize = ByteArray((IMAGE_MAX_BYTES_TEST + 1).toInt()) { 0 }
                    val response =
                        client.put("/api/v1/series/${id.value}/cover") {
                            bearerAuth(token)
                            setBody(
                                MultiPartFormDataContent(
                                    formData {
                                        append(
                                            "file",
                                            ChannelProvider(size = null) { ByteReadChannel(oversize) },
                                            Headers.build {
                                                append(HttpHeaders.ContentType, "image/jpeg")
                                                append(HttpHeaders.ContentDisposition, "filename=\"big.jpg\"")
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

        test("PUT /api/v1/contributors/{id}/image rejected for a MEMBER → the stored file is cleaned up") {
            val libraryRoot = Files.createTempDirectory("listenup-img-upload-orphan-contrib-")
            val homeDir = Files.createTempDirectory("listenup-img-upload-orphan-contrib-home-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString(), homeDir = homeDir.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }
                    val rootToken = client.mintRootToken()
                    val (memberToken, memberId) = client.registerMember("orphan-member@x")
                    client.patch("/api/v1/admin/users/$memberId") {
                        bearerAuth(rootToken)
                        contentType(ContentType.Application.Json)
                        setBody(AdminUserPatch(permissions = UserPermissions(canEdit = false, canShare = true)))
                    }

                    val contributorRepo by application.inject<ContributorRepository>()
                    val id = contributorRepo.resolveOrCreate("Orphan Author", sortName = null)

                    val response =
                        client.put("/api/v1/contributors/${id.value}/image") {
                            bearerAuth(memberToken)
                            setBody(jpegPart())
                        }
                    response.status shouldBe HttpStatusCode.Forbidden

                    // The upload stores content-addressed BEFORE the canEdit gate rejects; on rejection the
                    // helper must delete the file so distinct rejected payloads can't accumulate on disk.
                    val orphan = homeDir.resolve("contributors/${hashBytesSha256(jpeg)}.jpg")
                    Files.exists(orphan) shouldBe false
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
                homeDir.toFile().deleteRecursively()
            }
        }

        test("PUT /api/v1/series/{id}/cover for an unknown series → the stored file is cleaned up") {
            val libraryRoot = Files.createTempDirectory("listenup-img-upload-orphan-series-")
            val homeDir = Files.createTempDirectory("listenup-img-upload-orphan-series-home-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString(), homeDir = homeDir.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }
                    val token = client.mintRootToken()

                    val response =
                        client.put("/api/v1/series/nonexistent-series-id/cover") {
                            bearerAuth(token)
                            setBody(jpegPart())
                        }
                    response.status shouldNotBe HttpStatusCode.NoContent

                    // The unknown-id update returns Failure after the file is stored; the helper must
                    // delete the file rather than leave it orphaned.
                    val orphan = homeDir.resolve("series/${hashBytesSha256(jpeg)}.jpg")
                    Files.exists(orphan) shouldBe false
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
                homeDir.toFile().deleteRecursively()
            }
        }
    })

/** The image upload max-bytes cap mirrored from the route for test assertions (10 MiB). */
private const val IMAGE_MAX_BYTES_TEST = 10L * 1024 * 1024

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
 * Registers a second user under the OPEN policy (ACTIVE MEMBER, canEdit defaults true) and
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
