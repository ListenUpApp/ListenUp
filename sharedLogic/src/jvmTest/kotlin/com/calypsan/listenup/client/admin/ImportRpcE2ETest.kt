package com.calypsan.listenup.client.admin

import com.calypsan.listenup.api.ImportRoutePaths
import com.calypsan.listenup.api.ImportService
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.import.ImportAnalysis
import com.calypsan.listenup.api.dto.import.ImportResult
import com.calypsan.listenup.api.dto.import.ImportSummary
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.core.suspendRunCatching
import com.calypsan.listenup.client.data.remote.ImportRpcFactory
import com.calypsan.listenup.client.data.repository.ImportRepositoryImpl
import com.calypsan.listenup.core.FileSource
import com.calypsan.listenup.core.ImportId
import com.calypsan.listenup.server.module
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
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
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteReadChannel
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.sql.DriverManager
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.ktor.client.rpcConfig
import kotlinx.rpc.krpc.serialization.json.json as krpcJson
import kotlinx.rpc.withService

/**
 * Cross-module E2E: the client's [ImportRepositoryImpl] drives the real `:server`
 * import pipeline in-process over live HTTP (multipart upload) and kotlinx.rpc
 * WebSocket transports.
 *
 * This is the regression guard for the 404 bug: the old client POSTed to
 * `/admin/abs/upload` (wrong path). The repository's new [ImportRepositoryImpl.upload]
 * uses the shared [ImportRoutePaths.ABS_UPLOAD] constant which resolves to the
 * correct `/api/v1/admin/imports/abs/upload` route.
 *
 * Harness: the full server [module] boots via [testApplication] with an isolated
 * in-memory SQLite config (same pattern as
 * [com.calypsan.listenup.client.di.e2e.DiWiredClientFixture]). A [TestImportRpcFactory]
 * opens the [ImportService] proxy against `ws://localhost/api/rpc/authed`, and
 * [ImportRepositoryImpl]'s secondary constructor accepts an [uploadFn] lambda that
 * calls the test HTTP client — so the real upload code path is exercised without
 * requiring a full [com.calypsan.listenup.client.data.remote.ApiClientFactory].
 *
 * The upload fixture is a minimal `.audiobookshelf` zip: a valid SQLite file with all
 * required ABS tables but no rows, so analyze/confirm/apply all succeed with empty
 * results. This proves the full flow is reachable end-to-end.
 */
