package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.dto.readingorder.ReadingOrderChoiceKind
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.ReadingOrderBookSyncPayload
import com.calypsan.listenup.api.sync.ReadingOrderFollowSyncPayload
import com.calypsan.listenup.api.sync.ReadingOrderSyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.ReadingOrderBookEntity
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.sync.domains.AccessDeltaPolicy
import com.calypsan.listenup.client.data.sync.domains.readingOrderBooksDomain
import com.calypsan.listenup.client.data.sync.domains.readingOrderFollowsDomain
import com.calypsan.listenup.client.data.sync.domains.readingOrdersDomain
import com.calypsan.listenup.client.data.sync.domains.toHandler
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/** The three reading-order mirrors (#962): orders, their memberships, and the user's follows. */
class ReadingOrderDomainsTest :
    FunSpec({
        test("an order upsert mirrors into Room, and a tombstone soft-deletes it") {
            withDb { db ->
                val handler = readingOrdersDomain(db).toHandler(RoomTransactionRunner(db), ClientSyncDomainRegistry())
                handler.onEvent(created("ro", order("ro", revision = 1))).shouldBeInstanceOf<AppResult.Success<Unit>>()
                db.readingOrderDao().findById("ro")!!.let { order ->
                    order.name shouldBe "Ultimate Read Order"
                    order.seriesId shouldBe "cosmere"
                    order.createdBy shouldBe "simon"
                }
                handler.onEvent(SyncEvent.Deleted(id = "ro", revision = 2, occurredAt = 900))
                db.readingOrderDao().findById("ro")!!.deletedAt shouldBe 900L
                db.readingOrderDao().digestRows(Long.MAX_VALUE).shouldBeEmpty()
            }
        }

        test("a membership echo under another wire id replaces the optimistic row for the same pair, never duplicating it") {
            withDb { db ->
                db.readingOrderBookDao().upsert(
                    ReadingOrderBookEntity(readingOrderId = "ro", bookId = "b1", syncId = "client-1", position = 0, createdAt = 1),
                )
                val handler = readingOrderBooksDomain(db).toHandler(RoomTransactionRunner(db), ClientSyncDomainRegistry())
                handler.onEvent(created("server-1", member("server-1", "ro", "b1", position = 3, revision = 5)))
                db.readingOrderBookDao().liveForOrder("ro").let { rows ->
                    rows.size shouldBe 1
                    rows.single().syncId shouldBe "server-1"
                    rows.single().position shouldBe 3
                }
            }
        }

        test("a junction tombstone from catch-up applies by wire id even though the pair is blanked") {
            withDb { db ->
                val handler = readingOrderBooksDomain(db).toHandler(RoomTransactionRunner(db), ClientSyncDomainRegistry())
                handler.onCatchUpItem(member("m1", "ro", "b1", position = 0, revision = 1), isTombstone = false)
                handler.onCatchUpItem(
                    member("m1", readingOrderId = "", bookId = "", position = 0, revision = 2, deletedAt = 700),
                    isTombstone = true,
                )
                db.readingOrderBookDao().find("ro", "b1")!!.deletedAt shouldBe 700L
                db.readingOrderBookDao().liveForOrder("ro").shouldBeEmpty()
                db.readingOrderBookDao().find("", "") shouldBe null
            }
        }

        test("members read back first to last by position") {
            withDb { db ->
                val handler = readingOrderBooksDomain(db).toHandler(RoomTransactionRunner(db), ClientSyncDomainRegistry())
                handler.onCatchUpItem(member("m1", "ro", "b1", position = 2, revision = 1), isTombstone = false)
                handler.onCatchUpItem(member("m2", "ro", "b2", position = 0, revision = 2), isTombstone = false)
                handler.onCatchUpItem(member("m3", "ro", "b3", position = 1, revision = 3), isTombstone = false)
                db.readingOrderBookDao().liveForOrder("ro").map { it.bookId } shouldContainExactly listOf("b2", "b3", "b1")
            }
        }

        test("the books gate prunes memberships whose book left the viewer's scope") {
            withDb { db ->
                val domain = readingOrderBooksDomain(db)
                val handler = domain.toHandler(RoomTransactionRunner(db), ClientSyncDomainRegistry())
                handler.onCatchUpItem(member("m1", "ro", "b1", position = 0, revision = 1), isTombstone = false)
                handler.onCatchUpItem(member("m2", "ro", "b2", position = 1, revision = 2), isTombstone = false)
                val gate = domain.accessGate!!
                val targeted = gate.delta.shouldBeInstanceOf<AccessDeltaPolicy.Targeted>()
                targeted.candidatesFor(listOf("b1")) shouldBe setOf("m1")
                gate.tombstoneByIds(listOf("m1"), 1_000)
                gate.liveIds() shouldContainExactly listOf("m2")
            }
        }

        test("a follow upsert and its tombstone mirror for the signed-in user") {
            withDb { db ->
                val handler = readingOrderFollowsDomain(db).toHandler(RoomTransactionRunner(db), ClientSyncDomainRegistry())
                handler.onEvent(created("u1:mistborn", follow(ReadingOrderChoiceKind.ORDER, "ro", revision = 1)))
                db.readingOrderFollowDao().findById("u1:mistborn")!!.let { follow ->
                    follow.choice shouldBe "ORDER"
                    follow.readingOrderId shouldBe "ro"
                    follow.seriesId shouldBe "mistborn"
                }
                handler.onEvent(SyncEvent.Deleted(id = "u1:mistborn", revision = 2, occurredAt = 800))
                db.readingOrderFollowDao().findById("u1:mistborn")!!.deletedAt shouldBe 800L
            }
        }
    })

private fun withDb(block: suspend (ListenUpDatabase) -> Unit) =
    runTest {
        val db = createInMemoryTestDatabase()
        try {
            block(db)
        } finally {
            db.close()
        }
    }

private fun order(
    id: String,
    revision: Long,
) = ReadingOrderSyncPayload(id, "cosmere", "Ultimate Read Order", "simon", revision, updatedAt = 10, createdAt = 10)

private fun member(
    id: String,
    readingOrderId: String,
    bookId: String,
    position: Int,
    revision: Long,
    deletedAt: Long? = null,
) = ReadingOrderBookSyncPayload(id, readingOrderId, bookId, position, revision, updatedAt = 10, createdAt = 10, deletedAt = deletedAt)

private fun follow(
    kind: ReadingOrderChoiceKind,
    orderId: String?,
    revision: Long,
) = ReadingOrderFollowSyncPayload("u1:mistborn", "mistborn", kind, orderId, revision, updatedAt = 10, createdAt = 10)

private fun <T : Any> created(
    id: String,
    payload: T,
) = SyncEvent.Created(id = id, revision = 1, occurredAt = 10, clientOpId = null, payload = payload)
