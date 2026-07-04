@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.sync

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.SyncDomainKey
import com.calypsan.listenup.api.sync.Tombstoned
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Pins the per-user dimension of [SqlSyncableRepository]: a repository that sets
 * `userScoped = true` over a `user_id`-carrying root table threads the `userId`
 * argument through both the read path (`pullSince` filters by owning user) and the
 * publish path (the emitted [BusEvent] carries the writing user's id).
 *
 *  - Assertion (a): `pullSince(userId = "u1", ...)` returns only u1's rows
 *    when the table holds rows for both u1 and u2.
 *  - Assertion (b): `upsert(value, userId = "u1")` publishes a [BusEvent]
 *    whose `userId == "u1"`.
 */
class UserScopedRepositoryTest :
    FunSpec({

        test("pullSince(userId) returns only the requested user's rows") {
            withSqlDatabase {
                val repo = UserScopedFixtureRepository(sql, driver, ChangeBus(), SyncRegistry())
                repo.createSchema()

                runTest {
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

        test("upsert(userId = null) on a userScoped repo throws IllegalArgumentException") {
            withSqlDatabase {
                val repo = UserScopedFixtureRepository(sql, driver, ChangeBus(), SyncRegistry())
                repo.createSchema()

                runTest {
                    shouldThrow<IllegalArgumentException> {
                        repo.upsert(UserScopedPayload(id = "a", label = "alpha"), userId = null)
                    }
                }
            }
        }

        test("softDelete(userId = null) on a userScoped repo throws IllegalArgumentException") {
            withSqlDatabase {
                val repo = UserScopedFixtureRepository(sql, driver, ChangeBus(), SyncRegistry())
                repo.createSchema()

                runTest {
                    shouldThrow<IllegalArgumentException> {
                        repo.softDelete("a", clientOpId = null, userId = null)
                    }
                }
            }
        }

        test("upsert(userId) publishes a BusEvent carrying that userId") {
            withSqlDatabase {
                val bus = ChangeBus()
                val repo = UserScopedFixtureRepository(sql, driver, bus, SyncRegistry())
                repo.createSchema()

                runTest {
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

/**
 * SQLDelight twin of the former Exposed `UserScopedFixtureTable` fixture: a per-user
 * syncable aggregate on [SqlSyncableRepository]. The root table `user_scoped_fixture_test`
 * is test-only (no Flyway migration), so [createSchema] creates it via raw DDL and the
 * substrate / read / write run raw SQL over the [SqlDriver] — the same engine-neutral
 * pattern [AccessFilteredReads] uses for production access-filtered reads.
 */
internal class UserScopedFixtureRepository(
    db: ListenUpDatabase,
    override val driver: SqlDriver,
    bus: ChangeBus,
    registry: SyncRegistry,
) : SqlSyncableRepository<UserScopedPayload, String>(
        db = db,
        bus = bus,
        registry = registry,
        key = SyncDomainKey("user_scoped_fixtures", UserScopedPayload.serializer()),
    ) {
    override val userScoped: Boolean = true

    override val UserScopedPayload.id: String get() = id

    override fun UserScopedPayload.revisionOf(): Long = revision

    /** Materialises the test-only `user_scoped_fixture_test` table (revision + user_id indexed). */
    fun createSchema() {
        driver.execute(
            identifier = null,
            sql =
                """
                CREATE TABLE IF NOT EXISTS user_scoped_fixture_test (
                    id TEXT NOT NULL PRIMARY KEY,
                    label TEXT NOT NULL,
                    user_id TEXT NOT NULL,
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
            sql = "CREATE INDEX IF NOT EXISTS ix_usf_revision ON user_scoped_fixture_test(revision)",
            parameters = 0,
        )
        driver.execute(
            identifier = null,
            sql = "CREATE INDEX IF NOT EXISTS ix_usf_user_id ON user_scoped_fixture_test(user_id)",
            parameters = 0,
        )
    }

    override val substrate: SyncableSubstrateQueries =
        object : SyncableSubstrateQueries {
            override fun existsById(id: String): Boolean =
                driver
                    .executeQuery(
                        identifier = null,
                        sql = "SELECT 1 FROM user_scoped_fixture_test WHERE id = ? LIMIT 1",
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
                            "UPDATE user_scoped_fixture_test " +
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
                    "SELECT id, revision FROM user_scoped_fixture_test WHERE revision > ? ORDER BY revision ASC LIMIT ?",
                    listOf(cursor, limit),
                )

            override fun selectIdRevAtMost(cursor: Long): List<IdRev> =
                driver.queryIdRev(
                    "SELECT id, revision FROM user_scoped_fixture_test WHERE revision <= ?",
                    listOf(cursor),
                )

            override fun selectIdsAboveRevisionForUser(
                userId: String,
                cursor: Long,
                limit: Long,
            ): List<IdRev> =
                driver.queryIdRev(
                    "SELECT id, revision FROM user_scoped_fixture_test " +
                        "WHERE user_id = ? AND revision > ? ORDER BY revision ASC LIMIT ?",
                    listOf(userId, cursor, limit),
                )

            override fun selectIdRevAtMostForUser(
                userId: String,
                cursor: Long,
            ): List<IdRev> =
                driver.queryIdRev(
                    "SELECT id, revision FROM user_scoped_fixture_test WHERE user_id = ? AND revision <= ?",
                    listOf(userId, cursor),
                )
        }

    override fun readPayload(idStr: String): UserScopedPayload? =
        driver
            .executeQuery(
                identifier = null,
                sql = "SELECT id, label, revision, updated_at, deleted_at FROM user_scoped_fixture_test WHERE id = ?",
                mapper = { cursor ->
                    QueryResult.Value(
                        if (cursor.next().value) {
                            UserScopedPayload(
                                id = cursor.getString(0)!!,
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
        value: UserScopedPayload,
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
                    "UPDATE user_scoped_fixture_test " +
                        "SET label = ?, revision = ?, updated_at = ?, deleted_at = NULL, client_op_id = ? WHERE id = ?",
                parameters = 5,
                binders = {
                    bindString(0, value.label)
                    bindLong(1, rev)
                    bindLong(2, now)
                    bindString(3, clientOpId)
                    bindString(4, value.id)
                },
            )
        } else {
            driver.execute(
                identifier = null,
                sql =
                    "INSERT INTO user_scoped_fixture_test " +
                        "(id, label, user_id, revision, created_at, updated_at, deleted_at, client_op_id) " +
                        "VALUES (?, ?, ?, ?, ?, ?, NULL, ?)",
                parameters = 7,
                binders = {
                    bindString(0, value.id)
                    bindString(1, value.label)
                    bindString(2, requireNotNull(userId) { "user-scoped writePayload requires a userId" })
                    bindLong(3, rev)
                    bindLong(4, now)
                    bindLong(5, now)
                    bindString(6, clientOpId)
                },
            )
        }
    }
}
