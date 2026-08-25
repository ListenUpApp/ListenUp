package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.UploadRoutePaths
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.RegisterResult
import com.calypsan.listenup.api.dto.uploads.UploadSessionSummary
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.UploadError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.testing.publicAuthService
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readRawBytes
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.serialization.kotlinx.json.json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * The upload REST surface end to end through the real [module]: session lifecycle, the admin
 * gate, and — the part that matters most — that a hostile `relPath` is refused **over the wire**,
 * after Ktor's own query-parameter decoding has had its turn at it.
 *
 * The traversal cases are asserted here rather than only at the unit level because the encoded
 * forms only become themselves once Ktor decodes them; a unit test cannot prove what the server
 * actually receives.
 */
class UploadRoutesTest :
    FunSpec({

        test("ROOT can create a session, stream a file into it, and see the totals grow") {
            withUploadServer { client, token, homeDir ->
                val session = client.createSession(token).body<UploadSessionSummary>()
                session.sessionId shouldStartWith "up-"
                session.fileCount shouldBe 0

                val response = client.uploadFile(token, session.sessionId, "Book/01.m4b", "hello".encodeToByteArray())
                response.status shouldBe HttpStatusCode.OK
                val after = response.body<UploadSessionSummary>()
                after.fileCount shouldBe 1
                after.totalBytes shouldBe 5L

                homeDir
                    .resolve("uploads")
                    .resolve(session.sessionId)
                    .resolve("Book")
                    .resolve("01.m4b")
                    .exists() shouldBe true
            }
        }

        test("a non-admin MEMBER cannot create an upload session") {
            withUploadServer { client, _, _ ->
                val memberToken = registerMember()
                val response = client.createSession(memberToken)
                response.status shouldBe HttpStatusCode.Forbidden
                response.decodeError().shouldBeInstanceOf<AuthError.PermissionDenied>()
            }
        }

        test("an unknown session id is a typed 410, never a path") {
            withUploadServer { client, token, _ ->
                // 410 rather than 404 — see UploadError.toHttpStatus: the global StatusPages
                // `status(NotFound)` handler rewrites every 404 body, typed ones included.
                val response = client.uploadFile(token, "up-nope", "x.m4b", "hi".encodeToByteArray())
                response.status shouldBe HttpStatusCode.Gone
                response.decodeError().shouldBeInstanceOf<UploadError.SessionNotFound>()
            }
        }

        test("a session id that is itself a traversal never resolves to a path") {
            withUploadServer { client, token, homeDir ->
                // The id lands in the {sessionId} path segment; the guard is isSafeUploadSessionId,
                // not the routing table.
                client.uploadFile(token, "..", "x.m4b", "hi".encodeToByteArray())
                client.uploadFile(token, "up-../..", "x.m4b", "hi".encodeToByteArray())
                homeDir.resolve("uploads").filesUnder() shouldBe emptyList()
            }
        }

        // ── traversal, over the wire ────────────────────────────────────────────

        listOf(
            "an absolute path" to "/etc/cron.d/pwned",
            "a leading .." to "../../pwned.m4b",
            "a .. buried mid-path" to "book/../../../pwned.m4b",
            "percent-encoded .." to "%2E%2E%2F%2E%2E%2Fpwned.m4b",
            "a backslash traversal" to "..\\..\\pwned.m4b",
            "a Windows drive path" to "C:\\Windows\\pwned.dll",
            "an empty segment" to "book//pwned.m4b",
            "a lone dot segment" to "book/./pwned.m4b",
            "a NUL byte" to "book/\u0000pwned.m4b",
        ).forEach { (label, relPath) ->
            test("$label in relPath is refused before any file is opened") {
                withUploadServer { client, token, homeDir ->
                    val session = client.createSession(token).body<UploadSessionSummary>()
                    val response = client.uploadFile(token, session.sessionId, relPath, "pwned".encodeToByteArray())

                    response.status shouldBe HttpStatusCode.BadRequest
                    response.decodeError().shouldBeInstanceOf<UploadError.InvalidFilePath>()
                    // Not one byte landed — not in the session, not anywhere under the data home.
                    homeDir.resolve("uploads").filesUnder() shouldBe emptyList()
                }
            }
        }

        test("double-percent-encoded .. is inert — it decodes to one odd filename, still inside the session") {
            // The proof that decoding exactly once (Ktor's) is right and a second decode would be
            // wrong: what survives one decode is `%2e%2e%2fpwned.m4b`, a single legal segment.
            withUploadServer { client, token, homeDir ->
                val session = client.createSession(token).body<UploadSessionSummary>()
                val response =
                    client.uploadFile(token, session.sessionId, "%252e%252e%252fpwned.m4b", "x".encodeToByteArray())

                response.status shouldBe HttpStatusCode.OK
                homeDir.resolve("uploads").resolve(session.sessionId).filesUnder() shouldBe
                    listOf("%2e%2e%2fpwned.m4b")
            }
        }

        test("a missing relPath is refused") {
            withUploadServer { client, token, _ ->
                val session = client.createSession(token).body<UploadSessionSummary>()
                val response = client.uploadFile(token, session.sessionId, relPath = null, bytes = "x".encodeToByteArray())
                response.status shouldBe HttpStatusCode.BadRequest
                response.decodeError().shouldBeInstanceOf<UploadError.InvalidFilePath>()
            }
        }

        // ── abandoning ──────────────────────────────────────────────────────────

        test("abandoning a session removes its staging directory and writes nothing to the library") {
            withUploadServer { client, token, homeDir ->
                val session = client.createSession(token).body<UploadSessionSummary>()
                client.uploadFile(token, session.sessionId, "Book/01.m4b", "hello".encodeToByteArray())

                val response = client.delete(UploadRoutePaths.session(session.sessionId)) { bearerAuth(token) }
                response.status shouldBe HttpStatusCode.NoContent
                homeDir.resolve("uploads").resolve(session.sessionId).exists() shouldBe false

                // A second abandon is a clean typed refusal, not a crash.
                val second = client.delete(UploadRoutePaths.session(session.sessionId)) { bearerAuth(token) }
                second.status shouldBe HttpStatusCode.Gone
                second.decodeError().shouldBeInstanceOf<UploadError.SessionNotFound>()
            }
        }
    })

