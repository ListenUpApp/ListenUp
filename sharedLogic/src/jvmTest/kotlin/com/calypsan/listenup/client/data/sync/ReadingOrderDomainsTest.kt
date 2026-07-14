package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.ReadingOrderBookSyncPayload
import com.calypsan.listenup.api.sync.ReadingOrderFollowSyncPayload
import com.calypsan.listenup.api.sync.ReadingOrderSyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.sync.domains.MirroredDomain
import com.calypsan.listenup.client.data.sync.domains.readingOrderBooksDomain
import com.calypsan.listenup.client.data.sync.domains.readingOrderFollowsDomain
import com.calypsan.listenup.client.data.sync.domains.readingOrdersDomain
import com.calypsan.listenup.client.data.sync.domains.toHandler
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/**
 * Covers the three reading-order sync domains — Room write-through for SSE
 * events and catch-up items (the [ShelfBooksDomainTest] pattern): leaf
 * (`reading_orders`, with `attribution`), junction (`reading_order_books`, with
 * `sortOrder`), and follow-state (`reading_order_follows`, Integration
 * Foundations §5.4).
 */
class ReadingOrderDomainsTest :
    FunSpec({

        // ── reading_orders (leaf) ───────────────────────────────────────────────

        test("reading_orders Created event inserts the row with attribution") {
            withDb { db ->
                val handler = readingOrdersDomain(db).handler(db)
                handler
                    .onEvent(created(orderPayload("ro1", attribution = "u/Argent", revision = 1L)))
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()
                val row = db.readingOrderDao().getById("ro1")
                row shouldNotBe null
                row!!.attribution shouldBe "u/Argent"
                row.revision shouldBe 1L
            }
        }

        test("reading_orders Deleted event tombstones the row") {
            withDb { db ->
                val handler = readingOrdersDomain(db).handler(db)
                handler.onEvent(created(orderPayload("ro1", revision = 1L)))
                handler
                    .onEvent(SyncEvent.Deleted(id = "ro1", revision = 2L, occurredAt = 800L))
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()
                db.readingOrderDao().getById("ro1") shouldBe null
                db.readingOrderDao().revisionOf("ro1") shouldBe 2L
            }
        }

        test("reading_orders self-registers under its wire name") {
            val registry = ClientSyncDomainRegistry()
            val db = createInMemoryTestDatabase()
            try {
                val handler = readingOrdersDomain(db).toHandler(RoomTransactionRunner(db), registry)
                handler.domainName shouldBe "reading_orders"
                registry.lookup("reading_orders") shouldBe handler
            } finally {
                db.close()
            }
        }

        // ── reading_order_books (junction) ─────────────────────────────────────

        test("reading_order_books Created event inserts the junction row with sortOrder") {
            withDb { db ->
                val handler = readingOrderBooksDomain(db).handler(db)
                handler
                    .onEvent(created(junctionPayload("ro1", "b1", sortOrder = 7, revision = 1L)))
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()
                val row = db.readingOrderBookDao().findById("ro1:b1")
                row shouldNotBe null
                row!!.sortOrder shouldBe 7
                row.deletedAt shouldBe null
            }
        }

        test("reading_order_books Updated event upserts (re-add clears tombstone)") {
            withDb { db ->
                val handler = readingOrderBooksDomain(db).handler(db)
                handler.onEvent(
                    created(junctionPayload("ro1", "b1", deletedAt = 500L, revision = 2L)),
                )
                handler.onEvent(
                    SyncEvent.Updated(
                        id = "ro1:b1",
                        revision = 3L,
                        occurredAt = 600L,
                        clientOpId = null,
                        payload = junctionPayload("ro1", "b1", revision = 3L, deletedAt = null),
                    ),
                )
                val row = db.readingOrderBookDao().findById("ro1:b1")!!
                row.deletedAt shouldBe null
                row.revision shouldBe 3L
            }
        }

        test("reading_order_books Deleted event tombstones and drops out of reads + digest") {
            withDb { db ->
                val handler = readingOrderBooksDomain(db).handler(db)
                handler.onEvent(created(junctionPayload("ro1", "b1", revision = 1L)))
                handler
                    .onEvent(SyncEvent.Deleted(id = "ro1:b1", revision = 2L, occurredAt = 800L))
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()
                db
                    .readingOrderBookDao()
                    .observeReadingOrderBooks("ro1")
                    .first()
                    .none { it == "b1" } shouldBe true
                db.readingOrderBookDao().digestRows(Long.MAX_VALUE).map { it.id } shouldNotContain "ro1:b1"
            }
        }

        test("reading_order_books onCatchUpItem tombstone sets deletedAt") {
            withDb { db ->
                val handler = readingOrderBooksDomain(db).handler(db)
                handler.onCatchUpItem(junctionPayload("ro1", "b1"), isTombstone = false)
                handler
                    .onCatchUpItem(junctionPayload("ro1", "b1", deletedAt = 999L, revision = 2L), isTombstone = true)
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()
                db.readingOrderBookDao().findById("ro1:b1")!!.deletedAt shouldBe 999L
            }
        }

        // ── reading_order_follows (follow-state, §5.4) ─────────────────────────

        test("reading_order_follows Created event upserts and observeActiveReadingOrderId sees it") {
            withDb { db ->
                val handler = readingOrderFollowsDomain(db).handler(db)
                handler
                    .onEvent(
                        created(followPayload("u1:series-1", "series-1", activeReadingOrderId = "ro1", revision = 1L)),
                    ).shouldBeInstanceOf<AppResult.Success<Unit>>()
                db.readingOrderFollowDao().observeActiveReadingOrderId("series-1").first() shouldBe "ro1"
            }
        }

        test("reading_order_follows Updated event nulls the active order (the graceful floor)") {
            withDb { db ->
                val handler = readingOrderFollowsDomain(db).handler(db)
                handler.onEvent(
                    created(followPayload("u1:series-1", "series-1", activeReadingOrderId = "ro1", revision = 1L)),
                )
                handler.onEvent(
                    SyncEvent.Updated(
                        id = "u1:series-1",
                        revision = 2L,
                        occurredAt = 600L,
                        clientOpId = null,
                        payload = followPayload("u1:series-1", "series-1", activeReadingOrderId = null, revision = 2L),
                    ),
                )
                db.readingOrderFollowDao().observeActiveReadingOrderId("series-1").first() shouldBe null
                db.readingOrderFollowDao().findById("u1:series-1")!!.revision shouldBe 2L
            }
        }

        test("reading_order_follows self-registers under its wire name") {
            val registry = ClientSyncDomainRegistry()
            val db = createInMemoryTestDatabase()
            try {
                val handler = readingOrderFollowsDomain(db).toHandler(RoomTransactionRunner(db), registry)
                handler.domainName shouldBe "reading_order_follows"
                registry.lookup("reading_order_follows") shouldBe handler
            } finally {
                db.close()
            }
        }
    })

