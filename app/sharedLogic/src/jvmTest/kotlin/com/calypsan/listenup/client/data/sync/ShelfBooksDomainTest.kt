package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.ShelfBookSyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.local.db.ShelfBookEntity
import com.calypsan.listenup.client.data.sync.domains.shelfBooksDomain
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
 * Covers [com.calypsan.listenup.client.data.sync.domains.shelfBooksDomain]: Room
 * write-through for SSE shelf_books junction events (extracted from the combined
 * shelves test when the domain migrated to the descriptor catalog).
 */
class ShelfBooksDomainTest :
    FunSpec({

        test("shelf_books Created event inserts the junction row") {
            withHandler { handler, db ->
                handler
                    .onEvent(createdJunction(junctionPayload("s1", "b1", sortOrder = 0, revision = 1L)))
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()
                val row = db.shelfBookDao().findById("s1:b1")
                row shouldNotBe null
                row!!.shelfId shouldBe "s1"
                row.bookId shouldBe "b1"
                row.sortOrder shouldBe 0
                row.revision shouldBe 1L
                row.deletedAt shouldBe null
            }
        }

        test("upsert reconciles a client-minted row to the server's echoed id for the same pair — no duplicate") {
            // SERVER-SYNC-04: shelf_books' primary key IS the opaque wire id (unlike the other three
            // junction domains, whose PK is the natural pair), so a client-minted optimistic add and
            // the server's later Created echo for the SAME (shelfId, bookId) carry DIFFERENT primary
            // keys. ShelfBookMirrorApply.upsert must collapse them into one row, not leave both.
            withHandler { handler, db ->
                db.shelfBookDao().upsert(
                    ShelfBookEntity(
                        id = "client-minted-id",
                        shelfId = "s1",
                        bookId = "b1",
                        sortOrder = 0,
                        revision = 0,
                        deletedAt = null,
                        updatedAt = 100L,
                        createdAt = 100L,
                    ),
                )

                handler
                    .onEvent(createdJunction(junctionPayload("s1", "b1", sortOrder = 0, revision = 1L, id = "server-echoed-id")))
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()

                db.shelfBookDao().findById("client-minted-id") shouldBe null
                val row = db.shelfBookDao().findById("server-echoed-id")
                row shouldNotBe null
                row!!.revision shouldBe 1L
                db.shelfBookDao().digestRows(Long.MAX_VALUE).map { it.id } shouldBe listOf("server-echoed-id")
            }
        }

        test("shelf_books Updated event upserts (re-add clears tombstone)") {
            withHandler { handler, db ->
                handler.onEvent(
                    createdJunction(junctionPayload("s1", "b1", deletedAt = 500L, revision = 2L)),
                )
                handler.onEvent(
                    SyncEvent.Updated(
                        id = "s1:b1",
                        revision = 3L,
                        occurredAt = 600L,
                        clientOpId = null,
                        payload = junctionPayload("s1", "b1", revision = 3L, deletedAt = null),
                    ),
                )
                val row = db.shelfBookDao().findById("s1:b1")!!
                row.deletedAt shouldBe null
                row.revision shouldBe 3L
            }
        }

        test("shelf_books Deleted event tombstones via synthetic id") {
            withHandler { handler, db ->
                handler.onEvent(createdJunction(junctionPayload("s1", "b1", revision = 1L)))
                handler
                    .onEvent(SyncEvent.Deleted(id = "s1:b1", revision = 2L, occurredAt = 800L))
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()
                val row = db.shelfBookDao().findById("s1:b1")!!
                row.deletedAt shouldBe 800L
                row.revision shouldBe 2L
            }
        }

        test("tombstoned row is EXCLUDED from digestRows — the digest counts live rows only (F1)") {
            withHandler { handler, db ->
                handler.onEvent(createdJunction(junctionPayload("s1", "b1", revision = 1L)))
                handler.onEvent(SyncEvent.Deleted(id = "s1:b1", revision = 2L, occurredAt = 800L))
                // observeShelfBooks filters tombstones — invisible to reads
                db
                    .shelfBookDao()
                    .observeShelfBooks("s1")
                    .first()
                    .none { it == "b1" } shouldBe true
                // and EXCLUDED from the digest — the digest counts live rows only, so a client that
                // tombstoned this row locally converges (F1). Deletions still reach clients via the
                // firehose and the tombstone-ungated access-filtered catch-up. Digest-drift reconciliation
                db.shelfBookDao().digestRows(Long.MAX_VALUE).map { it.id } shouldNotContain "s1:b1"
            }
        }

        test("shelf_books onCatchUpItem tombstone sets deletedAt") {
            withHandler { handler, db ->
                handler.onCatchUpItem(junctionPayload("s1", "b1"), isTombstone = false)
                handler
                    .onCatchUpItem(junctionPayload("s1", "b1", deletedAt = 999L, revision = 2L), isTombstone = true)
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()
                db.shelfBookDao().findById("s1:b1")!!.deletedAt shouldBe 999L
            }
        }

        test("shelf_books handler self-registers under domainName 'shelf_books'") {
            val registry = ClientSyncDomainRegistry()
            val db = createInMemoryTestDatabase()
            try {
                val handler = shelfBooksDomain(db).toHandler(RoomTransactionRunner(db), registry)
                handler.domainName shouldBe "shelf_books"
                registry.lookup("shelf_books") shouldBe handler
            } finally {
                db.close()
            }
        }
    })

// ── Helpers ─────────────────────────────────────────────────────────────────────

private fun withHandler(block: suspend (SyncDomainHandler<ShelfBookSyncPayload>, ListenUpDatabase) -> Unit) =
    runTest {
        val db = createInMemoryTestDatabase()
        try {
            block(shelfBooksDomain(db).toHandler(RoomTransactionRunner(db), ClientSyncDomainRegistry()), db)
        } finally {
            db.close()
        }
    }

private fun junctionPayload(
    shelfId: String,
    bookId: String,
    sortOrder: Int = 0,
    revision: Long = 1L,
    deletedAt: Long? = null,
    id: String = "$shelfId:$bookId",
) = ShelfBookSyncPayload(
    id = id,
    shelfId = shelfId,
    bookId = bookId,
    sortOrder = sortOrder,
    revision = revision,
    updatedAt = 100L,
    createdAt = 50L,
    deletedAt = deletedAt,
)

private fun createdJunction(payload: ShelfBookSyncPayload) =
    SyncEvent.Created(
        id = payload.id,
        revision = payload.revision,
        occurredAt = payload.createdAt,
        clientOpId = null,
        payload = payload,
    )
