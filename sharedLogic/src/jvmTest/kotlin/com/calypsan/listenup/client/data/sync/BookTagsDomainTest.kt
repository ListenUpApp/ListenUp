package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.sync.BookTagSyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.sync.domains.bookTagsDomain
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
 * Covers [com.calypsan.listenup.client.data.sync.domains.bookTagsDomain]: Room write-through
 * for SSE book_tags junction events (Tags phase — Room v22).
 */
class BookTagsDomainTest :
    FunSpec({

        test("Created event inserts the junction row") {
            withHandler { handler, db ->
                handler
                    .onEvent(created(junctionPayload("b1", "t1", createdAt = 100L, revision = 1L)))
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()
                val row = db.bookTagDao().findByKey("b1", "t1")
                row shouldNotBe null
                row!!.bookId shouldBe "b1"
                row.tagId shouldBe "t1"
                row.createdAt shouldBe 100L
                row.revision shouldBe 1L
                row.deletedAt shouldBe null
            }
        }

        test("Updated event upserts the junction row (re-add semantics)") {
            withHandler { handler, db ->
                // Insert tombstoned row
                handler.onEvent(created(junctionPayload("b1", "t1", deletedAt = 500L, revision = 2L)))
                // Re-add: server emits Updated with deletedAt = null
                handler.onEvent(
                    SyncEvent.Updated(
                        id = "b1:t1",
                        revision = 3L,
                        occurredAt = 600L,
                        clientOpId = null,
                        payload = junctionPayload("b1", "t1", revision = 3L, deletedAt = null),
                    ),
                )
                val row = db.bookTagDao().findByKey("b1", "t1")!!
                row.deletedAt shouldBe null
                row.revision shouldBe 3L
            }
        }

        test("Deleted event tombstones the junction row via synthetic id") {
            withHandler { handler, db ->
                handler.onEvent(created(junctionPayload("b1", "t1", revision = 1L)))
                handler
                    .onEvent(
                        SyncEvent.Deleted(id = "b1:t1", revision = 2L, occurredAt = 800L),
                    ).shouldBeInstanceOf<AppResult.Success<Unit>>()
                val row = db.bookTagDao().findByKey("b1", "t1")!!
                row.deletedAt shouldBe 800L
            }
        }

        test("tombstoned row is EXCLUDED from digestRows — the digest counts live rows only (F1)") {
            withHandler { handler, db ->
                handler.onEvent(created(junctionPayload("b1", "t1", revision = 1L)))
                handler.onEvent(
                    SyncEvent.Deleted(id = "b1:t1", revision = 2L, occurredAt = 800L),
                )
                // observeForBook filters tombstones — invisible to reads
                db
                    .bookTagDao()
                    .observeForBook("b1")
                    .first()
                    .none { it.tagId == "t1" } shouldBe true
                // and EXCLUDED from the digest — the digest counts live rows only, so a client that
                // tombstoned this row locally converges (F1). Deletions still reach clients via the
                // firehose and the tombstone-ungated access-filtered catch-up. Digest-drift reconciliation (synthetic "$bookId:$tagId" id)
                db.bookTagDao().digestRows(Long.MAX_VALUE).map { it.id } shouldNotContain "b1:t1"
            }
        }

        test("Deleted event with malformed id logs warning and returns Success") {
            withHandler { handler, _ ->
                // Should not throw — just log and return Success
                handler
                    .onEvent(
                        SyncEvent.Deleted(id = "invalid-no-colon", revision = 1L, occurredAt = 100L),
                    ).shouldBeInstanceOf<AppResult.Success<Unit>>()
            }
        }

        test("onCatchUpItem live item inserts the junction row") {
            withHandler { handler, db ->
                handler
                    .onCatchUpItem(junctionPayload("b2", "t2", createdAt = 200L, revision = 1L), isTombstone = false)
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()
                val row = db.bookTagDao().findByKey("b2", "t2")
                row shouldNotBe null
                row!!.deletedAt shouldBe null
            }
        }

        test("onCatchUpItem tombstone sets deletedAt on the junction row") {
            withHandler { handler, db ->
                handler.onCatchUpItem(junctionPayload("b1", "t1"), isTombstone = false)
                handler
                    .onCatchUpItem(
                        junctionPayload("b1", "t1", deletedAt = 999L, revision = 2L),
                        isTombstone = true,
                    ).shouldBeInstanceOf<AppResult.Success<Unit>>()
                val row = db.bookTagDao().findByKey("b1", "t1")!!
                row.deletedAt shouldBe 999L
            }
        }

        test("handler self-registers under domainName 'book_tags'") {
            val registry = ClientSyncDomainRegistry()
            val db = createInMemoryTestDatabase()
            try {
                val handler = bookTagsDomain(db).toHandler(RoomTransactionRunner(db), registry)
                handler.domainName shouldBe "book_tags"
                registry.lookup("book_tags") shouldBe handler
            } finally {
                db.close()
            }
        }
    })

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun withHandler(block: suspend (SyncDomainHandler<BookTagSyncPayload>, ListenUpDatabase) -> Unit) =
    runTest {
        val db = createInMemoryTestDatabase()
        try {
            block(bookTagsDomain(db).toHandler(RoomTransactionRunner(db), ClientSyncDomainRegistry()), db)
        } finally {
            db.close()
        }
    }

private fun junctionPayload(
    bookId: String,
    tagId: String,
    createdAt: Long = 100L,
    revision: Long = 1L,
    deletedAt: Long? = null,
) = BookTagSyncPayload(
    bookId = bookId,
    tagId = tagId,
    createdAt = createdAt,
    revision = revision,
    deletedAt = deletedAt,
)

private fun created(payload: BookTagSyncPayload) =
    SyncEvent.Created(
        id = "${payload.bookId}:${payload.tagId}",
        revision = payload.revision,
        occurredAt = payload.createdAt,
        clientOpId = null,
        payload = payload,
    )