// ── Helpers ─────────────────────────────────────────────────────────────────────

private fun withDb(block: suspend (ListenUpDatabase) -> Unit) =
    runTest {
        val db = createInMemoryTestDatabase()
        try {
            block(db)
        } finally {
            db.close()
        }
    }

private fun <T : com.calypsan.listenup.api.sync.SyncPayload> MirroredDomain<T>.handler(
    db: ListenUpDatabase,
): SyncDomainHandler<T> = toHandler(RoomTransactionRunner(db), ClientSyncDomainRegistry())

private fun orderPayload(
    id: String,
    attribution: String = "",
    revision: Long = 1L,
    deletedAt: Long? = null,
) = ReadingOrderSyncPayload(
    id = id,
    name = "Cosmere",
    description = "",
    attribution = attribution,
    isPrivate = false,
    revision = revision,
    updatedAt = 100L,
    createdAt = 50L,
    deletedAt = deletedAt,
)

private fun junctionPayload(
    readingOrderId: String,
    bookId: String,
    sortOrder: Int = 0,
    revision: Long = 1L,
    deletedAt: Long? = null,
) = ReadingOrderBookSyncPayload(
    id = "$readingOrderId:$bookId",
    readingOrderId = readingOrderId,
    bookId = bookId,
    sortOrder = sortOrder,
    revision = revision,
    updatedAt = 100L,
    createdAt = 50L,
    deletedAt = deletedAt,
)

private fun followPayload(
    id: String,
    seriesId: String,
    activeReadingOrderId: String?,
    revision: Long = 1L,
    deletedAt: Long? = null,
) = ReadingOrderFollowSyncPayload(
    id = id,
    seriesId = seriesId,
    activeReadingOrderId = activeReadingOrderId,
    revision = revision,
    updatedAt = 100L,
    createdAt = 50L,
    deletedAt = deletedAt,
)

private fun <T : Any> created(payload: T): SyncEvent.Created<T> {
    val (id, revision, createdAt) =
        when (payload) {
            is ReadingOrderSyncPayload -> Triple(payload.id, payload.revision, payload.createdAt)
            is ReadingOrderBookSyncPayload -> Triple(payload.id, payload.revision, payload.createdAt)
            is ReadingOrderFollowSyncPayload -> Triple(payload.id, payload.revision, payload.createdAt)
            else -> error("unsupported payload $payload")
        }
    return SyncEvent.Created(
        id = id,
        revision = revision,
        occurredAt = createdAt,
        clientOpId = null,
        payload = payload,
    )
}
