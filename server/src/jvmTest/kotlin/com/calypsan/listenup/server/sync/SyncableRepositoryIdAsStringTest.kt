@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.sync

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.SyncDomainKey
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.api.sync.Tombstoned
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.jvm.JvmInline
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Inline-fixture test that pins the `idAsString` template-hook contract:
 *
 * [SqlSyncableRepository] MUST serialize the domain id to its raw string value
 * for every WHERE clause, UPDATE statement, and `SyncEvent` entity id —
 * never via Kotlin's default `toString()`, which on a `@JvmInline value class`
 * emits `"WrapperName(value=foo)"` and would corrupt every column.
 *
 * The fixture uses [FixtureId] — a `@JvmInline value class` whose default
 * `toString()` is `"FixtureId(value=abc)"`. Three assertions cover the three
 * call sites (`upsert` WHERE, `softDelete` WHERE, emitted event `id`):
 *  - upsert stores `"abc"` (not `"FixtureId(value=abc)"`) in the id column.
 *  - softDelete(FixtureId("abc")) finds the row and sets deletedAt.
 *  - Emitted SyncEvent.Created.id is `"abc"`, not `"FixtureId(value=abc)"`.
 */
class SyncableRepositoryIdAsStringTest :
    FunSpec({

        test("idAsString override is honoured by upsert, softDelete, and event entityId") {
            withSqlDatabase {
                val bus = ChangeBus()
                val repo = FixtureRepository(sql, driver, bus, SyncRegistry())
                repo.createSchema()

                runTest {
                    val deferredCreated = async { bus.subscribe().first() }
                    advanceUntilIdle()

                    val payload =
                        FixturePayload(
                            id = FixtureId("abc"),
                            label = "fixture",
                            revision = 0,
                            updatedAt = 0,
                        )

                    // Assertion 1: upsert stores the raw value "abc" in the id column,
                    // not the wrapped "FixtureId(value=abc)" default-toString form.
                    val upsertResult = repo.upsert(payload, clientOpId = "op-1")
                    upsertResult.shouldBeInstanceOf<AppResult.Success<FixturePayload>>()

                    val storedId = repo.singleStoredId()
                    storedId shouldBe "abc"

                    // Assertion 3: emitted SyncEvent.Created carries the raw id "abc".
                    val busEvent = deferredCreated.await()
                    busEvent.repo.domainName shouldBe "fixtures"
                    val createdEvent = busEvent.event
                    createdEvent.shouldBeInstanceOf<SyncEvent.Created<FixturePayload>>()
                    createdEvent.id shouldBe "abc"

                    // Assertion 2: softDelete finds the row (it only finds it if
                    // idAsString unwraps the value class — otherwise the WHERE
                    // clause mismatches the stored "abc" and rowsAffected stays 0).
                    val deleteResult = repo.softDelete(FixtureId("abc"), clientOpId = "op-2")
                    deleteResult.shouldBeInstanceOf<AppResult.Success<Unit>>()

                    (repo.deletedAtFor("abc") != null) shouldBe true
                }
            }
        }
    })

/**
 * Value class whose default `toString()` is `"FixtureId(value=abc)"` — the
 * bug shape we're guarding against. The fixture proves `idAsString` strips
 * the wrapper and writes the raw string everywhere it matters.
 */
@JvmInline
@Serializable
internal value class FixtureId(
    val value: String,
)

@Serializable
@SerialName("FixturePayload")
internal data class FixturePayload(
    val id: FixtureId,
    val label: String,
    val revision: Long,
    val updatedAt: Long,
    override val deletedAt: Long? = null,
) : Tombstoned

/**
 * SQLDelight twin of the former Exposed `FixtureTestTable` fixture: a global
 * (non-user-scoped) syncable aggregate on [SqlSyncableRepository] with a
 * `@JvmInline value class` id. Backs both this test and
 * [SyncableRepositoryFirehoseSuppressionTest]. The root table
 * `fixture_idAsString_test` is test-only (no Flyway migration), so [createSchema]
 * creates it via raw DDL and all reads/writes run raw SQL over the [SqlDriver].
 */
