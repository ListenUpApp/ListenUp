@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.Tombstoned
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
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
 * Pins the per-user dimension of [SyncableRepository]: a repository whose
 * table is a [UserScopedSyncableTable] and which sets `userScoped = true`
 * threads the `userId` argument through both the read path (`pullSince`
 * filters by owning user) and the publish path (the emitted [BusEvent]
 * carries the writing user's id).
 *
 *  - Assertion (a): `pullSince(userId = "u1", ...)` returns only u1's rows
 *    when the table holds rows for both u1 and u2.
 *  - Assertion (b): `upsert(value, userId = "u1")` publishes a [BusEvent]
 *    whose `userId == "u1"`.
 */
class UserScopedRepositoryTest :
    FunSpec({

        test("pullSince(userId) returns only the requested user's rows") {
            withInMemoryDatabase {
                val db = this
                val repo = UserScopedFixtureRepository(db, ChangeBus(), SyncRegistry())

                runTest {
                    suspendTransaction(db) { SchemaUtils.create(UserScopedFixtureTable) }

                    repo.upsert(UserScopedPayload(id = "a", label = "alpha"), userId = "u1")
                    repo.upsert(UserScopedPayload(id = "b", label = "beta"), userId = "u1")
                    repo.upsert(UserScopedPayload(id = "c", label = "gamma"), userId = "u2")

                    val u1Page = repo.pullSince(userId = "u1", cursor = 0L, limit = 50)
                    u1Page.items.map { it.id } shouldContainExactlyInAnyOrder listOf("a", "b")

                    val u2Page = repo.pullSince(userId = "u2", cursor = 0L, limit = 50)
                    u2Page.items.map { it.id } shouldContainExactlyInAnyOrder listOf("c")
                }
            }
        }

        test("upsert(userId) publishes a BusEvent carrying that userId") {
            withInMemoryDatabase {
                val db = this
                val bus = ChangeBus()
                val repo = UserScopedFixtureRepository(db, bus, SyncRegistry())

                runTest {
                    suspendTransaction(db) { SchemaUtils.create(UserScopedFixtureTable) }

                    val deferredEvent = async { bus.subscribe().first() }
                    advanceUntilIdle()

                    val result =
                        repo.upsert(
                            UserScopedPayload(id = "a", label = "alpha"),
                            clientOpId = null,
                            userId = "u1",
                        )
                    result.shouldBeInstanceOf<AppResult.Success<UserScopedPayload>>()

                    deferredEvent.await().userId shouldBe "u1"
                }
            }
        }
    })

@Serializable
@SerialName("UserScopedPayload")
internal data class UserScopedPayload(
    val id: String,
    val label: String,
    val revision: Long = 0,
    val updatedAt: Long = 0,
    override val deletedAt: Long? = null,
) : Tombstoned

internal object UserScopedFixtureTable : UserScopedSyncableTable("user_scoped_fixture_test") {
    val id = text("id")
    val label = text("label")
    override val primaryKey = PrimaryKey(id)
}

internal class UserScopedFixtureRepository(
    db: Database,
    bus: ChangeBus,
    registry: SyncRegistry,
) : SyncableRepository<UserScopedPayload, String>(
        db,
        UserScopedFixtureTable,
        bus,
        registry,
        "user_scoped_fixtures",
    ) {
    override val userScoped: Boolean = true

    override val elementSerializer: KSerializer<UserScopedPayload> = UserScopedPayload.serializer()

    override val UserScopedPayload.id: String get() = id

    override fun UserScopedPayload.revisionOf(): Long = revision

    override suspend fun readPayload(idStr: String): UserScopedPayload? =
        UserScopedFixtureTable
            .selectAll()
            .where { UserScopedFixtureTable.id eq idStr }
            .firstOrNull()
            ?.let { row ->
                UserScopedPayload(
                    id = row[UserScopedFixtureTable.id],
                    label = row[UserScopedFixtureTable.label],
                    revision = row[UserScopedFixtureTable.revision],
                    updatedAt = row[UserScopedFixtureTable.updatedAt],
                    deletedAt = row[UserScopedFixtureTable.deletedAt],
                )
            }

    override suspend fun writePayload(
        value: UserScopedPayload,
        rev: Long,
        now: Long,
        clientOpId: String?,
        userId: String?,
        existed: Boolean,
    ) {
        if (existed) {
            UserScopedFixtureTable.update({ UserScopedFixtureTable.id eq value.id }) { stmt ->
                stmt[UserScopedFixtureTable.label] = value.label
                stmt[UserScopedFixtureTable.revision] = rev
                stmt[UserScopedFixtureTable.updatedAt] = now
                stmt[UserScopedFixtureTable.deletedAt] = null
                stmt[UserScopedFixtureTable.clientOpId] = clientOpId
            }
        } else {
            UserScopedFixtureTable.insert { stmt ->
                stmt[UserScopedFixtureTable.id] = value.id
                stmt[UserScopedFixtureTable.label] = value.label
                stmt[UserScopedFixtureTable.userId] =
                    requireNotNull(userId) { "user-scoped writePayload requires a userId" }
                stmt[UserScopedFixtureTable.revision] = rev
                stmt[UserScopedFixtureTable.createdAt] = now
                stmt[UserScopedFixtureTable.updatedAt] = now
                stmt[UserScopedFixtureTable.deletedAt] = null
                stmt[UserScopedFixtureTable.clientOpId] = clientOpId
            }
        }
    }
}
