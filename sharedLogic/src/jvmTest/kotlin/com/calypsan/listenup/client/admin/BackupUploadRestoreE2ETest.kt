package com.calypsan.listenup.client.admin

import com.calypsan.listenup.api.BackupService
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.backup.BackupSummary
import com.calypsan.listenup.api.dto.backup.RestoreResult
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.remote.ApiClientFactory
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.data.repository.BackupRepositoryImpl
import com.calypsan.listenup.core.FileSource
import com.calypsan.listenup.server.backup.BackupArchive
import com.calypsan.listenup.server.backup.BackupPaths
import com.calypsan.listenup.server.db.DatabaseConfig
import com.calypsan.listenup.server.db.DatabaseFactory
import com.calypsan.listenup.server.module
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteReadChannel
import java.nio.file.Files
import kotlinx.io.files.Path as IoPath
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.ktor.client.rpcConfig
import kotlinx.rpc.krpc.serialization.json.json as krpcJson
import kotlinx.rpc.withService

/**
 * Cross-module E2E: the client's [BackupRepositoryImpl.uploadBackup] streams a `.listenup.zip`
 * to the real `:server` upload route in-process, the server stages it, and
 * [BackupRepositoryImpl.restoreBackup] restores it through the real RPC route.
 *
 * This is the regression guard for the new "restore from file" feature: a `.listenup.zip` that
 * exists only as bytes (e.g. downloaded from another server) can be uploaded, appears in
 * [BackupRepositoryImpl.listBackups], and can be restored via [BackupRepositoryImpl.restoreBackup].
 *
 * The test uses a *same-server* create → upload → restore round-trip:
 *  - A "foreign" archive is produced by creating a backup in a separate temp dir (a second
 *    [BackupArchive] instance disconnected from the server's DB and paths). Its zip bytes are
 *    read and wrapped in a [FileSource] — structurally identical to bytes that arrive from
 *    another server.
 *  - [BackupRepositoryImpl.uploadBackup] streams those bytes to the in-process server via the
 *    real `POST /api/v1/admin/backups/upload` route.
 *  - [BackupRepositoryImpl.listBackups] proves the staged archive is now present.
 *  - [BackupRepositoryImpl.restoreBackup] proves the restore call reaches the service and
 *    returns a domain-typed [RestoreResult] (not a transport 404).
 *
 * Harness: the full server [module] boots via [testApplication] with an isolated in-memory
 * SQLite config (same pattern as [ImportRpcE2ETest]). [backupServiceProxy] opens the
 * [BackupService] RPC proxy against the in-process server, wrapped in [RpcChannel.forTest].
 * A Mokkery mock of
 * [ApiClientFactory] is configured so [BackupRepositoryImpl.uploadBackup] runs the REAL
 * production `submitFormWithBinaryData` code path against the in-process server.
 *
 * Anti-flake: the upload call runs directly in the [testApplication] suspend scope — NOT
 * inside a nested `runTest`. Nesting `runTest` inside `testApplication` races the
 * virtual-time scheduler against the real-dispatcher HTTP transport; under load it tears
 * down the streaming upload body mid-write. See [ImportRpcE2ETest] for prior art.
 */
