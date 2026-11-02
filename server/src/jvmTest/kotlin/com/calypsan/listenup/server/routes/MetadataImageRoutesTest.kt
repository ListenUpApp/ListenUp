package com.calypsan.listenup.server.routes

import io.ktor.server.testing.ApplicationTestBuilder

import com.calypsan.listenup.server.testing.publicAuthService

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
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
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
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString(), homeDir = libraryRoot.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }
                    val token = mintAccessToken()

                    val contributorRepo by application.inject<ContributorRepository>()
                    val id = contributorRepo.resolveOrCreate("Brandon Sanderson", sortName = null)
                    libraryRoot.writeManaged(BRANDON_PHOTO, byteArrayOf(1, 2, 3, 4))
                    contributorRepo.upsert(
                        ContributorSyncPayload(
                            id = id.value,
                            name = "Brandon Sanderson",
                            sortName = null,
                            revision = 1L,
                            updatedAt = 1_000L,
                            createdAt = 1_000L,
                            deletedAt = null,
                            imagePath = BRANDON_PHOTO,
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
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString(), homeDir = libraryRoot.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }
                    val token = mintAccessToken()

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
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString(), homeDir = libraryRoot.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }
                    val token = mintAccessToken()

                    val contributorRepo by application.inject<ContributorRepository>()
                    val id = contributorRepo.resolveOrCreate("No Photo Author", sortName = null)
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
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString(), homeDir = libraryRoot.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }
                    val token = mintAccessToken()

                    val contributorRepo by application.inject<ContributorRepository>()
                    val id = contributorRepo.resolveOrCreate("Missing File Author", sortName = null)
                    contributorRepo.upsert(
                        ContributorSyncPayload(
                            id = id.value,
                            name = "Missing File Author",
                            sortName = null,
                            revision = 1L,
                            updatedAt = 1_000L,
                            createdAt = 1_000L,
                            deletedAt = null,
                            imagePath = ABSENT_PHOTO,
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
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString(), homeDir = libraryRoot.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }
                    val token = mintAccessToken()

                    val contributorRepo by application.inject<ContributorRepository>()
                    val id = contributorRepo.resolveOrCreate("Traversal Author", sortName = null)
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
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString(), homeDir = libraryRoot.toString())
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
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString(), homeDir = libraryRoot.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }
                    val token = mintAccessToken()

                    val contributorRepo by application.inject<ContributorRepository>()
                    val id = contributorRepo.resolveOrCreate("Range Author", sortName = null)
                    val imgBytes = ByteArray(200) { it.toByte() }
                    libraryRoot.writeManaged(RANGE_PHOTO, imgBytes)
                    contributorRepo.upsert(
                        ContributorSyncPayload(
                            id = id.value,
                            name = "Range Author",
                            sortName = null,
                            revision = 1L,
                            updatedAt = 1_000L,
                            createdAt = 1_000L,
                            deletedAt = null,
                            imagePath = RANGE_PHOTO,
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
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString(), homeDir = libraryRoot.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }
                    val token = mintAccessToken()

                    val seriesRepo by application.inject<SeriesRepository>()
                    val id = seriesRepo.resolveOrCreate("The Stormlight Archive")
                    libraryRoot.writeManaged(STORMLIGHT_COVER, byteArrayOf(5, 6, 7, 8))
                    seriesRepo.upsert(
                        SeriesSyncPayload(
                            id = id.value,
                            name = "The Stormlight Archive",
                            sortName = null,
                            revision = 1L,
                            updatedAt = 1_000L,
                            createdAt = 1_000L,
                            deletedAt = null,
                            coverPath = STORMLIGHT_COVER,
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
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString(), homeDir = libraryRoot.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }
                    val token = mintAccessToken()

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
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString(), homeDir = libraryRoot.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }
                    val token = mintAccessToken()

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
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString(), homeDir = libraryRoot.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }
                    val token = mintAccessToken()

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
                            coverPath = ABSENT_COVER,
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
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString(), homeDir = libraryRoot.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }
                    val token = mintAccessToken()

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
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString(), homeDir = libraryRoot.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }

                    val response = client.get("/api/v1/series/any-id/cover")

                    response.status shouldBe HttpStatusCode.Unauthorized
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }

        // ─── Refused stored paths: only <folder>/<sha256>.<image ext> is served ───

        data class RefusedPath(
            val label: String,
            val stored: String,
        )

        val refusedContributorPaths =
            listOf(
                RefusedPath("outside the contributors image folder", "decoy.txt"),
                RefusedPath("in the wrong image folder", "series/$HEX.jpg"),
                RefusedPath("with a non-image extension", "contributors/$HEX.txt"),
            )

        for (case in refusedContributorPaths) {
            test("a stored contributor photo path ${case.label} is refused") {
                val libraryRoot = Files.createTempDirectory("listenup-meta-img-contributor-refused-")
                try {
                    testApplication {
                        useIsolatedTestConfig(libraryPath = libraryRoot.toString(), homeDir = libraryRoot.toString())
                        application { module() }
                        val client = createClient { install(ContentNegotiation) { json(contractJson) } }
                        val token = mintAccessToken()

                        val repo by application.inject<ContributorRepository>()
                        val id = repo.resolveOrCreate("Refused Path", sortName = null)
                        libraryRoot.writeManaged(case.stored, DECOY_BYTES)
                        repo.upsert(
                            ContributorSyncPayload(
                                id = id.value,
                                name = "Refused Path",
                                sortName = null,
                                revision = 1L,
                                updatedAt = 1_000L,
                                createdAt = 1_000L,
                                deletedAt = null,
                                imagePath = case.stored,
                            ),
                            clientOpId = null,
                        )

                        val response =
                            client.get("/api/v1/contributors/${id.value}/photo") {
                                bearerAuth(token)
                            }

                        response.status shouldBe HttpStatusCode.BadRequest
                        response.body<ByteArray>().contentEquals(DECOY_BYTES) shouldBe false
                    }
                } finally {
                    libraryRoot.toFile().deleteRecursively()
                }
            }
        }

        val refusedSeriesPaths =
            listOf(
                RefusedPath("outside the series image folder", "decoy.txt"),
                RefusedPath("in the wrong image folder", "contributors/$HEX.jpg"),
                RefusedPath("with a non-image extension", "series/$HEX.txt"),
            )

        for (case in refusedSeriesPaths) {
            test("a stored series cover path ${case.label} is refused") {
                val libraryRoot = Files.createTempDirectory("listenup-meta-img-series-refused-")
                try {
                    testApplication {
                        useIsolatedTestConfig(libraryPath = libraryRoot.toString(), homeDir = libraryRoot.toString())
                        application { module() }
                        val client = createClient { install(ContentNegotiation) { json(contractJson) } }
                        val token = mintAccessToken()

                        val repo by application.inject<SeriesRepository>()
                        val id = repo.resolveOrCreate("Refused Path")
                        libraryRoot.writeManaged(case.stored, DECOY_BYTES)
                        repo.upsert(
                            SeriesSyncPayload(
                                id = id.value,
                                name = "Refused Path",
                                sortName = null,
                                revision = 1L,
                                updatedAt = 1_000L,
                                createdAt = 1_000L,
                                deletedAt = null,
                                coverPath = case.stored,
                            ),
                            clientOpId = null,
                        )

                        val response =
                            client.get("/api/v1/series/${id.value}/cover") {
                                bearerAuth(token)
                            }

                        response.status shouldBe HttpStatusCode.BadRequest
                        response.body<ByteArray>().contentEquals(DECOY_BYTES) shouldBe false
                    }
                } finally {
                    libraryRoot.toFile().deleteRecursively()
                }
            }
        }
    })