// ── helpers ─────────────────────────────────────────────────────────────────────

/** Boots the real server module against a fresh temp home and hands [block] an authenticated ROOT client. */
private fun withUploadServer(block: suspend ApplicationTestBuilder.(HttpClient, String, Path) -> Unit) {
    val homeDir = Files.createTempDirectory("listenup-upload-routes-")
    try {
        testApplication {
            useIsolatedTestConfig(homeDir = homeDir.toString())
            application { module() }
            val client = createClient { install(ContentNegotiation) { json(contractJson) } }
            block(client, setupRoot(), homeDir)
        }
    } finally {
        homeDir.toFile().deleteRecursively()
    }
}

private suspend fun HttpClient.createSession(token: String) = post(UploadRoutePaths.SESSIONS) { bearerAuth(token) }

private suspend fun HttpClient.uploadFile(
    token: String,
    sessionId: String,
    relPath: String?,
    bytes: ByteArray,
): HttpResponse {
    val query = relPath?.let { "?${UploadRoutePaths.REL_PATH_PARAM}=${it.urlEncoded()}" }.orEmpty()
    return post("${UploadRoutePaths.file(sessionId)}$query") {
        bearerAuth(token)
        setBody(
            MultiPartFormDataContent(
                formData {
                    append(
                        "file",
                        bytes,
                        Headers.build {
                            append(HttpHeaders.ContentType, "application/octet-stream")
                            append(HttpHeaders.ContentDisposition, "filename=\"part.bin\"")
                        },
                    )
                },
            ),
        )
    }
}

/**
 * Percent-encodes [this] for a query value, leaving `%` alone.
 *
 * Leaving `%` unescaped is deliberate: the encoded-traversal cases send `%2E%2E%2F` and
 * `%252e%252e%252f` as literal wire bytes, and re-encoding them here would defeat the very thing
 * those cases exist to test.
 */
private fun String.urlEncoded(): String =
    buildString {
        this@urlEncoded.forEach { c ->
            if (c.isLetterOrDigit() || c in "-._~%") {
                append(c)
            } else {
                c.toString().encodeToByteArray().forEach { b -> append('%').append("%02X".format(b)) }
            }
        }
    }

/** Relative paths of every regular file under this directory, or empty when it is absent. */
private fun Path.filesUnder(): List<String> {
    if (!exists()) return emptyList()
    return Files.walk(this).use { stream ->
        stream.filter { Files.isRegularFile(it) }.map { this.relativize(it).toString() }.toList()
    }
}

private suspend fun HttpResponse.decodeError(): AppError = contractJson.decodeFromString<AppError>(readRawBytes().decodeToString())

/** Runs first-user setup and returns the ROOT access token. */
private suspend fun ApplicationTestBuilder.setupRoot(): String =
    publicAuthService()
        .setupRoot(RegisterRequest("root@x", "x".repeat(8), "Root"))
        .let { it as AppResult.Success<AuthSession> }
        .data
        .accessToken
        .value

/** Registers a second, non-admin user (the OPEN policy makes them ACTIVE) and returns their token. */
private suspend fun ApplicationTestBuilder.registerMember(): String =
    publicAuthService()
        .register(RegisterRequest("member@x", "x".repeat(8), "Member"))
        .let { it as AppResult.Success<RegisterResult> }
        .data
        .let { it as RegisterResult.Authenticated }
        .session
        .accessToken
        .value
