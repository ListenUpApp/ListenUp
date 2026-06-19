package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.ImportRoutePaths
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.imports.ImportStatus
import com.calypsan.listenup.api.dto.imports.ImportSummary
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.ImportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.absimport.AbsSchema
import com.calypsan.listenup.server.absimport.buildSyntheticAbsBackupZip
import com.calypsan.listenup.server.absimport.zipOf
import com.calypsan.listenup.server.module
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
import io.ktor.utils.io.ByteReadChannel
import kotlinx.io.asSource
import kotlinx.io.buffered
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * Integration tests for the ABS-import upload REST route:
 *  - `POST /api/v1/admin/imports/abs/upload` — stages a `.audiobookshelf` zip and extracts its
 *    `absdatabase.sqlite` into `imports/<id>/`.
 *
 * Boots the full [module] with an isolated SQLite DB and a shared temp homeDir so the import
 * working directories resolve to a known location on disk for assertion.
 */
class ImportRoutesTest :
    FunSpec({
        test("ROOT upload stages the ABS database and returns an UPLOADED summary") {
            val homeDir = Files.createTempDirectory("listenup-import-routes-ok-")
            try {
                testApplication {
                    useIsolatedTestConfig(homeDir = homeDir.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }
                    val (token, _) = client.setupRoot()

                    val response = client.uploadAbsBackup(token, buildSyntheticAbsBackupZip())
                    response.status shouldBe HttpStatusCode.OK

                    val summary = response.body<ImportSummary>()
                    summary.id.value shouldStartWith "abs-"
                    summary.status shouldBe ImportStatus.UPLOADED
                    summary.bookCount shouldBe 0
                    summary.userCount shouldBe 0

                    // The extracted ABS database must exist on disk under imports/<id>/.
                    val absDb = homeDir.resolve("imports").resolve(summary.id.value).resolve(AbsSchema.DB_FILENAME)
                    absDb.exists() shouldBe true
                }
            } finally {
                homeDir.toFile().deleteRecursively()
            }
        }

        test("non-admin MEMBER gets 403 PermissionDenied on upload") {
            val homeDir = Files.createTempDirectory("listenup-import-routes-403-")
            try {
                testApplication {
                    useIsolatedTestConfig(homeDir = homeDir.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }
                    val (_, memberToken) = client.setupRootAndRegisterMember()

                    val response = client.uploadAbsBackup(memberToken, buildSyntheticAbsBackupZip())
                    response.status shouldBe HttpStatusCode.Forbidden
                    val error = contractJson.decodeFromString<AppError>(response.readRawBytes().decodeToString())
                    error.shouldBeInstanceOf<AuthError.PermissionDenied>()
                }
            } finally {
                homeDir.toFile().deleteRecursively()
            }
        }

        test("a zip without absdatabase.sqlite returns 422 and leaves no import dir behind") {
            val homeDir = Files.createTempDirectory("listenup-import-routes-noabs-")
            try {
                testApplication {
                    useIsolatedTestConfig(homeDir = homeDir.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }
                    val (token, _) = client.setupRoot()

                    val badZip = zipOf("readme.txt" to "no database here".encodeToByteArray())
                    val response = client.uploadAbsBackup(token, badZip)
                    response.status shouldBe HttpStatusCode.UnprocessableEntity
                    val error = contractJson.decodeFromString<AppError>(response.readRawBytes().decodeToString())
                    error.shouldBeInstanceOf<ImportError.UploadFailed>()

                    // The partial import directory must have been cleaned up — only .tmp remains.
                    val importsDir = homeDir.resolve("imports")
                    val leftover = importsDir.listImportSubdirs()
                    leftover shouldBe emptyList()
                }
            } finally {
                homeDir.toFile().deleteRecursively()
            }
        }

        test("upload larger than the 50 MiB Ktor default formFieldLimit is not rejected with 500") {
            // Regression guard: Ktor's default formFieldLimit is 50 MiB. Real ABS backups routinely
            // exceed this. Before the fix, sending >50 MiB body caused a 500 IOException from the
            // multipart parser before the handler could stream it to disk. After the fix (5 GiB cap),
            // the parser accepts the body, the handler streams it to a temp file, extractAbsDatabase
            // fails (no absdatabase.sqlite in a stream of zeroes), and the route returns a typed 422
            // ImportError.UploadFailed — NOT a 500 Internal Server Error.
            //
            // The body is produced lazily via ChannelProvider so no large ByteArray is allocated.
            val homeDir = Files.createTempDirectory("listenup-import-routes-largebody-")
            try {
                testApplication {
                    useIsolatedTestConfig(homeDir = homeDir.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }
                    val (token, _) = client.setupRoot()

                    // 55 MiB — just above the old 50 MiB default.
                    val largeSizeBytes = 55L * 1024L * 1024L
                    val response = client.uploadAbsBackupStreamed(token, largeSizeBytes)

                    // Must NOT be a 500 (the multipart-limit IOException path).
                    response.status shouldNotBe HttpStatusCode.InternalServerError
                    // Must be the handled typed error for a zip that contains no absdatabase.sqlite.
                    response.status shouldBe HttpStatusCode.UnprocessableEntity
                    val error = contractJson.decodeFromString<AppError>(response.readRawBytes().decodeToString())
                    error.shouldBeInstanceOf<ImportError.UploadFailed>()
                }
            } finally {
                homeDir.toFile().deleteRecursively()
            }
        }
    })