internal class FixtureRepository(
    db: ListenUpDatabase,
    override val driver: SqlDriver,
    bus: ChangeBus,
    registry: SyncRegistry,
) : SqlSyncableRepository<FixturePayload, FixtureId>(
        db = db,
        bus = bus,
        registry = registry,
        key = SyncDomainKey("fixtures", FixturePayload.serializer()),
    ) {
    override val FixturePayload.id: FixtureId get() = this.id

    override fun idAsString(id: FixtureId): String = id.value

    /** Materialises the test-only `fixture_idAsString_test` table (revision indexed). */
    fun createSchema() {
        driver.execute(
            identifier = null,
            sql =
                """
                CREATE TABLE IF NOT EXISTS fixture_idAsString_test (
                    id TEXT NOT NULL PRIMARY KEY,
                    label TEXT NOT NULL,
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
            sql = "CREATE INDEX IF NOT EXISTS ix_fixture_id_as_string_revision ON fixture_idAsString_test(revision)",
            parameters = 0,
        )
    }

    /** Test-only: the id stored in the (single-row) fixture table — proves the raw value, not toString(). */
    suspend fun singleStoredId(): String =
        suspendTransaction(db) {
            driver
                .executeQuery(
                    identifier = null,
                    sql = "SELECT id FROM fixture_idAsString_test",
                    mapper = { cursor ->
                        cursor.next()
                        QueryResult.Value(cursor.getString(0)!!)
                    },
                    parameters = 0,
                ).value
        }

    /** Test-only: the `deleted_at` tombstone for [idStr], or null if the row is live/absent. */
    suspend fun deletedAtFor(idStr: String): Long? =
        suspendTransaction(db) {
            driver
                .executeQuery(
                    identifier = null,
                    sql = "SELECT deleted_at FROM fixture_idAsString_test WHERE id = ?",
                    mapper = { cursor ->
                        QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else null)
                    },
                    parameters = 1,
                    binders = { bindString(0, idStr) },
                ).value
        }

    override val substrate: SyncableSubstrateQueries =
        object : SyncableSubstrateQueries {
            override fun existsById(id: String): Boolean =
                driver
                    .executeQuery(
                        identifier = null,
                        sql = "SELECT 1 FROM fixture_idAsString_test WHERE id = ? LIMIT 1",
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
                            "UPDATE fixture_idAsString_test " +
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
                    "SELECT id, revision FROM fixture_idAsString_test WHERE revision > ? ORDER BY revision ASC LIMIT ?",
                    listOf(cursor, limit),
                )

            override fun selectIdRevAtMost(cursor: Long): List<IdRev> =
                driver.queryIdRev(
                    "SELECT id, revision FROM fixture_idAsString_test WHERE revision <= ?",
                    listOf(cursor),
                )
        }

    override fun readPayload(idStr: String): FixturePayload? =
        driver
            .executeQuery(
                identifier = null,
                sql = "SELECT id, label, revision, updated_at, deleted_at FROM fixture_idAsString_test WHERE id = ?",
                mapper = { cursor ->
                    QueryResult.Value(
                        if (cursor.next().value) {
                            FixturePayload(
                                id = FixtureId(cursor.getString(0)!!),
                                label = cursor.getString(1)!!,
                                revision = cursor.getLong(2)!!,
                                updatedAt = cursor.getLong(3)!!,
                                deletedAt = cursor.getLong(4),
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
        value: FixturePayload,
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
                    "UPDATE fixture_idAsString_test " +
                        "SET label = ?, revision = ?, updated_at = ?, deleted_at = NULL, client_op_id = ? WHERE id = ?",
                parameters = 5,
                binders = {
                    bindString(0, value.label)
                    bindLong(1, rev)
                    bindLong(2, now)
                    bindString(3, clientOpId)
                    bindString(4, value.id.value)
                },
            )
        } else {
            driver.execute(
                identifier = null,
                sql =
                    "INSERT INTO fixture_idAsString_test " +
                        "(id, label, revision, created_at, updated_at, deleted_at, client_op_id) " +
                        "VALUES (?, ?, ?, ?, ?, NULL, ?)",
                parameters = 6,
                binders = {
                    bindString(0, value.id.value)
                    bindString(1, value.label)
                    bindLong(2, rev)
                    bindLong(3, now)
                    bindLong(4, now)
                    bindString(5, clientOpId)
                },
            )
        }
    }
}