class ImportRpcE2ETest :
    FunSpec({

        test("upload → analyze → confirmMapping → apply returns typed Success end to end") {
            val homeDir = Files.createTempDirectory("import-rpc-e2e-")
            try {
                testApplication {
                    environment {
                        val tmpDb =
                            Files
                                .createTempFile("import-rpc-e2e-", ".db")
                                .toFile()
                                .apply { deleteOnExit() }
                        // scanner.libraryPath bootstraps a library row so ImportAnalyzer's
                        // LibraryRegistry.currentLibrary() succeeds (it requires at least one
                        // library to exist). The path doesn't need to hold real audio files for
                        // this test — analyze on an empty ABS backup reads zero items.
                        val libDir = Files.createTempDirectory("import-rpc-e2e-lib-")
                        config =
                            MapApplicationConfig(
                                "database.jdbcUrl" to "jdbc:sqlite:${tmpDb.absolutePath}",
                                "auth.refreshPepper" to "x".repeat(32),
                                "jwt.secret" to "x".repeat(32),
                                "jwt.issuer" to "listenup",
                                "jwt.audience" to "listenup-client",
                                "registration.policy" to "OPEN",
                                "mdns.enabled" to "false",
                                "listenup.home" to homeDir.toString(),
                                "scanner.libraryPath" to libDir.toString(),
                            )
                    }
                    application { module() }

                    val restClient =
                        createClient {
                            install(ContentNegotiation) { json(contractJson) }
                        }

                    // Mint a ROOT account so the import admin gate passes.
                    val accessToken = restClient.setupRoot()

                    val rpcClient = createClient { installKrpc() }
                    val rpcFactory = TestImportRpcFactory(rpcClient, accessToken)

                    // Construct the real ImportRepositoryImpl via its secondary constructor —
                    // the uploadFn lambda uses the test HTTP client (testApplication's virtual
                    // transport) while the RPC methods use the real rpcFactory proxy. Both
                    // paths hit the same in-process server module.
                    val repository =
                        ImportRepositoryImpl(
                            rpcFactory = rpcFactory,
                            uploadFn = { fileSource ->
                                suspendRunCatching {
                                    restClient
                                        .post(ImportRoutePaths.ABS_UPLOAD) {
                                            bearerAuth(accessToken)
                                            setBody(
                                                MultiPartFormDataContent(
                                                    formData {
                                                        append(
                                                            "file",
                                                            ChannelProvider(fileSource.size) { fileSource.openChannel() },
                                                            Headers.build {
                                                                append(
                                                                    HttpHeaders.ContentType,
                                                                    "application/zip",
                                                                )
                                                                append(
                                                                    HttpHeaders.ContentDisposition,
                                                                    "filename=\"${fileSource.filename}\"",
                                                                )
                                                            },
                                                        )
                                                    },
                                                ),
                                            )
                                        }.body<ImportSummary>()
                                }
                            },
                        )

                    runTest {
                        // 1. upload — must return a non-blank importId (proves the route is reachable
                        //    and the correct path constant is used — not the old 404 path).
                        val summary =
                            repository
                                .upload(buildMinimalAbsZipSource())
                                .shouldBeInstanceOf<AppResult.Success<ImportSummary>>()
                                .data
                        summary.id.value.shouldNotBeBlank()

                        val importId: ImportId = summary.id

                        // 2. analyze — proves the RPC route is reachable post-upload.
                        repository
                            .analyze(importId)
                            .shouldBeInstanceOf<AppResult.Success<ImportAnalysis>>()

                        // 3. confirmMapping — empty maps are valid for a zero-row ABS backup.
                        repository
                            .confirmMapping(importId, emptyMap(), emptyMap())
                            .shouldBeInstanceOf<AppResult.Success<Unit>>()

                        // 4. apply — proves the full lifecycle completes end-to-end.
                        val applyResult =
                            repository
                                .apply(importId)
                                .shouldBeInstanceOf<AppResult.Success<ImportResult>>()
                                .data
                        applyResult.importedCount shouldBe 0
                        applyResult.skippedCount shouldBe 0
                    }
                }
            } finally {
                homeDir.toFile().deleteRecursively()
            }
        }
    })

// ── test doubles ─────────────────────────────────────────────────────────────

/**
 * Test-only [ImportRpcFactory] that opens an [ImportService] proxy against
 * `ws://localhost/api/rpc/authed` on the in-process [testApplication].
 *
 * Mirrors [TestBackupRpcFactory] — the canonical test-double pattern for admin
 * RPC factories in this package. The [accessToken] is a real JWT minted by the
 * server's `/api/v1/auth/setup` route so the bearer-gated RPC surface authenticates.
 */
private class TestImportRpcFactory(
    private val rpcHttpClient: HttpClient,
    private val accessToken: String,
) : ImportRpcFactory {
    private val mutex = Mutex()
    private var cachedService: ImportService? = null

    override suspend fun get(): ImportService =
        mutex.withLock {
            cachedService ?: connect().also { cachedService = it }
        }

    override suspend fun invalidate() {
        mutex.withLock { cachedService = null }
    }

    private suspend fun connect(): ImportService =
        rpcHttpClient
            .rpc("ws://localhost/api/rpc/authed") {
                rpcConfig { serialization { krpcJson(contractJson) } }
                bearerAuth(accessToken)
            }.withService<ImportService>()
}

// ── fixture helpers ───────────────────────────────────────────────────────────

/**
 * Registers the first user as ROOT via `/api/v1/auth/setup` and returns the access token.
 * Mirrors the pattern used in [com.calypsan.listenup.server.routes.ImportRoutesTest.setupRoot].
 */
