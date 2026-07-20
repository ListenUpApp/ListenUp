package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookMoodSyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.sync.domains.bookMoodsDomain
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
 * Covers [com.calypsan.listenup.client.data.sync.domains.bookMoodsDomain]: Room
 * write-through for SSE book_moods junction events. Mirrors [BookTagsDomainTest].
 */
class BookMoodsDomainTest :
    FunSpec({

        test("Created event inserts the junction row") {
            withHandler { handler, db ->
                handler
                    .onEvent(created(junctionPayload("b1", "m1")))
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()
                val row = db.bookMoodDao().findByKey("b1", "m1")
                row shouldNotBe null
                row!!.bookId shouldBe "b1"
                row.moodId shouldBe "m1"
                row.revision shouldBe 1L
                row.deletedAt shouldBe null
            }
        }

        test("Updated event upserts the junction row (re-add clears tombstone)") {
            withHandler { handler, db ->
                handler.onEvent(created(junctionPayload("b1", "m1", deletedAt = 500L)))
                handler.onEvent(
                    SyncEvent.Updated(
                        id = "b1:m1",
                        revision = 2L,
                        occurredAt = 600L,
                        clientOpId = null,
                        payload = junctionPayload("b1", "m1", revision = 2L),
                    ),
                )
                val row = db.bookMoodDao().findByKey("b1", "m1")!!
                row.deletedAt shouldBe null
                row.revision shouldBe 2L
            }
        }

        test("Deleted event tombstones via synthetic id") {
            withHandler { handler, db ->
                handler.onEvent(created(junctionPayload("b1", "m1")))
                handler
                    .onEvent(
                        SyncEvent.Deleted(id = "b1:m1", revision = 2L, occurredAt = 900L),
                    ).shouldBeInstanceOf<AppResult.Success<Unit>>()
                db.bookMoodDao().findByKey("b1", "m1")!!.deletedAt shouldBe 900L
            }
        }

        test("tombstoned row is EXCLUDED from digestRows — the digest counts live rows only (F1)") {
            withHandler { handler, db ->
                handler.onEvent(created(junctionPayload("b1", "m1")))
                handler.onEvent(
                    SyncEvent.Deleted(id = "b1:m1", revision = 2L, occurredAt = 900L),
                )
                // observeForBook filters tombstones — invisible to reads
                db
                    .bookMoodDao()
                    .observeForBook("b1")
                    .first()
                    .none { it.moodId == "m1" } shouldBe true
                // and EXCLUDED from the digest — the digest counts live rows only, so a client that
                // tombstoned this row locally converges (F1). Deletions still reach clients via the
                // firehose and the tombstone-ungated access-filtered catch-up. Digest-drift reconciliation (synthetic "$bookId:$moodId" id)
                db.bookMoodDao().digestRows(Long.MAX_VALUE).map { it.id } shouldNotContain "b1:m1"
            }
        }

        test("Deleted event for an unknown id is a graceful no-op") {
            // SERVER-SYNC-04: the wire id is opaque and never parsed, so there is no "malformed id"
            // failure mode anymore — the only failure mode is an id that matches no local row, which
            // must log and return Success without touching any other row.
            withHandler { handler, db ->
                handler.onEvent(created(junctionPayload("b1", "m1", revision = 1L)))

                handler
                    .onEvent(
                        SyncEvent.Deleted(id = "never-seen-opaque-id", revision = 2L, occurredAt = 900L),
                    ).shouldBeInstanceOf<AppResult.Success<Unit>>()

                db.bookMoodDao().findByKey("b1", "m1")!!.deletedAt shouldBe null
            }
        }

        test("onCatchUpItem tombstone sets deletedAt") {
            withHandler { handler, db ->
                handler.onCatchUpItem(junctionPayload("b1", "m1"), isTombstone = false)
                handler
                    .onCatchUpItem(
                        junctionPayload("b1", "m1", revision = 2L, deletedAt = 999L),
                        isTombstone = true,
                    ).shouldBeInstanceOf<AppResult.Success<Unit>>()
                db.bookMoodDao().findByKey("b1", "m1")!!.deletedAt shouldBe 999L
            }
        }

        test("handler self-registers under domainName 'book_moods'") {
            val registry = ClientSyncDomainRegistry()
            val db = createInMemoryTestDatabase()
            try {
                val handler = bookMoodsDomain(db).toHandler(RoomTransactionRunner(db), registry)
                handler.domainName shouldBe "book_moods"
                registry.lookup("book_moods") shouldBe handler
            } finally {
                db.close()
            }
        }
    })

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun withHandler(block: suspend (SyncDomainHandler<BookMoodSyncPayload>, ListenUpDatabase) -> Unit) =
    runTest {
        val db = createInMemoryTestDatabase()
        try {
            block(bookMoodsDomain(db).toHandler(RoomTransactionRunner(db), ClientSyncDomainRegistry()), db)
        } finally {
            db.close()
        }
    }

private fun junctionPayload(
    bookId: String,
    moodId: String,
    createdAt: Long = 100L,
    revision: Long = 1L,
    deletedAt: Long? = null,
) = BookMoodSyncPayload(
    id = "$bookId:$moodId",
    bookId = bookId,
    moodId = moodId,
    createdAt = createdAt,
    revision = revision,
    deletedAt = deletedAt,
)

private fun created(payload: BookMoodSyncPayload) =
    SyncEvent.Created(
        id = "${payload.bookId}:${payload.moodId}",
        revision = payload.revision,
        occurredAt = payload.createdAt,
        clientOpId = null,
        payload = payload,
    )
