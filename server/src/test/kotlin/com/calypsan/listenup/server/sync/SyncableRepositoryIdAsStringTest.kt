@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.api.sync.Tombstoned
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.jvm.JvmInline
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Inline-fixture test that pins the `idAsString` template-hook contract:
 *
 * `SyncableRepository` MUST serialize the domain id to its raw string value
 * for every WHERE clause, UPDATE statement, and `SyncEvent` entity id —
 * never via Kotlin's default `toString()`, which on a `@JvmInline value class`
 * emits `"WrapperName(value=foo)"` and would corrupt every column.
 *
 * The fixture uses [FixtureId] — a `@JvmInline value class` whose default
 * `toString()` is `"FixtureId(value=abc)"`. Three assertions cover the three
 * call sites (`upsert` WHERE, `softDelete` WHERE, emitted event `id`):
 *  - upsertFromPayload stores `"abc"` (not `"FixtureId(value=abc)"`) in the id column.
 *  - softDelete(FixtureId("abc")) finds the row and sets deletedAt.
 *  - Emitted SyncEvent.Created.id is `"abc"`, not `"FixtureId(value=abc)"`.
 */
class SyncableRepositoryIdAsStringTest :
    FunSpec({

        test("idAsString override is honoured by upsert, softDelete, and event entityId") {
            withInMemoryDatabase {
                val db = this
                val bus = ChangeBus()
                val repo = FixtureRepository(db, bus, SyncRegistry())

                runTest {
                    suspendTransaction(db) { SchemaUtils.create(FixtureTestTable) }

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

                    val storedId =
                        suspendTransaction(db) {
                            FixtureTestTable
                                .selectAll()
                                .map { it[FixtureTestTable.id] }
                                .single()
                        }
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

                    val deletedAt =
                        suspendTransaction(db) {
                            FixtureTestTable
                                .selectAll()
                                .where { FixtureTestTable.id eq "abc" }
                                .map { it[FixtureTestTable.deletedAt] }
                                .single()
                        }
                    (deletedAt != null) shouldBe true
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

internal object FixtureTestTable : SyncableTable("fixture_idAsString_test") {
    val id = text("id")
    val label = text("label")
    override val primaryKey = PrimaryKey(id)
}

internal class FixtureRepository(
    db: Database,
    bus: ChangeBus,
    registry: SyncRegistry,
) : SyncableRepository<FixturePayload, FixtureId>(db, FixtureTestTable, bus, registry, "fixtures") {
    override val elementSerializer: KSerializer<FixturePayload> = FixturePayload.serializer()

    override val FixturePayload.id: FixtureId get() = this.id

    override fun FixturePayload.revisionOf(): Long = revision

    override fun idAsString(id: FixtureId): String = id.value

    override suspend fun readPayload(idStr: String): FixturePayload? =
        FixtureTestTable
            .selectAll()
            .where { FixtureTestTable.id eq idStr }
            .firstOrNull()
            ?.let { row ->
                FixturePayload(
                    id = FixtureId(row[FixtureTestTable.id]),
                    label = row[FixtureTestTable.label],
                    revision = row[FixtureTestTable.revision],
                    updatedAt = row[FixtureTestTable.updatedAt],
                    deletedAt = row[FixtureTestTable.deletedAt],
                )
            }

    override suspend fun writePayload(
        value: FixturePayload,
        rev: Long,
        now: Long,
        clientOpId: String?,
        existed: Boolean,
    ) {
        if (existed) {
            FixtureTestTable.update({ FixtureTestTable.id eq value.id.value }) { stmt ->
                stmt[FixtureTestTable.label] = value.label
                stmt[FixtureTestTable.revision] = rev
                stmt[FixtureTestTable.updatedAt] = now
                stmt[FixtureTestTable.deletedAt] = null
                stmt[FixtureTestTable.clientOpId] = clientOpId
            }
        } else {
            FixtureTestTable.insert { stmt ->
                stmt[FixtureTestTable.id] = value.id.value
                stmt[FixtureTestTable.label] = value.label
                stmt[FixtureTestTable.revision] = rev
                stmt[FixtureTestTable.createdAt] = now
                stmt[FixtureTestTable.updatedAt] = now
                stmt[FixtureTestTable.deletedAt] = null
                stmt[FixtureTestTable.clientOpId] = clientOpId
            }
        }
    }
}