class BackupUploadRestoreE2ETest :
    FunSpec({

        test(".listenup.zip uploads, is staged, and is restorable end-to-end") {
            val serverHomeDir = Files.createTempDirectory("backup-upload-restore-e2e-")
            try {
                testApplication {
                    environment {
                        val tmpDb =
                            Files
                                .createTempFile("backup-upload-restore-e2e-", ".db")
                                .toFile()
                                .apply { deleteOnExit() }
                        val libDir = Files.createTempDirectory("backup-upload-restore-e2e-lib-")
                        config =
                            MapApplicationConfig(
                                "database.jdbcUrl" to "jdbc:sqlite:${tmpDb.absolutePath}",
                                "auth.refreshPepper" to "x".repeat(32),
                                "jwt.secret" to "x".repeat(32),
                                "jwt.issuer" to "listenup",
                                "jwt.audience" to "listenup-client",
                                "registration.policy" to "OPEN",
                                "mdns.enabled" to "false",
                                "listenup.home" to serverHomeDir.toString(),
                                "scanner.libraryPath" to libDir.toString(),
                            )
                    }
                    application { module() }

                    val restClient = createClient { install(ContentNegotiation) { json(contractJson) } }
                    val accessToken = restClient.setupRootForBackup()

                    val authedRestClient =
                        createClient {
                            install(ContentNegotiation) { json(contractJson) }
                            install(Auth) {
                                bearer {
                                    loadTokens { BearerTokens(accessToken, "") }
                                    sendWithoutRequest { true }
                                }
                            }
                        }

                    val rpcClient = createClient { installKrpc() }

                    val clientFactory =
                        mock<ApiClientFactory> {
                            everySuspend { getClient() } returns authedRestClient
                        }
                    val repository =
                        BackupRepositoryImpl(
                            channel = RpcChannel.forTest(rpcClient.backupServiceProxy(accessToken)),
                            clientFactory = clientFactory,
                        )

                    // Build a "foreign" .listenup.zip — a valid archive produced by a separate
                    // BackupArchive instance in its own temp dir, disconnected from the server's
                    // DB and paths. This mirrors bytes arriving from another machine's server.
                    val zipBytes = buildForeignBackupZipBytes()

                    val fileSource =
                        object : FileSource {
                            override val filename: String = "foreign-server.listenup.zip"
                            override val size: Long = zipBytes.size.toLong()

                            override fun openChannel(): ByteReadChannel = ByteReadChannel(zipBytes)
                        }

                    // 1. uploadBackup — drives the real REST route.
                    //    Do NOT wrap in runTest (see class KDoc for the anti-flake rationale).
                    val uploadedSummary =
                        repository
                            .uploadBackup(fileSource)
                            .shouldBeInstanceOf<AppResult.Success<BackupSummary>>()
                            .data

                    uploadedSummary.id.value.shouldNotBeBlank()
                    uploadedSummary.includesImages shouldBe false

                    // 2. listBackups — proves the server staged the uploaded archive.
                    val listedIds =
                        repository
                            .listBackups()
                            .shouldBeInstanceOf<AppResult.Success<List<BackupSummary>>>()
                            .data
                            .map { it.id }

                    listedIds shouldContain uploadedSummary.id

                    // 3. restoreBackup — proves the RPC route is reachable for the uploaded id
                    //    and returns a domain-typed RestoreResult (never a transport 404).
                    val restoreResult =
                        repository
                            .restoreBackup(uploadedSummary.id)
                            .shouldBeInstanceOf<AppResult.Success<RestoreResult>>()
                            .data

                    restoreResult.restoredFrom shouldBe uploadedSummary.id
                    restoreResult.includedImages shouldBe false
                }
            } finally {
                serverHomeDir.toFile().deleteRecursively()
            }
        }
    })

// ── test doubles ─────────────────────────────────────────────────────────────

/**
 * Opens a [BackupService] proxy against `ws://localhost/api/rpc/authed` on the in-process
 * [testApplication], wrapped by [RpcChannel.forTest]. Here [accessToken] is a real JWT minted
 * by the server's `/api/v1/auth/setup` route (the full [module] is booted), so the bearer-gated
 * RPC surface authenticates correctly.
 */
private suspend fun HttpClient.backupServiceProxy(accessToken: String): BackupService =
    rpc("ws://localhost/api/rpc/authed") {
        rpcConfig { serialization { krpcJson(contractJson) } }
        bearerAuth(accessToken)
    }.withService<BackupService>()

// ── fixture helpers ───────────────────────────────────────────────────────────

/**
 * Registers the first user as ROOT via `/api/v1/auth/setup` and returns the access token.
 * Mirrors the [setupRoot] pattern used in [ImportRpcE2ETest].
 */
private suspend fun HttpClient.setupRootForBackup(): String {
    val result =
        post("/api/v1/auth/setup") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(email = "root@backup.test", password = "password1234", displayName = "Root"))
        }.body<AppResult<AuthSession>>()
    return (result as AppResult.Success<AuthSession>).data.accessToken.value
}

/**
 * Produces a valid `.listenup.zip` as a [ByteArray] by creating an archive in a temporary
 * home directory disconnected from the in-process server's paths.
 *
 * The archive is structurally identical to one that would arrive from another server:
 * a real SQLite snapshot + `manifest.json` with checksums. The [BackupArchive.validate]
 * call in the upload route's [handleUpload] will accept it because the archive is genuine
 * (not hand-crafted bytes).
 *
 * The temp directory is deleted before returning; only the zip bytes survive in memory.
 */
private suspend fun buildForeignBackupZipBytes(): ByteArray {
    val foreignHomeDir = Files.createTempDirectory("backup-upload-foreign-")
    try {
        val dbFile = foreignHomeDir.resolve("listenup.db")
        val handle =
            DatabaseFactory.init(
                DatabaseConfig(jdbcUrl = "jdbc:sqlite:$dbFile"),
            )
        try {
            handle.sqlDriver.execute(null, "CREATE TABLE IF NOT EXISTS seed(v TEXT)", 0)
            handle.sqlDriver.execute(null, "INSERT INTO seed(v) VALUES ('foreign')", 0)

            val paths = BackupPaths(IoPath(foreignHomeDir.toString()))
            val archive =
                BackupArchive(
                    paths = paths,
                    dbHandle = handle,
                    serverId = { "foreign-srv" },
                    appVersion = "0.0.0-test",
                    schemaVersion = { "1" },
                    counts = { 0 to 0 },
                )

            val archivePath =
                archive.create(
                    id = "backup-foreign-upload-test",
                    includeImages = false,
                    onEvent = {},
                )

            return Files.readAllBytes(
                java.nio.file.Path
                    .of(archivePath.toString()),
            )
        } finally {
            handle.close()
        }
    } finally {
        foreignHomeDir.toFile().deleteRecursively()
    }
}
