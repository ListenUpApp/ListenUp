package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.backup.BackupSummary
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.BackupError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.backup.BackupPaths
import com.calypsan.listenup.server.backup.backupTestFixture
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
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
import java.util.zip.ZipFile

/**
 * Integration tests for the binary backup REST routes:
 *  - `GET  /api/v1/admin/backups/{id}/download` — streams the archive as an attachment.
 *  - `POST /api/v1/admin/backups/upload`         — stages a foreign backup into backupsDir.
 *
 * Boots the full [module] with an isolated SQLite DB and a shared temp homeDir so
 * [BackupPaths] resolves to a known directory. Uses the real REST auth surface to
 * mint a ROOT token (all-bypassing ADMIN) and (for the non-admin tests) a MEMBER token.
 */
class BackupRoutesTest :
    FunSpec({

        test("GET download returns 200 zip with manifest for an existing backup") {
            val homeDir = Files.createTempDirectory("listenup-backup-routes-dl-")
            try {
                testApplication {
                    useIsolatedTestConfig(homeDir = homeDir.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }
                    val (token, _) = client.setupRoot()

                    // Place a real backup archive in the server's backups dir using a fixture that
                    // shares the same homeDir so BackupPaths.archiveFor resolves it correctly.
                    val fixture = backupTestFixture(homeDir = homeDir, withImages = false)
                    try {
                        fixture.archive.create("backup-dl-test", includeImages = false, onEvent = {})
                    } finally {
                        fixture.close()
                    }

                    val response =
                        client.get("/api/v1/admin/backups/backup-dl-test/download") {
                            bearerAuth(token)
                        }
                    response.status shouldBe HttpStatusCode.OK

                    val body = response.readRawBytes()
                    body.isNotEmpty() shouldBe true

                    // Verify the response bytes form a valid zip containing manifest.json + db.
                    val tmpZip = Files.createTempFile("dl-verify-", ".zip")
                    try {
                        Files.write(tmpZip, body)
                        ZipFile(tmpZip.toFile()).use { zf ->
                            zf.getEntry("manifest.json") shouldNotBe null
                            zf.getEntry("listenup.db") shouldNotBe null
                        }
                    } finally {
                        Files.deleteIfExists(tmpZip)
                    }

                    val disposition = response.headers[HttpHeaders.ContentDisposition]
                    disposition shouldNotBe null
                    disposition!!.contains("attachment") shouldBe true
                    disposition.contains("backup-dl-test.listenup.zip") shouldBe true
                }
            } finally {
                homeDir.toFile().deleteRecursively()
            }
        }

        test("GET download returns 404 for a missing backup id") {
            val homeDir = Files.createTempDirectory("listenup-backup-routes-missing-")
            try {
                testApplication {
                    useIsolatedTestConfig(homeDir = homeDir.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }
                    val (token, _) = client.setupRoot()

                    val response =
                        client.get("/api/v1/admin/backups/backup-nonexistent/download") {
                            bearerAuth(token)
                        }
                    // The route returns BackupNotFound (→ 404); the StatusPages generic 404 handler
                    // then takes over for the REST surface. Status is the reliable assertion here —
                    // typed errors from this domain cross the wire as AppError on the RPC surface.
                    response.status shouldBe HttpStatusCode.NotFound
                }
            } finally {
                homeDir.toFile().deleteRecursively()
            }
        }

        test("POST upload stages a valid foreign backup and it becomes downloadable") {
            val homeDir = Files.createTempDirectory("listenup-backup-routes-upload-")
            try {
                testApplication {
                    useIsolatedTestConfig(homeDir = homeDir.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }
                    val (token, _) = client.setupRoot()

                    // Build a valid archive in a separate temp home so it's "foreign" to the server.
                    val zipBytes: ByteArray
                    val foreignFixture = backupTestFixture(withImages = false)
                    try {
                        val archivePath =
                            foreignFixture.archive.create(
                                "backup-foreign",
                                includeImages = false,
                                onEvent = {},
                            )
                        zipBytes = Files.readAllBytes(java.nio.file.Path.of(archivePath.toString()))
                    } finally {
                        foreignFixture.close()
                        foreignFixture.homeDir.toFile().deleteRecursively()
                    }

                    val uploadResponse =
                        client.post("/api/v1/admin/backups/upload") {
                            bearerAuth(token)
                            setBody(
                                MultiPartFormDataContent(
                                    formData {
                                        append(
                                            "file",
                                            zipBytes,
                                            Headers.build {
                                                append(HttpHeaders.ContentType, "application/zip")
                                                append(
                                                    HttpHeaders.ContentDisposition,
                                                    "filename=\"backup-foreign.listenup.zip\"",
                                                )
                                            },
                                        )
                                    },
                                ),
                            )
                        }
                    uploadResponse.status shouldBe HttpStatusCode.OK
                    val summary = uploadResponse.body<BackupSummary>()
                    summary.id.value.startsWith("backup-") shouldBe true
                    summary.includesImages shouldBe false

                    // The staged archive must now be downloadable by the returned id.
                    val downloadResponse =
                        client.get("/api/v1/admin/backups/${summary.id.value}/download") {
                            bearerAuth(token)
                        }
                    downloadResponse.status shouldBe HttpStatusCode.OK
                    downloadResponse.readRawBytes().isNotEmpty() shouldBe true
                }
            } finally {
                homeDir.toFile().deleteRecursively()
            }
        }

        test("POST upload with a corrupt (non-zip) body returns 422 CorruptArchive") {
            val homeDir = Files.createTempDirectory("listenup-backup-routes-corrupt-")
            try {
                testApplication {
                    useIsolatedTestConfig(homeDir = homeDir.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }
                    val (token, _) = client.setupRoot()

                    val uploadResponse =
                        client.post("/api/v1/admin/backups/upload") {
                            bearerAuth(token)
                            setBody(
                                MultiPartFormDataContent(
                                    formData {
                                        append(
                                            "file",
                                            "this is definitely not a zip file".encodeToByteArray(),
                                            Headers.build {
                                                append(HttpHeaders.ContentType, "application/octet-stream")
                                                append(HttpHeaders.ContentDisposition, "filename=\"bad.zip\"")
                                            },
                                        )
                                    },
                                ),
                            )
                        }
                    uploadResponse.status shouldBe HttpStatusCode.UnprocessableEntity
                    val error =
                        contractJson.decodeFromString<AppError>(uploadResponse.readRawBytes().decodeToString())
                    error.shouldBeInstanceOf<BackupError.CorruptArchive>()
                }
            } finally {
                homeDir.toFile().deleteRecursively()
            }
        }

        test("non-admin MEMBER gets 403 PermissionDenied on GET download") {
            val homeDir = Files.createTempDirectory("listenup-backup-routes-403dl-")
            try {
                testApplication {
                    useIsolatedTestConfig(homeDir = homeDir.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }
                    val (_, memberToken) = client.setupRootAndRegisterMember()

                    val response =
                        client.get("/api/v1/admin/backups/backup-any-id/download") {
                            bearerAuth(memberToken)
                        }
                    response.status shouldBe HttpStatusCode.Forbidden
                    val error = contractJson.decodeFromString<AppError>(response.readRawBytes().decodeToString())
                    error.shouldBeInstanceOf<AuthError.PermissionDenied>()
                }
            } finally {
                homeDir.toFile().deleteRecursively()
            }
        }

        test("POST upload accepts a body larger than Ktor's default 50 MiB formFieldLimit") {
            // Structural limit test: streams 51 MiB of zero bytes as a multipart part.
            // If receiveMultipart() were called without an explicit formFieldLimit the default
            // 50 MiB cap would reject this body before it reached the archive validator;
            // the route would return 413 instead of 422 CorruptArchive.
            // We assert 422 (CorruptArchive from the validator) rather than 413, which proves the
            // configured MAX_BACKUP_RESTORE_BYTES limit (5 GiB) was passed to receiveMultipart().
            val homeDir = Files.createTempDirectory("listenup-backup-routes-limit-")
            try {
                testApplication {
                    useIsolatedTestConfig(homeDir = homeDir.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }
                    val (token, _) = client.setupRoot()

                    // 51 MiB of zeros — exceeds Ktor's default 50 MiB cap but well within
                    // MAX_BACKUP_RESTORE_BYTES (5 GiB). Content is intentionally invalid (not a
                    // zip) so the archive validator rejects it with CorruptArchive (422).
                    val fiftyOneMib = ByteArray(51 * 1024 * 1024)
                    val response =
                        client.post("/api/v1/admin/backups/upload") {
                            bearerAuth(token)
                            setBody(
                                MultiPartFormDataContent(
                                    formData {
                                        append(
                                            "file",
                                            fiftyOneMib,
                                            Headers.build {
                                                append(HttpHeaders.ContentType, "application/zip")
                                                append(
                                                    HttpHeaders.ContentDisposition,
                                                    "filename=\"big.zip\"",
                                                )
                                            },
                                        )
                                    },
                                ),
                            )
                        }
                    // 422 = CorruptArchive from the validator — the upload was NOT rejected on
                    // size (that would be 413 or a Ktor-internal failure).
                    response.status shouldBe HttpStatusCode.UnprocessableEntity
                    val error =
                        contractJson.decodeFromString<AppError>(response.readRawBytes().decodeToString())
                    error.shouldBeInstanceOf<BackupError.CorruptArchive>()
                }
            } finally {
                homeDir.toFile().deleteRecursively()
            }
        }

        test("non-admin MEMBER gets 403 PermissionDenied on POST upload") {
            val homeDir = Files.createTempDirectory("listenup-backup-routes-403up-")
            try {
                testApplication {
                    useIsolatedTestConfig(homeDir = homeDir.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }
                    val (_, memberToken) = client.setupRootAndRegisterMember()

                    val response =
                        client.post("/api/v1/admin/backups/upload") {
                            bearerAuth(memberToken)
                            setBody(
                                MultiPartFormDataContent(
                                    formData {
                                        append(
                                            "file",
                                            ByteArray(16),
                                            Headers.build {
                                                append(HttpHeaders.ContentType, "application/zip")
                                                append(HttpHeaders.ContentDisposition, "filename=\"x.zip\"")
                                            },
                                        )
                                    },
                                ),
                            )
                        }
                    response.status shouldBe HttpStatusCode.Forbidden
                    val error = contractJson.decodeFromString<AppError>(response.readRawBytes().decodeToString())
                    error.shouldBeInstanceOf<AuthError.PermissionDenied>()
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

/**
 * Runs first-user setup (ROOT) and registers a second MEMBER user. Returns
 * (rootToken, memberToken). The OPEN registration policy makes the second user ACTIVE immediately.
 */
private suspend fun HttpClient.setupRootAndRegisterMember(): Pair<String, String> {
    val (rootToken, _) = setupRoot()
    val memberSession =
        post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest("member@x", "x".repeat(8), "Member"))
        }.body<AppResult<com.calypsan.listenup.api.dto.auth.RegisterResult>>()
            .let { it as AppResult.Success<com.calypsan.listenup.api.dto.auth.RegisterResult> }
            .data
            .let { it as com.calypsan.listenup.api.dto.auth.RegisterResult.Authenticated }
            .session
    return rootToken to memberSession.accessToken.value
}
