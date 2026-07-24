package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.ShelfSyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.sync.domains.shelvesDomain
import com.calypsan.listenup.client.data.sync.domains.toHandler
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/**
 * Covers the [com.calypsan.listenup.client.data.sync.domains.shelvesDomain] descriptor
 * (Shelves — Room v26, leaf domain): Room write-through for Created/Updated upserts,
 * Deleted tombstones, and catch-up tombstone application. The `shelf_books` junction
 * lives in [ShelfBooksDomainTest].
 */
class ShelvesDomainTest :
    FunSpec({

        test("shelf Created event upserts the row into Room") {
            withShelfHandler { handler, db ->
                handler
                    .onEvent(createdShelf(shelfPayload("s1", name = "Favourites")))
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()
                val row = db.shelfDao().getById("s1")
                row shouldNotBe null
                row!!.name shouldBe "Favourites"
                row.revision shouldBe 1L
                row.deletedAt shouldBe null
            }
        }

        test("shelf Updated event overwrites the existing row") {
            withShelfHandler { handler, db ->
                handler.onEvent(createdShelf(shelfPayload("s1", name = "Favourites")))
                handler.onEvent(
                    SyncEvent.Updated(
                        id = "s1",
                        revision = 2L,
                        occurredAt = 200L,
                        clientOpId = null,
                        payload = shelfPayload("s1", name = "Top Picks", revision = 2L),
                    ),
                )
                val row = db.shelfDao().getById("s1")!!
                row.name shouldBe "Top Picks"
                row.revision shouldBe 2L
            }
        }

        test("shelf Deleted event soft-deletes the row") {
            withShelfHandler { handler, db ->
                handler.onEvent(createdShelf(shelfPayload("s1")))
                handler
                    .onEvent(SyncEvent.Deleted(id = "s1", revision = 2L, occurredAt = 500L))
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()
                db.shelfDao().getById("s1") shouldBe null
            }
        }

        test("tombstoned row is EXCLUDED from digestRows — the digest counts live rows only (F1)") {
            withShelfHandler { handler, db ->
                handler.onEvent(createdShelf(shelfPayload("s1")))
                handler.onEvent(SyncEvent.Deleted(id = "s1", revision = 2L, occurredAt = 500L))
                db.shelfDao().getById("s1") shouldBe null
                db.shelfDao().digestRows(Long.MAX_VALUE).map { it.id } shouldNotContain "s1"
            }
        }

        test("shelf onCatchUpItem live item upserts the row") {
            withShelfHandler { handler, db ->
                handler
                    .onCatchUpItem(shelfPayload("s2", name = "Sci-Fi"), isTombstone = false)
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()
                db.shelfDao().getById("s2")!!.name shouldBe "Sci-Fi"
            }
        }

        test("shelf onCatchUpItem tombstone soft-deletes the row") {
            withShelfHandler { handler, db ->
                handler.onCatchUpItem(shelfPayload("s1"), isTombstone = false)
                handler
                    .onCatchUpItem(shelfPayload("s1", revision = 3L, deletedAt = 700L), isTombstone = true)
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()
                db.shelfDao().getById("s1") shouldBe null
            }
        }

        test("shelf handler self-registers under domainName 'shelves'") {
            val registry = ClientSyncDomainRegistry()
            val db = createInMemoryTestDatabase()
            try {
                val handler = shelvesDomain(db).toHandler(RoomTransactionRunner(db), registry)
                handler.domainName shouldBe "shelves"
                registry.lookup("shelves") shouldBe handler
            } finally {
                db.close()
            }
        }
    })

// ── Helpers ─────────────────────────────────────────────────────────────────────

private fun withShelfHandler(block: suspend (SyncDomainHandler<ShelfSyncPayload>, ListenUpDatabase) -> Unit) =
    runTest {
        val db = createInMemoryTestDatabase()
        try {
            block(shelvesDomain(db).toHandler(RoomTransactionRunner(db), ClientSyncDomainRegistry()), db)
        } finally {
            db.close()
        }
    }

private fun shelfPayload(
    id: String,
    name: String = "My Shelf",
    revision: Long = 1L,
    deletedAt: Long? = null,
) = ShelfSyncPayload(
    id = id,
    name = name,
    description = "",
    isPrivate = false,
    revision = revision,
    updatedAt = 100L,
    createdAt = 50L,
    deletedAt = deletedAt,
)

private fun createdShelf(payload: ShelfSyncPayload) =
    SyncEvent.Created(
        id = payload.id,
        revision = payload.revision,
        occurredAt = payload.updatedAt,
        clientOpId = null,
        payload = payload,
    )