private suspend fun HttpClient.uploadAbsBackup(
    token: String,
    zipBytes: ByteArray,
) = post(ImportRoutePaths.ABS_UPLOAD) {
    bearerAuth(token)
    setBody(
        MultiPartFormDataContent(
            formData {
                append(
                    "file",
                    zipBytes,
                    Headers.build {
                        append(HttpHeaders.ContentType, "application/zip")
                        append(HttpHeaders.ContentDisposition, "filename=\"library.audiobookshelf\"")
                    },
                )
            },
        ),
    )
}

/**
 * Uploads a lazily-produced body of [sizeBytes] zero bytes via [ChannelProvider] — no large
 * [ByteArray] is allocated. The channel is backed by a [ZeroInputStream] wrapped in a
 * [kotlinx.io.Source], so nothing is buffered in memory beyond the read-side chunk window.
 */
private suspend fun HttpClient.uploadAbsBackupStreamed(
    token: String,
    sizeBytes: Long,
) = post(ImportRoutePaths.ABS_UPLOAD) {
    bearerAuth(token)
    setBody(
        MultiPartFormDataContent(
            formData {
                append(
                    "file",
                    ChannelProvider(size = sizeBytes) {
                        ByteReadChannel(ZeroInputStream(sizeBytes).asSource().buffered())
                    },
                    Headers.build {
                        append(HttpHeaders.ContentType, "application/zip")
                        append(HttpHeaders.ContentDisposition, "filename=\"large.audiobookshelf\"")
                    },
                )
            },
        ),
    )
}

/** An [InputStream] that produces exactly [totalBytes] zero bytes and then signals EOF. */
private class ZeroInputStream(
    private val totalBytes: Long,
) : InputStream() {
    private var remaining = totalBytes

    override fun read(): Int =
        if (remaining > 0) {
            remaining--
            0
        } else {
            -1
        }

    override fun read(
        b: ByteArray,
        off: Int,
        len: Int,
    ): Int {
        if (remaining <= 0) return -1
        val n = minOf(len.toLong(), remaining).toInt()
        b.fill(0, off, off + n)
        remaining -= n
        return n
    }
}

/** Lists `imports/` subdirectories that are real import jobs (excludes the `.tmp` scratch dir). */
private fun Path.listImportSubdirs(): List<String> {
    if (!exists()) return emptyList()
    return Files.newDirectoryStream(this).use { stream ->
        stream
            .filter { Files.isDirectory(it) && it.fileName.toString() != ".tmp" }
            .map { it.fileName.toString() }
    }
}

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
 * Runs first-user setup (ROOT) and registers a second MEMBER user. Returns (rootToken, memberToken).
 * The OPEN registration policy makes the second user ACTIVE immediately.
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
