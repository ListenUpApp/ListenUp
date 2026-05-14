package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.sync.Tombstoned
import com.calypsan.listenup.server.db.DatabaseConfig
import com.calypsan.listenup.server.db.DatabaseFactory
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.sse
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE as ServerSSE
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation

/**
 * Pins the spec §189 invariant: when the SSE firehose's emit pipeline throws,
 * the server emits a `SyncControl.StreamError(InternalError(correlationId, cause))`
 * frame where `cause` is the THROWABLE'S CLASS NAME only — never a full
 * stacktrace, package path, or `Caused by:` chain.
 *
 * Stacktraces never cross the wire. Operators trace the correlation id to a
 * server-side log entry that holds the full detail; the client only sees the
 * sanitized class-name string. This test guards the contract; a regression that
 * leaks `stackTraceToString()` or wraps the throwable's `toString()` into the
 * frame would fail every `shouldNotContain` assertion below.
 *
 * The fixture installs a [ThrowingRepository] whose [ThrowingPayload.serializer]
 * deliberately throws on encode. The SSE consumer's `Flow.collect { ... }`
 * lambda calls `busEvent.repo.encodeSyncEventAsJson(busEvent.event)`, which
 * delegates to that serializer — so a single `upsert` triggers the catch
 * branch in [com.calypsan.listenup.server.sync.syncRoutes].
 */
class SyncRoutesStreamErrorTest :
    FunSpec({

        test("StreamError frame carries class-name cause + correlationId; never a stacktrace") {
            withThrowingTestApplication {
                client.sse("/api/v1/sync/events") {
                    coroutineScope {
                        val deferred = async { incoming.first { it.event == "control" } }
                        // Triggers bus.publish → SSE collect → encodeSyncEventAsJson → throw
                        repo.upsert(
                            ThrowingPayload(id = "boom", revision = 0, updatedAt = 0),
                        )
                        val event = deferred.await()

                        event.event shouldBe "control"
                        val data = event.data!!

                        // Wire shape: SyncControl.StreamError wrapping AppError.InternalError,
                        // with a non-empty correlationId and a class-name-only cause.
                        data shouldContain """"type":"SyncControl.StreamError""""
                        data shouldContain """"type":"AppError.InternalError""""
                        data shouldContain """"correlationId":"""
                        // The cause is the simpleName of the thrown class, not the FQN.
                        data shouldContain """"cause":"BrokenSerializerException""""

                        // Invariant: stacktraces never cross the wire. None of these tokens
                        // may appear in any field of the frame.
                        data shouldNotContain "at com.calypsan."
                        data shouldNotContain "Caused by:"
                        data shouldNotContain ".java:"
                        data shouldNotContain ".kt:"
                    }
                }
            }
        }
    })

/**
 * Mirrors `withTestApplication` but wires a [ThrowingRepository] in place of
 * `TagRepository`. Kept private to this test — the only consumer of the
 * deliberately-broken serializer.
 */
private fun withThrowingTestApplication(block: suspend ThrowingTestScope.() -> Unit) {
    testApplication {
        val tmp =
            Files.createTempFile("listenup-streamerror-test-", ".db").toFile().apply { deleteOnExit() }
        val db =
            DatabaseFactory.init(DatabaseConfig(jdbcUrl = "jdbc:sqlite:${tmp.absolutePath}"))
        val bus = ChangeBus()
        val registry = SyncRegistry()
        val throwingRepo = ThrowingRepository(db, bus, registry)

        // Materialise the table once before the SSE handler subscribes; otherwise
        // the first upsert hits a missing-table error before reaching the bus.
        suspendTransaction(db) { SchemaUtils.create(ThrowingTable) }

        application {
            install(ServerContentNegotiation) { json(contractJson) }
            install(ServerSSE)
            install(Koin) {
                modules(
                    module {
                        single { db }
                        single { bus }
                        single { registry }
                        single(createdAtStart = true) { throwingRepo }
                    },
                )
            }
            routing { syncRoutes() }
        }

        val jsonClient =
            createClient {
                install(ContentNegotiation) { json(contractJson) }
                install(SSE)
            }

        ThrowingTestScope(client = jsonClient, repo = throwingRepo).block()
    }
}

private data class ThrowingTestScope(
    val client: io.ktor.client.HttpClient,
    val repo: ThrowingRepository,
)

// ---------- Fixture types ----------

/**
 * Distinct simpleName ("BrokenSerializerException") so the wire-cause assertion
 * is unambiguous — no other class in the stacktrace shares this name.
 */
private class BrokenSerializerException : RuntimeException("deliberately-broken serializer for stream-error test")

@Serializable(with = ThrowingPayloadSerializer::class)
@SerialName("ThrowingPayload")
private data class ThrowingPayload(
    val id: String,
    val revision: Long,
    val updatedAt: Long,
    override val deletedAt: Long? = null,
) : Tombstoned

/**
 * Throws on encode; decode is unused by the SSE path but implemented for
 * completeness so `KSerializer` is honest.
 */
private object ThrowingPayloadSerializer : KSerializer<ThrowingPayload> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ThrowingPayload", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: ThrowingPayload,
    ) {
        boom()
    }

    override fun deserialize(decoder: Decoder): ThrowingPayload = boom()

    private fun boom(): Nothing = throw BrokenSerializerException()
}

private object ThrowingTable : SyncableTable("throwing_streamerror_test") {
    val id = text("id")
    override val primaryKey = PrimaryKey(id)
}

private class ThrowingRepository(
    db: Database,
    bus: ChangeBus,
    registry: SyncRegistry,
) : SyncableRepository<ThrowingPayload, String>(db, ThrowingTable, bus, registry, "throwing") {
    override val elementSerializer: KSerializer<ThrowingPayload> = ThrowingPayloadSerializer

    override fun ResultRow.toDto(): ThrowingPayload =
        ThrowingPayload(
            id = this[ThrowingTable.id],
            revision = this[ThrowingTable.revision],
            updatedAt = this[ThrowingTable.updatedAt],
            deletedAt = this[ThrowingTable.deletedAt],
        )

    override fun ThrowingPayload.writeTo(stmt: UpdateBuilder<*>) {
        stmt[ThrowingTable.id] = id
    }

    override val ThrowingPayload.id: String get() = id

    override fun ThrowingPayload.revisionOf(): Long = revision
}