// ─── Auth helper (mirrors pattern in BookRoutesTest) ─────────────────────────

private suspend fun ApplicationTestBuilder.mintAccessToken(): String {
    publicAuthService().setupRoot(RegisterRequest("root@x", "x".repeat(8), "Root"))
    return publicAuthService()
        .login(LoginRequest("root@x", "x".repeat(8)))
        .shouldBeInstanceOf<AppResult.Success<AuthSession>>()
        .data
        .accessToken
        .value
}

// ─── Managed-path fixtures ───────────────────────────────────────────────────

private const val HEX = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
private const val HEX_2 = "1123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
private const val HEX_3 = "2123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
private const val BRANDON_PHOTO = "contributors/$HEX.jpg"
private const val RANGE_PHOTO = "contributors/$HEX_2.jpg"
private const val ABSENT_PHOTO = "contributors/$HEX_3.jpg"
private const val STORMLIGHT_COVER = "series/$HEX.jpg"
private const val ABSENT_COVER = "series/$HEX_3.jpg"
private val DECOY_BYTES = byteArrayOf(9, 9, 9)

private fun java.nio.file.Path.writeManaged(
    relativePath: String,
    bytes: ByteArray,
) {
    val file = resolve(relativePath).toFile()
    file.parentFile.mkdirs()
    file.writeBytes(bytes)
}