private suspend fun HttpClient.setupRoot(): String {
    val result =
        post("/api/v1/auth/setup") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(email = "root@import.test", password = "password1234", displayName = "Root"))
        }.body<AppResult<AuthSession>>()
    return (result as AppResult.Success<AuthSession>).data.accessToken.value
}

/**
 * Returns a [FileSource] backed by a minimal `.audiobookshelf` zip.
 *
 * The zip contains a single entry (`absdatabase.sqlite`) — a valid SQLite file with
 * all ABS tables created but no rows. An empty ABS backup is accepted by the server
 * upload route, and analyze/confirmMapping/apply all succeed with zero-count results.
 *
 * [com.calypsan.listenup.server.absimport.buildSyntheticAbsBackupZip] lives in
 * `:server`'s test source set (not reachable from `:sharedLogic:jvmTest`), so this
 * function uses only standard JDK classes to produce an equivalent minimal fixture.
 */
private fun buildMinimalAbsZipSource(): FileSource {
    val tmpDir = Files.createTempDirectory("abs-minimal-zip-")
    val dbPath = tmpDir.resolve("absdatabase.sqlite")

    DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { conn ->
        conn.createStatement().use { st ->
            // Create the minimal table set that AbsBackupReader expects. Table and column
            // names match AbsSchema constants in `:server` exactly — drift here causes a
            // compile-time-invisible test failure, which is acceptable given the coupling
            // is deliberate (the server's reader and this fixture are tightly coupled).
            // Table and column names match AbsSchema constants exactly so AbsBackupReader's
            // SELECT queries succeed even on an empty database (SQLite rejects unknown columns).
            st.executeUpdate(
                "CREATE TABLE users (id TEXT PRIMARY KEY, username TEXT, email TEXT, type TEXT)",
            )
            st.executeUpdate(
                "CREATE TABLE libraryItems (" +
                    "id TEXT PRIMARY KEY, libraryId TEXT, mediaId TEXT, " +
                    "mediaType TEXT, path TEXT, relPath TEXT)",
            )
            st.executeUpdate(
                "CREATE TABLE books (id TEXT PRIMARY KEY, title TEXT, subtitle TEXT, asin TEXT, isbn TEXT)",
            )
            st.executeUpdate("CREATE TABLE bookAuthors (bookId TEXT, authorId TEXT)")
            st.executeUpdate("CREATE TABLE authors (id TEXT PRIMARY KEY, name TEXT)")
            st.executeUpdate(
                "CREATE TABLE mediaProgresses (" +
                    "userId TEXT, libraryItemId TEXT, mediaItemId TEXT, " +
                    "mediaItemType TEXT, currentTime REAL, duration REAL, " +
                    "isFinished INTEGER, updatedAt TEXT)",
            )
            // Column names match AbsSchema: SESSION_STARTED_AT="createdAt", SESSION_DEVICE="mediaPlayer",
            // SESSION_START_TIME="startTime" — not the legacy field names the client used to send.
            st.executeUpdate(
                "CREATE TABLE playbackSessions (" +
                    "id TEXT PRIMARY KEY, userId TEXT, libraryItemId TEXT, " +
                    "mediaItemId TEXT, mediaItemType TEXT, " +
                    "startTime REAL, currentTime REAL, " +
                    "timeListening INTEGER, createdAt TEXT, mediaPlayer TEXT)",
            )
        }
    }

    val dbBytes = Files.readAllBytes(dbPath)
    tmpDir.toFile().deleteRecursively()

    val zipBytes =
        ByteArrayOutputStream()
            .also { baos ->
                ZipOutputStream(baos).use { zip ->
                    zip.putNextEntry(ZipEntry("absdatabase.sqlite"))
                    zip.write(dbBytes)
                    zip.closeEntry()
                }
            }.toByteArray()

    return object : FileSource {
        override val filename: String = "library.audiobookshelf"
        override val size: Long = zipBytes.size.toLong()

        override fun openChannel(): ByteReadChannel = ByteReadChannel(zipBytes)
    }
}
