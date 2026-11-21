package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.SyncStreamService
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.backup.BackupEvent
import com.calypsan.listenup.api.dto.backup.RestoreResult
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.data.sync.domains.tagsDomain
import com.calypsan.listenup.client.data.sync.domains.toHandler
import com.calypsan.listenup.client.data.sync.testing.testAuth
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.core.BackupId
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.backup.BackupArchive
import com.calypsan.listenup.server.backup.BackupPaths
import com.calypsan.listenup.server.backup.MaintenanceState
import com.calypsan.listenup.server.backup.RestoreOrchestrator
import com.calypsan.listenup.server.db.DatabaseConfig
import com.calypsan.listenup.server.db.DatabaseFactory
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase as ServerSqlDatabase
import com.calypsan.listenup.server.plugins.JWT_PROVIDER
import com.calypsan.listenup.server.plugins.userPrincipalOrNull
import com.calypsan.listenup.server.rpcguard.guard
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.sync.TagRepository
import com.calypsan.listenup.server.sync.createSyncStreamService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.io.files.Path as IoPath
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.ktor.client.rpcConfig
import kotlinx.rpc.krpc.ktor.server.Krpc as ServerKrpc
import kotlinx.rpc.krpc.ktor.server.rpc as serverRpc
import kotlinx.rpc.krpc.serialization.json.json as krpcJson
import kotlinx.rpc.withService

/**
 * A server restored to an older backup and a client that synced past that backup converge, end to end:
 * the real `:server` restore swaps the database in-process, and the real client catch-up and digest
 * reconcile run against it over the live RPC transport.
 *
 * Before the restore the client holds a tag renamed after the backup (at a revision above every restored
 * row) and a tag created after it. After one reconcile it must show exactly the restored library — the
 * rename undone, the late tag gone — and its digest must then match the server's, so the next connect
 * re-pulls nothing.
 */
class RestoreConvergenceE2ETest :
    FunSpec({

        test("after a server restore, one reconcile brings the client to exactly the restored library") {
            val homeDir = Files.createTempDirectory("restore-convergence-e2e-")
            val handle = DatabaseFactory.init(DatabaseConfig(jdbcUrl = "jdbc:sqlite:${homeDir.resolve("listenup.db")}"))
            val bus = ChangeBus()
            val serverRegistry = SyncRegistry()
            val serverTags = TagRepository(ServerSqlDatabase(handle.sqlDriver), bus, serverRegistry)
            val paths = BackupPaths(IoPath(homeDir.toString()))
            val archive =
                BackupArchive(
                    paths = paths,
                    dbHandle = handle,
                    serverId = { "srv-test" },
                    appVersion = "0.0.0-test",
                    schemaVersion = { "1" },
                    counts = { 1 to 1 },
                )
            val restorer =
                RestoreOrchestrator(
                    paths = paths,
                    archive = archive,
                    dbHandle = handle,
                    maintenance = MaintenanceState(),
                    eventBus = MutableSharedFlow<BackupEvent>(extraBufferCapacity = 64),
                    changeBus = bus,
                )
            val clientDb = createInMemoryTestDatabase()

            try {
                testApplication {
                    application {
                        install(Authentication) { testAuth(defaultUserId = "u1") }
                        install(ServerKrpc)
                        routing {
                            authenticate(JWT_PROVIDER) {
                                serverRpc("/api/rpc/authed") {
                                    rpcConfig { serialization { krpcJson(contractJson) } }
                                    registerService<SyncStreamService> {
                                        val principal = call.userPrincipalOrNull() ?: error("auth wall regression")
                                        guard(
                                            createSyncStreamService(
                                                bus,
                                                serverRegistry,
                                                { error("tags are not book-gated") },
                                                PrincipalProvider { principal },
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Driven in the testApplication scope, not runTest: the channel bounds each call with
                    // withTimeout, which runTest's virtual clock would trip while real socket I/O is in flight.
                    val channel =
                        RpcChannel.forTest(
                            createClient { installKrpc() }
                                .rpc("ws://localhost/api/rpc/authed") {
                                    rpcConfig { serialization { krpcJson(contractJson) } }
                                }.withService<SyncStreamService>(),
                        )
                    val clientRegistry = ClientSyncDomainRegistry()
                    val tagHandler = tagsDomain(clientDb).toHandler(RoomTransactionRunner(clientDb), clientRegistry)
                    val store = SyncCursorStore(clientDb.syncCursorDao())
                    val catchUp = SyncCatchUpClient(channel, store, RoomTransactionRunner(clientDb))
                    val digestClient = DomainDigestClient(channel)
                    val reconciler = SyncReconciler(clientRegistry, store, digestClient, catchUp)

                    suspend fun clientTags() = clientDb.tagDao().digestRows(Long.MAX_VALUE).map { it.id }

                    suspend fun clientName(id: String) = clientDb.tagDao().getById(id)?.name

                    serverTags.upsert(tag("tag-a", "Fantasy"))
                    serverTags.upsert(tag("tag-b", "Horror"))
                    archive.create("before-edits", includeImages = false, onEvent = {})
                    serverTags.updateName("tag-a", "Epic Fantasy")
                    serverTags.upsert(tag("tag-c", "Mystery"))
                    catchUp.catchUp(tagHandler).shouldBeInstanceOf<AppResult.Success<Unit>>()
                    clientName("tag-a") shouldBe "Epic Fantasy"
                    clientTags() shouldContainExactlyInAnyOrder listOf("tag-a", "tag-b", "tag-c")

                    restorer.restore(BackupId("before-edits")).shouldBeInstanceOf<AppResult.Success<RestoreResult>>()
                    reconciler.reconcileAll()

                    clientName("tag-a") shouldBe "Fantasy"
                    clientTags() shouldContainExactlyInAnyOrder listOf("tag-a", "tag-b")
                    val cursor = checkNotNull(store.highestCursor())
                    val serverDigest =
                        digestClient.fetch("tags", cursor).shouldBeInstanceOf<AppResult.Success<*>>().data
                    serverDigest shouldBe DigestComputer.compute(cursor, tagHandler.localDigestRows(cursor).orEmpty())
                }
            } finally {
                clientDb.close()
                handle.close()
                homeDir.toFile().deleteRecursively()
            }
        }
    })

private fun tag(
    id: String,
    name: String,
) = Tag(id = id, name = name, slug = id, revision = 0, updatedAt = 0)
