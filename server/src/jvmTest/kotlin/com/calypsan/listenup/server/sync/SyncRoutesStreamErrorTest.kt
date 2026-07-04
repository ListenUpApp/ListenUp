package com.calypsan.listenup.server.sync

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.sync.SyncDomainKey
import com.calypsan.listenup.api.sync.Tombstoned
import com.calypsan.listenup.server.db.DatabaseConfig
import com.calypsan.listenup.server.db.DatabaseFactory
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.plugins.JWT_PROVIDER
import com.calypsan.listenup.server.testing.testAuth
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.sse
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
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
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import org.sqlite.SQLiteConfig
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation

/**
 * Pins the invariant: when the SSE firehose's emit pipeline throws,
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
 *
 * [DatabaseFactory.init] migrates the temp-file schema (the SQLDelight driver never
 * calls `Schema.create`); the SQLDelight [SqlDriver] + [ListenUpDatabase] are then
 * opened over the same file, mirroring the `withSqlDatabase` fixture.
 */
private fun withThrowingTestApplication(block: suspend ThrowingTestScope.() -> Unit) {
    testApplication {
        val tmp =
            Files.createTempFile("listenup-streamerror-test-", ".db").toFile().apply { deleteOnExit() }
        val path = tmp.absolutePath
        DatabaseFactory.init(DatabaseConfig(jdbcUrl = "jdbc:sqlite:$path"))
        val driver =
            JdbcSqliteDriver(
                "jdbc:sqlite:$path",
                SQLiteConfig()
                    .apply {
                        enforceForeignKeys(true)
                        busyTimeout = 5000
                        setJournalMode(SQLiteConfig.JournalMode.WAL)
                    }.toProperties(),
            )
        val sqlDb = ListenUpDatabase(driver)
        val bus = ChangeBus()
        val registry = SyncRegistry()
        val throwingRepo = ThrowingRepository(sqlDb, driver, bus, registry)

        // Materialise the table once before the SSE handler subscribes; otherwise
        // the first upsert hits a missing-table error before reaching the bus.
        throwingRepo.createSchema()

        application {
            install(ServerContentNegotiation) { json(contractJson) }
            install(ServerSSE)
            install(Authentication) { testAuth() }
            install(Koin) {
                modules(
                    module {
                        single { sqlDb }
                        single { bus }
                        single { registry }
                        single(createdAtStart = true) { throwingRepo }
                    },
                )
            }
            routing { authenticate(JWT_PROVIDER) { syncRoutes() } }
        }

        val jsonClient =
            createClient {
                install(ContentNegotiation) { json(contractJson) }
                install(SSE)
            }

        try {
            ThrowingTestScope(client = jsonClient, repo = throwingRepo).block()
        } finally {
            driver.close()
        }
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

/**
 * SQLDelight twin of the former Exposed `ThrowingTable` fixture: a global syncable
 * aggregate on [SqlSyncableRepository] whose serializer throws on encode. The root
 * table `throwing_streamerror_test` is test-only (no Flyway migration), so
 * [createSchema] creates it via raw DDL and all reads/writes run raw SQL over the
 * [SqlDriver].
 */
private class ThrowingRepository(
    db: ListenUpDatabase,
    override val driver: SqlDriver,
    bus: ChangeBus,
    registry: SyncRegistry,
) : SqlSyncableRepository<ThrowingPayload, String>(
        db = db,
        bus = bus,
        registry = registry,
        key = SyncDomainKey("throwing", ThrowingPayloadSerializer),
    ) {
    override val ThrowingPayload.id: String get() = id

    override fun ThrowingPayload.revisionOf(): Long = revision

    /** Materialises the test-only `throwing_streamerror_test` table (revision indexed). */
    fun createSchema() {
        driver.execute(
            identifier = null,
            sql =
                """
                CREATE TABLE IF NOT EXISTS throwing_streamerror_test (
                    id TEXT NOT NULL PRIMARY KEY,
                    revision INTEGER NOT NULL,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    deleted_at INTEGER,
                    client_op_id TEXT
                )
                """.trimIndent(),
            parameters = 0,
        )
        driver.execute(
            identifier = null,
            sql = "CREATE INDEX IF NOT EXISTS ix_throwing_streamerror_revision ON throwing_streamerror_test(revision)",
            parameters = 0,
        )
    }

    override val substrate: SyncableSubstrateQueries =
        object : SyncableSubstrateQueries {
            override fun existsById(id: String): Boolean =
                driver
                    .executeQuery(
                        identifier = null,
                        sql = "SELECT 1 FROM throwing_streamerror_test WHERE id = ? LIMIT 1",
                        mapper = { cursor -> QueryResult.Value(cursor.next().value) },
                        parameters = 1,
                        binders = { bindString(0, id) },
                    ).value

            override fun softDeleteById(
                id: String,
                revision: Long,
                updatedAt: Long,
                deletedAt: Long,
                clientOpId: String?,
            ): Long =
                driver
                    .execute(
                        identifier = null,
                        sql =
                            "UPDATE throwing_streamerror_test " +
                                "SET revision = ?, updated_at = ?, deleted_at = ?, client_op_id = ? WHERE id = ?",
                        parameters = 5,
                        binders = {
                            bindLong(0, revision)
                            bindLong(1, updatedAt)
                            bindLong(2, deletedAt)
                            bindString(3, clientOpId)
                            bindString(4, id)
                        },
                    ).value

            override fun selectIdsAboveRevision(
                cursor: Long,
                limit: Long,
            ): List<IdRev> =
                driver.queryIdRev(
                    "SELECT id, revision FROM throwing_streamerror_test WHERE revision > ? ORDER BY revision ASC LIMIT ?",
                    listOf(cursor, limit),
                )

            override fun selectIdRevAtMost(cursor: Long): List<IdRev> =
                driver.queryIdRev(
                    "SELECT id, revision FROM throwing_streamerror_test WHERE revision <= ?",
                    listOf(cursor),
                )
        }

    override fun readPayload(idStr: String): ThrowingPayload? =
        driver
            .executeQuery(
                identifier = null,
                sql = "SELECT id, revision, updated_at, deleted_at FROM throwing_streamerror_test WHERE id = ?",
                mapper = { cursor ->
                    QueryResult.Value(
                        if (cursor.next().value) {
                            ThrowingPayload(
                                id = cursor.getString(0)!!,
                                revision = cursor.getLong(1)!!,
                                updatedAt = cursor.getLong(2)!!,
                                deletedAt = cursor.getLong(3),
                            )
                        } else {
                            null
                        },
                    )
                },
                parameters = 1,
                binders = { bindString(0, idStr) },
            ).value

    override fun writePayload(
        value: ThrowingPayload,
        rev: Long,
        now: Long,
        clientOpId: String?,
        userId: String?,
        existed: Boolean,
    ) {
        if (existed) {
            driver.execute(
                identifier = null,
                sql =
                    "UPDATE throwing_streamerror_test " +
                        "SET revision = ?, updated_at = ?, deleted_at = NULL, client_op_id = ? WHERE id = ?",
                parameters = 4,
                binders = {
                    bindLong(0, rev)
                    bindLong(1, now)
                    bindString(2, clientOpId)
                    bindString(3, value.id)
                },
            )
        } else {
            driver.execute(
                identifier = null,
                sql =
                    "INSERT INTO throwing_streamerror_test " +
                        "(id, revision, created_at, updated_at, deleted_at, client_op_id) " +
                        "VALUES (?, ?, ?, ?, NULL, ?)",
                parameters = 5,
                binders = {
                    bindString(0, value.id)
                    bindLong(1, rev)
                    bindLong(2, now)
                    bindLong(3, now)
                    bindString(4, clientOpId)
                },
            )
        }
    }
}
