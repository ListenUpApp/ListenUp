package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.sync.domains.collectionBooksDomain
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
 * Covers [com.calypsan.listenup.client.data.sync.domains.collectionBooksDomain]: Room
 * write-through for the `collection_books` junction (composite `"$collectionId:$bookId"`
 * envelope id) plus the access gate (extracted from the combined collections test when
 * the domain migrated to the descriptor catalog).
 */
class CollectionBooksDomainTest :
    FunSpec({

        test("collection_books Created event inserts the junction row") {
            withHandler { handler, db ->
                handler
                    .onEvent(createdJunction(junctionPayload("c1", "b1", createdAt = 100L, revision = 1L)))
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()
                val row = db.collectionBookDao().findByKey("c1", "b1")
                row shouldNotBe null
                row!!.collectionId shouldBe "c1"
                row.bookId shouldBe "b1"
                row.createdAt shouldBe 100L
                row.revision shouldBe 1L
                row.deletedAt shouldBe null
            }
        }

        test("collection_books Updated event upserts (re-add clears tombstone)") {
            withHandler { handler, db ->
                handler.onEvent(createdJunction(junctionPayload("c1", "b1", deletedAt = 500L, revision = 2L)))
                handler.onEvent(
                    SyncEvent.Updated(
                        id = "c1:b1",
                        revision = 3L,
                        occurredAt = 600L,
                        clientOpId = null,
                        payload = junctionPayload("c1", "b1", revision = 3L, deletedAt = null),
                    ),
                )
                val row = db.collectionBookDao().findByKey("c1", "b1")!!
                row.deletedAt shouldBe null
                row.revision shouldBe 3L
            }
        }

        test("collection_books Deleted event tombstones via synthetic id") {
            withHandler { handler, db ->
                handler.onEvent(createdJunction(junctionPayload("c1", "b1", revision = 1L)))
                handler
                    .onEvent(SyncEvent.Deleted(id = "c1:b1", revision = 2L, occurredAt = 800L))
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()
                val row = db.collectionBookDao().findByKey("c1", "b1")!!
                row.deletedAt shouldBe 800L
                row.revision shouldBe 2L
            }
        }

        test("tombstoned row is EXCLUDED from digestRows — the digest counts live rows only (F1)") {
            withHandler { handler, db ->
                handler.onEvent(createdJunction(junctionPayload("c1", "b1", revision = 1L)))
                handler.onEvent(SyncEvent.Deleted(id = "c1:b1", revision = 2L, occurredAt = 800L))
                // observeBookIds filters tombstones — invisible to reads
                db
                    .collectionBookDao()
                    .observeBookIds("c1")
                    .first()
                    .none { it == "b1" } shouldBe true
                // and EXCLUDED from the digest — the digest counts live rows only, so a client that
                // tombstoned this row locally converges (F1). Deletions still reach clients via the
                // firehose and the tombstone-ungated access-filtered catch-up. Digest-drift reconciliation (synthetic "$collectionId:$bookId" id)
                db.collectionBookDao().digestRows(Long.MAX_VALUE).map { it.id } shouldNotContain "c1:b1"
            }
        }

        test("collection_books Deleted event with malformed id logs and returns Success") {
            withHandler { handler, _ ->
                handler
                    .onEvent(SyncEvent.Deleted(id = "no-colon-here", revision = 1L, occurredAt = 100L))
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()
            }
        }

        test("collection_books onCatchUpItem tombstone sets deletedAt") {
            withHandler { handler, db ->
                handler.onCatchUpItem(junctionPayload("c1", "b1"), isTombstone = false)
                handler
                    .onCatchUpItem(junctionPayload("c1", "b1", deletedAt = 999L, revision = 2L), isTombstone = true)
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()
                db.collectionBookDao().findByKey("c1", "b1")!!.deletedAt shouldBe 999L
            }
        }

        test("collection_books handler self-registers under domainName 'collection_books'") {
            val registry = ClientSyncDomainRegistry()
            val db = createInMemoryTestDatabase()
            try {
                val handler = collectionBooksDomain(db).toHandler(RoomTransactionRunner(db), registry)
                handler.domainName shouldBe "collection_books"
                registry.lookup("collection_books") shouldBe handler
            } finally {
                db.close()
            }
        }

        test("composed collection_books handler exposes the access gate") {
            val db = createInMemoryTestDatabase()
            try {
                val handler =
                    collectionBooksDomain(db)
                        .toHandler(RoomTransactionRunner(db), ClientSyncDomainRegistry())
                handler.shouldBeInstanceOf<AccessFilteredSyncHandler>()
            } finally {
                db.close()
            }
        }
    })

// ── Helpers ─────────────────────────────────────────────────────────────────────

private fun withHandler(block: suspend (SyncDomainHandler<CollectionBookSyncPayload>, ListenUpDatabase) -> Unit) =
    runTest {
        val db = createInMemoryTestDatabase()
        try {
            block(collectionBooksDomain(db).toHandler(RoomTransactionRunner(db), ClientSyncDomainRegistry()), db)
        } finally {
            db.close()
        }
    }

private fun junctionPayload(
    collectionId: String,
    bookId: String,
    createdAt: Long = 100L,
    revision: Long = 1L,
    deletedAt: Long? = null,
) = CollectionBookSyncPayload(
    id = "$collectionId:$bookId",
    collectionId = collectionId,
    bookId = bookId,
    createdAt = createdAt,
    revision = revision,
    deletedAt = deletedAt,
)

private fun createdJunction(payload: CollectionBookSyncPayload) =
    SyncEvent.Created(
        id = "${payload.collectionId}:${payload.bookId}",
        revision = payload.revision,
        occurredAt = payload.createdAt,
        clientOpId = null,
        payload = payload,
    )
