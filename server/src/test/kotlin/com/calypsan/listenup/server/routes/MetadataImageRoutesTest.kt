package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.ContributorSyncPayload
import com.calypsan.listenup.api.sync.SeriesSyncPayload
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import org.koin.ktor.ext.inject

/**
 * Integration tests for contributor photo and series cover image routes.
 *
 * Uses the full [module] boots with a real SQLite DB and a configured
 * library path. Files are written to a per-test temp directory that becomes the
 * library root.
 *
 * Covers:
 * - 200 with bytes when the contributor / series has an imagePath / coverPath
 *   and the file exists.
 * - 404 when the contributor / series is missing from the DB.
 * - 404 when imagePath / coverPath is null.
 * - 404 when the path is set but the file is absent from disk.
 * - 206 Partial Content for range requests (via the PartialContent plugin).
 * - 400 (Bad Request) for path-traversal attempts (../foo).
 * - 401 for unauthenticated requests.
 */
class MetadataImageRoutesTest :
    FunSpec({

        // ─── Contributor photo ────────────────────────────────────────────────

        test("GET /api/v1/contributors/{id}/photo returns 200 with bytes when the file exists") {
            val libraryRoot = Files.createTempDirectory("listenup-meta-img-contrib-200-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }
                    val token = client.mintAccessToken()

                    val contributorRepo by application.inject<ContributorRepository>()
                    val id = contributorRepo.resolveOrCreate("Brandon Sanderson")
                    val imgFile = libraryRoot.resolve("brandon.jpg").toFile()
                    imgFile.writeBytes(byteArrayOf(1, 2, 3, 4))
                    contributorRepo.upsert(
                        ContributorSyncPayload(
                            id = id.value,
                            name = "Brandon Sanderson",
                            sortName = null,
                            revision = 1L,
                            updatedAt = 1_000L,
                            createdAt = 1_000L,
                            deletedAt = null,
                            imagePath = "brandon.jpg",
                        ),
                        clientOpId = null,
                    )

                    val response =
                        client.get("/api/v1/contributors/${id.value}/photo") {
                            bearerAuth(token)
                        }

                    response.status shouldBe HttpStatusCode.OK
                    response.body<ByteArray>() shouldBe byteArrayOf(1, 2, 3, 4)
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }

        test("GET /api/v1/contributors/{id}/photo returns 404 for unknown contributor") {
            val libraryRoot = Files.createTempDirectory("listenup-meta-img-contrib-404-unknown-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }
                    val token = client.mintAccessToken()

                    val response =
                        client.get("/api/v1/contributors/nonexistent/photo") {
                            bearerAuth(token)
                        }

                    response.status shouldBe HttpStatusCode.NotFound
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }

        test("GET /api/v1/contributors/{id}/photo returns 404 when imagePath is null") {
            val libraryRoot = Files.createTempDirectory("listenup-meta-img-contrib-404-nopath-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }
                    val token = client.mintAccessToken()

                    val contributorRepo by application.inject<ContributorRepository>()
                    val id = contributorRepo.resolveOrCreate("No Photo Author")
                    // imagePath stays null (not set)

                    val response =
                        client.get("/api/v1/contributors/${id.value}/photo") {
                            bearerAuth(token)
                        }

                    response.status shouldBe HttpStatusCode.NotFound
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }

        test("GET /api/v1/contributors/{id}/photo returns 404 when the file is missing from disk") {
            val libraryRoot = Files.createTempDirectory("listenup-meta-img-contrib-404-nofile-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }
                    val token = client.mintAccessToken()

                    val contributorRepo by application.inject<ContributorRepository>()
                    val id = contributorRepo.resolveOrCreate("Missing File Author")
                    contributorRepo.upsert(
                        ContributorSyncPayload(
                            id = id.value,
                            name = "Missing File Author",
                            sortName = null,
                            revision = 1L,
                            updatedAt = 1_000L,
                            createdAt = 1_000L,
                            deletedAt = null,
                            imagePath = "does-not-exist.jpg",
                        ),
                        clientOpId = null,
                    )

                    val response =
                        client.get("/api/v1/contributors/${id.value}/photo") {
                            bearerAuth(token)
                        }

                    response.status shouldBe HttpStatusCode.NotFound
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }

        test("GET /api/v1/contributors/{id}/photo returns 400 for a path-traversal imagePath") {
            val libraryRoot = Files.createTempDirectory("listenup-meta-img-contrib-traversal-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }
                    val token = client.mintAccessToken()

                    val contributorRepo by application.inject<ContributorRepository>()
                    val id = contributorRepo.resolveOrCreate("Traversal Author")
                    contributorRepo.upsert(
                        ContributorSyncPayload(
                            id = id.value,
                            name = "Traversal Author",
                            sortName = null,
                            revision = 1L,
                            updatedAt = 1_000L,
                            createdAt = 1_000L,
                            deletedAt = null,
                            imagePath = "../../etc/passwd",
                        ),
                        clientOpId = null,
                    )

                    val response =
                        client.get("/api/v1/contributors/${id.value}/photo") {
                            bearerAuth(token)
                        }

                    response.status shouldBe HttpStatusCode.BadRequest
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }

        test("GET /api/v1/contributors/{id}/photo returns 401 without a token") {
            val libraryRoot = Files.createTempDirectory("listenup-meta-img-contrib-unauth-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }

                    val response = client.get("/api/v1/contributors/any-id/photo")

                    response.status shouldBe HttpStatusCode.Unauthorized
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }

        test("range request for contributor photo returns 206 Partial Content") {
            val libraryRoot = Files.createTempDirectory("listenup-meta-img-contrib-range-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }
                    val token = client.mintAccessToken()

                    val contributorRepo by application.inject<ContributorRepository>()
                    val id = contributorRepo.resolveOrCreate("Range Author")
                    val imgBytes = ByteArray(200) { it.toByte() }
                    libraryRoot.resolve("range.jpg").toFile().writeBytes(imgBytes)
                    contributorRepo.upsert(
                        ContributorSyncPayload(
                            id = id.value,
                            name = "Range Author",
                            sortName = null,
                            revision = 1L,
                            updatedAt = 1_000L,
                            createdAt = 1_000L,
                            deletedAt = null,
                            imagePath = "range.jpg",
                        ),
                        clientOpId = null,
                    )

                    val response =
                        client.get("/api/v1/contributors/${id.value}/photo") {
                            bearerAuth(token)
                            header(HttpHeaders.Range, "bytes=0-99")
                        }

                    response.status shouldBe HttpStatusCode.PartialContent
                    response.body<ByteArray>().size shouldBe 100
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }

        // ─── Series cover ──────────────────────────────────────────────────────

        test("GET /api/v1/series/{id}/cover returns 200 with bytes when the file exists") {
            val libraryRoot = Files.createTempDirectory("listenup-meta-img-series-200-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }
                    val token = client.mintAccessToken()

                    val seriesRepo by application.inject<SeriesRepository>()
                    val id = seriesRepo.resolveOrCreate("The Stormlight Archive")
                    val coverFile = libraryRoot.resolve("stormlight.jpg").toFile()
                    coverFile.writeBytes(byteArrayOf(5, 6, 7, 8))
                    seriesRepo.upsert(
                        SeriesSyncPayload(
                            id = id.value,
                            name = "The Stormlight Archive",
                            sortName = null,
                            revision = 1L,
                            updatedAt = 1_000L,
                            createdAt = 1_000L,
                            deletedAt = null,
                            coverPath = "stormlight.jpg",
                        ),
                        clientOpId = null,
                    )

                    val response =
                        client.get("/api/v1/series/${id.value}/cover") {
                            bearerAuth(token)
                        }

                    response.status shouldBe HttpStatusCode.OK
                    response.body<ByteArray>() shouldBe byteArrayOf(5, 6, 7, 8)
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }

        test("GET /api/v1/series/{id}/cover returns 404 for unknown series") {
            val libraryRoot = Files.createTempDirectory("listenup-meta-img-series-404-unknown-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }
                    val token = client.mintAccessToken()

                    val response =
                        client.get("/api/v1/series/nonexistent/cover") {
                            bearerAuth(token)
                        }

                    response.status shouldBe HttpStatusCode.NotFound
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }

        test("GET /api/v1/series/{id}/cover returns 404 when coverPath is null") {
            val libraryRoot = Files.createTempDirectory("listenup-meta-img-series-404-nopath-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }
                    val token = client.mintAccessToken()

                    val seriesRepo by application.inject<SeriesRepository>()
                    val id = seriesRepo.resolveOrCreate("No Cover Series")
                    // coverPath stays null

                    val response =
                        client.get("/api/v1/series/${id.value}/cover") {
                            bearerAuth(token)
                        }

                    response.status shouldBe HttpStatusCode.NotFound
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }

        test("GET /api/v1/series/{id}/cover returns 404 when the file is missing from disk") {
            val libraryRoot = Files.createTempDirectory("listenup-meta-img-series-404-nofile-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }
                    val token = client.mintAccessToken()

                    val seriesRepo by application.inject<SeriesRepository>()
                    val id = seriesRepo.resolveOrCreate("Missing Cover Series")
                    seriesRepo.upsert(
                        SeriesSyncPayload(
                            id = id.value,
                            name = "Missing Cover Series",
                            sortName = null,
                            revision = 1L,
                            updatedAt = 1_000L,
                            createdAt = 1_000L,
                            deletedAt = null,
                            coverPath = "does-not-exist.jpg",
                        ),
                        clientOpId = null,
                    )

                    val response =
                        client.get("/api/v1/series/${id.value}/cover") {
                            bearerAuth(token)
                        }

                    response.status shouldBe HttpStatusCode.NotFound
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }

        test("GET /api/v1/series/{id}/cover returns 400 for a path-traversal coverPath") {
            val libraryRoot = Files.createTempDirectory("listenup-meta-img-series-traversal-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }
                    val token = client.mintAccessToken()

                    val seriesRepo by application.inject<SeriesRepository>()
                    val id = seriesRepo.resolveOrCreate("Traversal Series")
                    seriesRepo.upsert(
                        SeriesSyncPayload(
                            id = id.value,
                            name = "Traversal Series",
                            sortName = null,
                            revision = 1L,
                            updatedAt = 1_000L,
                            createdAt = 1_000L,
                            deletedAt = null,
                            coverPath = "../../etc/passwd",
                        ),
                        clientOpId = null,
                    )

                    val response =
                        client.get("/api/v1/series/${id.value}/cover") {
                            bearerAuth(token)
                        }

                    response.status shouldBe HttpStatusCode.BadRequest
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }

        test("GET /api/v1/series/{id}/cover returns 401 without a token") {
            val libraryRoot = Files.createTempDirectory("listenup-meta-img-series-unauth-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }

                    val response = client.get("/api/v1/series/any-id/cover")

                    response.status shouldBe HttpStatusCode.Unauthorized
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }
    })

// ─── Auth helper (mirrors pattern in BookRoutesTest) ─────────────────────────

private suspend fun HttpClient.mintAccessToken(): String {
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
