package com.calypsan.listenup.server.backup

import com.calypsan.listenup.api.BackupService
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.backup.BackupSummary
import com.calypsan.listenup.api.dto.backup.RestoreResult
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.SyncControl
import com.calypsan.listenup.api.sync.SyncFrame
import com.calypsan.listenup.server.api.BookAccessPolicy
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.testing.rootPrincipal
import com.calypsan.listenup.server.testing.rpcFirehose
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeout
import org.koin.ktor.ext.inject
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.ktor.client.rpcConfig
import kotlinx.rpc.krpc.serialization.json.json as krpcJson
import kotlinx.rpc.withService

/**
 * Cross-device E2E for the restore re-baseline broadcast (Plan 089).
 *
 * WHY this exists: a restore swaps the server's entire SQLite DB in-process. The admin device
 * that triggered the restore resyncs explicitly, but *every other connected device* used to get
 * no signal at all — the orchestrator emitted only backup-progress events (admin-only) and never
 * touched the cross-domain sync firehose. Those devices hold cursors AHEAD of the rewound
 * revision counter, so neither forward catch-up nor `CursorStale` fires, and they silently
 * diverge until a lucky reconnect. The fix broadcasts `SyncControl.LibraryDataChanged` on the
 * firehose after a successful restore, so a second device re-derives live.
 *
 * This test pins the SERVER half end-to-end: a second device's open RPC firehose subscription
 * (`SyncStreamService.observeEvents`, observed via the `rpcFirehose` test helper over the app's
 * real `ChangeBus`) receives the `LibraryDataChanged` control frame when an admin-triggered
 * restore completes against the full server module. The client-side reaction to that frame
 * (`lifecycleReconcile(force = true)` → digest reconcile) is already pinned by
 * `SyncEventDispatcherTest` and the digest-convergence E2Es — that half is out of scope here.
 *
 * Timing note: the restore drains in-flight requests (`MaintenanceState.drain()`) before
 * swapping the DB; the 30 s await budgets for that stall on a loaded CI runner.
 */
class RestoreBroadcastE2ETest :
    FunSpec({

        test("a connected firehose subscriber receives LibraryDataChanged when a restore completes") {
            val serverHomeDir = Files.createTempDirectory("restore-broadcast-e2e-")
            try {
                testApplication {
                    environment {
                        val tmpDb =
                            Files
                                .createTempFile("restore-broadcast-e2e-", ".db")
                                .toFile()
                                .apply { deleteOnExit() }
                        val libDir = Files.createTempDirectory("restore-broadcast-e2e-lib-")
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
                    val rootSession = restClient.setupRoot()
                    val accessToken = rootSession.accessToken.value

                    // Admin device: open a BackupService RPC proxy and create a backup to restore.
                    val rpcClient = createClient { installKrpc() }
                    val backupService =
                        rpcClient
                            .rpc("ws://localhost/api/rpc/authed") {
                                rpcConfig { serialization { krpcJson(contractJson) } }
                                bearerAuth(accessToken)
                            }.withService<BackupService>()

                    val summary =
                        backupService
                            .createBackup(includeImages = false)
                            .shouldBeInstanceOf<AppResult.Success<BackupSummary>>()
                            .data

                    // Device B: subscribe to the firehose BEFORE the restore, then trigger the
                    // restore while the collector runs concurrently. LibraryDataChanged rides the
                    // bus's replay-free control channel, so the subscription must be attached before
                    // the broadcast fires.
                    val bus by application.inject<ChangeBus>()
                    val policy by application.inject<BookAccessPolicy>()
                    coroutineScope {
                        val controlFrame =
                            async {
                                rpcFirehose(bus, rootPrincipal(rootSession.user.id.value), bookAccessPolicy = { policy })
                                    .filter { it.domain == SyncFrame.CONTROL }
                                    .map { contractJson.decodeFromString(SyncControl.serializer(), it.json) }
                                    .first { it is SyncControl.LibraryDataChanged }
                            }

                        // Deterministic attach barrier: the control channel has replay = 0, so a
                        // broadcast emitted before subscription would be missed. Await the actual
                        // subscriber registration instead of sleeping.
                        bus.controlSubscriptionCount.first { it > 0 }

                        backupService
                            .restoreBackup(summary.id)
                            .shouldBeInstanceOf<AppResult.Success<RestoreResult>>()

                        // The 30 s budget covers the maintenance drain of in-flight requests
                        // before the swap + broadcast.
                        withTimeout(30_000) { controlFrame.await() } shouldBe SyncControl.LibraryDataChanged
                    }
                }
            } finally {
                serverHomeDir.toFile().deleteRecursively()
            }
        }
    })

/**
 * Registers the first user as ROOT via `/api/v1/auth/setup` and returns the session — the
 * access token drives the RPC calls; the user id scopes the firehose subscriber's principal.
 * Mirrors the `setupRootForBackup` helper in `BackupUploadRestoreE2ETest`.
 */
private suspend fun HttpClient.setupRoot(): AuthSession {
    val result =
        post("/api/v1/auth/setup") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(email = "root@restore-broadcast.test", password = "password1234", displayName = "Root"))
        }.body<AppResult<AuthSession>>()
    return (result as AppResult.Success<AuthSession>).data
}
