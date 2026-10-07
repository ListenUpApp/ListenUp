@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.dto.readingorder.ReadingOrderChoiceKind
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.ReadingOrderBookSyncPayload
import com.calypsan.listenup.api.sync.ReadingOrderFollowSyncPayload
import com.calypsan.listenup.api.sync.ReadingOrderSyncPayload
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

class ReadingOrderRepositoriesTest :
    FunSpec({
        test("an order upserts, is found live, and its name is unique per series by normalized form") {
            withSqlDatabase {
                val repo = ReadingOrderRepository(sql, ChangeBus(), SyncRegistry())
                runTest {
                    repo
                        .upsert(order("ro", "cosmere", "Ultimate Read Order", createdBy = "simon"))
                        .shouldBeInstanceOf<AppResult.Success<ReadingOrderSyncPayload>>()
                    repo.findLive("ro")!!.let {
                        it.name shouldBe "Ultimate Read Order"
                        it.createdBy shouldBe "simon"
                    }
                    repo.liveIdForName("cosmere", "  ultimate  READ order ") shouldBe "ro"
                    repo.liveIdForName("mistborn", "Ultimate Read Order") shouldBe null
                    // Every user pulls every order: the domain is global.
                    repo.pullSince(userId = "anyone", cursor = 0, limit = 50).items.map { it.id } shouldContainExactly
                        listOf("ro")
                }
            }
        }

        test("a rename never rewrites the maker") {
            withSqlDatabase {
                val repo = ReadingOrderRepository(sql, ChangeBus(), SyncRegistry())
                runTest {
                    repo.upsert(order("ro", "cosmere", "URO", createdBy = "simon"))
                    repo.upsert(order("ro", "cosmere", "URO 2", createdBy = "mallory"))
                    repo.findLive("ro")!!.createdBy shouldBe "simon"
                }
            }
        }

        test("a membership resolves to the stored id of its natural pair, so a re-add reuses it") {
            withSqlDatabase {
                val members = ReadingOrderBookRepository(sql, ChangeBus(), SyncRegistry(), driver)
                runTest {
                    ReadingOrderRepository(sql, ChangeBus(), SyncRegistry()).upsert(order("ro", "s", "A", "u"))
                    members.upsert(member("m1", "ro", "b1", 0)).shouldBeInstanceOf<AppResult.Success<*>>()
                    members
                        .upsert(member("m2", "ro", "b1", 5))
                        .shouldBeInstanceOf<AppResult.Success<ReadingOrderBookSyncPayload>>()
                        .data.id shouldBe "m1"
                    members.softDelete(ReadingOrderBookKey("ro", "b1")).shouldBeInstanceOf<AppResult.Success<Unit>>()
                    members.liveMembers("ro").shouldBeEmpty()
                    members
                        .upsert(member("m3", "ro", "b1", 0))
                        .shouldBeInstanceOf<AppResult.Success<ReadingOrderBookSyncPayload>>()
                        .data
                        .let {
                            it.id shouldBe "m1"
                            it.deletedAt shouldBe null
                        }
                }
            }
        }

        test("a membership tombstone crosses the wire with the pair blanked") {
            withSqlDatabase {
                val members = ReadingOrderBookRepository(sql, ChangeBus(), SyncRegistry(), driver)
                runTest {
                    ReadingOrderRepository(sql, ChangeBus(), SyncRegistry()).upsert(order("ro", "s", "A", "u"))
                    members.upsert(member("m1", "ro", "b1", 0))
                    members.softDelete(ReadingOrderBookKey("ro", "b1"))
                    members.pullSince(userId = null, cursor = 0, limit = 50).items.single().let {
                        it.id shouldBe "m1"
                        it.readingOrderId shouldBe ""
                        it.bookId shouldBe ""
                    }
                }
            }
        }

        test("follows are user-scoped: another user's pull never sees them") {
            withSqlDatabase {
                val repo = ReadingOrderFollowRepository(sql, ChangeBus(), SyncRegistry())
                runTest {
                    repo.upsert(
                        follow("jess", "cosmere", ReadingOrderChoiceKind.PUBLICATION, null),
                        userId = "jess",
                    )
                    repo.pullSince(userId = "priya", cursor = 0, limit = 50).items.shouldBeEmpty()
                    repo.pullSince(userId = "jess", cursor = 0, limit = 50).items.single().choice shouldBe
                        ReadingOrderChoiceKind.PUBLICATION
                }
            }
        }

        test("followers of an order are counted across users, live ones only") {
            withSqlDatabase {
                val repo = ReadingOrderFollowRepository(sql, ChangeBus(), SyncRegistry())
                runTest {
                    repo.upsert(follow("jess", "cosmere", ReadingOrderChoiceKind.ORDER, "ro"), userId = "jess")
                    repo.upsert(follow("priya", "mistborn", ReadingOrderChoiceKind.ORDER, "ro"), userId = "priya")
                    repo.upsert(follow("sam", "cosmere", ReadingOrderChoiceKind.SERIES, null), userId = "sam")
                    repo.countFollowersOf("ro") shouldBe 2
                    repo.softDelete("priya:mistborn", userId = "priya")
                    repo.liveFollowersOf("ro") shouldContainExactly listOf(ReadingOrderFollower("jess:cosmere", "jess"))
                }
            }
        }
    })

private fun order(
    id: String,
    seriesId: String,
    name: String,
    createdBy: String,
) = ReadingOrderSyncPayload(id, seriesId, name, createdBy, revision = 0, updatedAt = 0, createdAt = 0, deletedAt = null)

private fun member(
    id: String,
    orderId: String,
    bookId: String,
    position: Int,
) = ReadingOrderBookSyncPayload(id, orderId, bookId, position, revision = 0, updatedAt = 0, createdAt = 0, deletedAt = null)

private fun follow(
    userId: String,
    seriesId: String,
    kind: ReadingOrderChoiceKind,
    orderId: String?,
) = ReadingOrderFollowSyncPayload(
    "$userId:$seriesId",
    seriesId,
    kind,
    orderId,
    revision = 0,
    updatedAt = 0,
    createdAt = 0,
    deletedAt = null,
)
