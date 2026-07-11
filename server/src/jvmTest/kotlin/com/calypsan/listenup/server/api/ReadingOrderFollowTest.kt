@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.readingorder.SetActiveReadingOrderRequest
import com.calypsan.listenup.api.error.ReadingOrderError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.ReadingOrderBookRepository
import com.calypsan.listenup.server.sync.ReadingOrderFollowRepository
import com.calypsan.listenup.server.sync.ReadingOrderRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import app.cash.sqldelight.db.SqlDriver
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * Follow-state coverage (Integration Foundations §5.4): [ReadingOrderServiceImpl.setActiveReadingOrder]
 * upserts the caller's per-series follow row through [ReadingOrderFollowRepository], with the
 * deterministic `"$userId:$seriesId"` identity and user-scoped pull isolation.
 */
class ReadingOrderFollowTest :
    FunSpec({

        val fixedClock = FixedClock(Instant.fromEpochMilliseconds(1_700_000_000_000L))

        fun principalFor(
            userId: String,
            role: UserRole = UserRole.MEMBER,
        ): PrincipalProvider = PrincipalProvider { UserPrincipal(UserId(userId), SessionId("session-$userId"), role) }

        fun makeService(
            sql: ListenUpDatabase,
            driver: SqlDriver,
        ): ReadingOrderServiceImpl {
            val bus = ChangeBus()
            val registry = SyncRegistry()
            return ReadingOrderServiceImpl(
                readingOrderRepo = ReadingOrderRepository(db = sql, bus = bus, registry = registry),
                readingOrderBookRepo = ReadingOrderBookRepository(db = sql, bus = bus, registry = registry),
                followRepo = ReadingOrderFollowRepository(db = sql, bus = bus, registry = registry),
                bookAccessPolicy = BookAccessPolicy(sql, driver),
                readAssembler = ReadingOrderReadAssembler(sql),
                clock = fixedClock,
                principal = principalFor("u1"),
            )
        }

        fun ReadingOrderServiceImpl.actAs(
            userId: String,
            role: UserRole = UserRole.MEMBER,
        ): ReadingOrderServiceImpl = copyWith(principalFor(userId, role))

        fun <T> AppResult<T>.value(): T {
            this.shouldBeInstanceOf<AppResult.Success<T>>()
            return data
        }

        test("setActiveReadingOrder creates the follow row with the deterministic id") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("u1")
                runTest {
                    val service = makeService(sql, driver).actAs("u1")
                    val order = service.createReadingOrder(name = "Cosmere").value()

                    service
                        .setActiveReadingOrder(
                            SetActiveReadingOrderRequest(seriesId = "series-1", activeReadingOrderId = order.id.value),
                        ).value()

                    val row = sql.readingOrderFollowsQueries.selectById("u1:series-1").executeAsOne()
                    row.series_id shouldBe "series-1"
                    row.active_reading_order_id shouldBe order.id.value
                    row.user_id shouldBe "u1"
                }
            }
        }

        test("setActiveReadingOrder with null resets to the per-book frontier floor (row kept, value null)") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("u1")
                runTest {
                    val service = makeService(sql, driver).actAs("u1")
                    val order = service.createReadingOrder(name = "Cosmere").value()

                    service
                        .setActiveReadingOrder(
                            SetActiveReadingOrderRequest(seriesId = "series-1", activeReadingOrderId = order.id.value),
                        ).value()
                    service
                        .setActiveReadingOrder(
                            SetActiveReadingOrderRequest(seriesId = "series-1", activeReadingOrderId = null),
                        ).value()

                    val row = sql.readingOrderFollowsQueries.selectById("u1:series-1").executeAsOne()
                    row.active_reading_order_id shouldBe null
                    // Update, not a second insert: revision advanced on the same row.
                    row.revision shouldNotBe 0L
                }
            }
        }

        test("setActiveReadingOrder rejects a reading order the caller cannot see") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("u1")
                sql.seedTestUser("u2")
                runTest {
                    val base = makeService(sql, driver)
                    val privateOrder = base.actAs("u2").createReadingOrder(name = "Secret", isPrivate = true).value()

                    val result =
                        base.actAs("u1").setActiveReadingOrder(
                            SetActiveReadingOrderRequest(seriesId = "series-1", activeReadingOrderId = privateOrder.id.value),
                        )
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<ReadingOrderError.NotFound>()
                }
            }
        }

        test("deleting a reading order clears every follow row pointing at it, with a synced revision bump") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("u1")
                sql.seedTestUser("u2")
                runTest {
                    val bus = ChangeBus()
                    val registry = SyncRegistry()
                    val followRepo = ReadingOrderFollowRepository(db = sql, bus = bus, registry = registry)
                    val service =
                        ReadingOrderServiceImpl(
                            readingOrderRepo = ReadingOrderRepository(db = sql, bus = bus, registry = registry),
                            readingOrderBookRepo = ReadingOrderBookRepository(db = sql, bus = bus, registry = registry),
                            followRepo = followRepo,
                            bookAccessPolicy = BookAccessPolicy(sql, driver),
                            readAssembler = ReadingOrderReadAssembler(sql),
                            clock = fixedClock,
                            principal = principalFor("u1"),
                        )

                    // u1 publishes an order; u2 follows it (a cross-user follow on a public order).
                    val order = service.actAs("u1").createReadingOrder(name = "Cosmere", isPrivate = false).value()
                    service
                        .actAs("u2")
                        .setActiveReadingOrder(
                            SetActiveReadingOrderRequest(seriesId = "series-1", activeReadingOrderId = order.id.value),
                        ).value()
                    val revisionBefore =
                        sql.readingOrderFollowsQueries
                            .selectById("u2:series-1")
                            .executeAsOne()
                            .revision

                    // u1 deletes the order — u2's follow must not keep the dangling pointer.
                    service.actAs("u1").deleteReadingOrder(order.id).value()

                    val row = sql.readingOrderFollowsQueries.selectById("u2:series-1").executeAsOne()
                    row.active_reading_order_id shouldBe null
                    row.deleted_at shouldBe null
                    // Revision bumped so the clear is a sync event — u2's device drops it live
                    // and a catch-up pull delivers the cleared payload.
                    (row.revision > revisionBefore) shouldBe true
                    val pulled = followRepo.pullSince(userId = "u2", cursor = revisionBefore, limit = 50)
                    pulled.items.map { it.id } shouldContainExactly listOf("u2:series-1")
                    pulled.items.single().activeReadingOrderId shouldBe null
                }
            }
        }

        test("follow rows pull user-scoped: user A never sees user B's follows") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("u1")
                sql.seedTestUser("u2")
                runTest {
                    val bus = ChangeBus()
                    val registry = SyncRegistry()
                    val followRepo = ReadingOrderFollowRepository(db = sql, bus = bus, registry = registry)
                    val base = makeService(sql, driver)
                    val order1 = base.actAs("u1").createReadingOrder(name = "A order").value()
                    val order2 = base.actAs("u2").createReadingOrder(name = "B order").value()

                    base
                        .actAs("u1")
                        .setActiveReadingOrder(
                            SetActiveReadingOrderRequest(seriesId = "s1", activeReadingOrderId = order1.id.value),
                        ).value()
                    base
                        .actAs("u2")
                        .setActiveReadingOrder(
                            SetActiveReadingOrderRequest(seriesId = "s1", activeReadingOrderId = order2.id.value),
                        ).value()

                    val aPage = followRepo.pullSince(userId = "u1", cursor = 0L, limit = 50)
                    aPage.items.map { it.id } shouldContainExactly listOf("u1:s1")
                    aPage.items.single().activeReadingOrderId shouldBe order1.id.value

                    val bPage = followRepo.pullSince(userId = "u2", cursor = 0L, limit = 50)
                    bPage.items.map { it.id } shouldContainExactly listOf("u2:s1")
                }
            }
        }
    })
