package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.ShelfBookSyncPayload
import com.calypsan.listenup.api.sync.ShelfSyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.sync.domains.shelvesDomain
import com.calypsan.listenup.client.data.sync.domains.toHandler
import com.calypsan.listenup.client.data.sync.handlers.ShelfBookSyncDomainHandler
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/**
 * Covers the two Shelves sync domains (Shelves — Room v26): the
 * [com.calypsan.listenup.client.data.sync.domains.shelvesDomain] descriptor (leaf) and
 * [ShelfBookSyncDomainHandler] (junction). Each exercises Room write-through for
 * Created/Updated upserts, Deleted tombstones, and catch-up tombstone application.
 */
class ShelvesDomainTest :
    FunSpec({

        // ── shelves (leaf) ──────────────────────────────────────────────────────

        test("shelf Created event upserts the row into Room") {
            withShelfHandler { handler, db ->
                handler
                    .onEvent(createdShelf(shelfPayload("s1", name = "Favourites")), isOwnEcho = false)
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
                handler.onEvent(createdShelf(shelfPayload("s1", name = "Favourites")), isOwnEcho = false)
                handler.onEvent(
                    SyncEvent.Updated(
                        id = "s1",
                        revision = 2L,
                        occurredAt = 200L,
                        clientOpId = null,
                        payload = shelfPayload("s1", name = "Top Picks", revision = 2L),
                    ),
                    isOwnEcho = false,
                )
                val row = db.shelfDao().getById("s1")!!
                row.name shouldBe "Top Picks"
                row.revision shouldBe 2L
            }
        }

        test("shelf Deleted event soft-deletes the row") {
            withShelfHandler { handler, db ->
                handler.onEvent(createdShelf(shelfPayload("s1")), isOwnEcho = false)
                handler
                    .onEvent(SyncEvent.Deleted(id = "s1", revision = 2L, occurredAt = 500L), isOwnEcho = false)
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()
                db.shelfDao().getById("s1") shouldBe null
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

        // ── shelf_books (junction) ────────────────────────────────────────────────

        test("shelf_books Created event inserts the junction row") {
            withShelfBookHandler { handler, db ->
                handler
                    .onEvent(createdJunction(junctionPayload("s1", "b1", sortOrder = 0, revision = 1L)), isOwnEcho = false)
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

        test("shelf_books Updated event upserts (re-add clears tombstone)") {
            withShelfBookHandler { handler, db ->
                handler.onEvent(
                    createdJunction(junctionPayload("s1", "b1", deletedAt = 500L, revision = 2L)),
                    isOwnEcho = false,
                )
                handler.onEvent(
                    SyncEvent.Updated(
                        id = "s1:b1",
                        revision = 3L,
                        occurredAt = 600L,
                        clientOpId = null,
                        payload = junctionPayload("s1", "b1", revision = 3L, deletedAt = null),
                    ),
                    isOwnEcho = false,
                )
                val row = db.shelfBookDao().findById("s1:b1")!!
                row.deletedAt shouldBe null
                row.revision shouldBe 3L
            }
        }

        test("shelf_books Deleted event tombstones via synthetic id") {
            withShelfBookHandler { handler, db ->
                handler.onEvent(createdJunction(junctionPayload("s1", "b1", revision = 1L)), isOwnEcho = false)
                handler
                    .onEvent(SyncEvent.Deleted(id = "s1:b1", revision = 2L, occurredAt = 800L), isOwnEcho = false)
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()
                val row = db.shelfBookDao().findById("s1:b1")!!
                row.deletedAt shouldBe 800L
                row.revision shouldBe 2L
            }
        }

        test("shelf_books onCatchUpItem tombstone sets deletedAt") {
            withShelfBookHandler { handler, db ->
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
                val handler = ShelfBookSyncDomainHandler(db, RoomTransactionRunner(db), registry)
                handler.domainName shouldBe "shelf_books"
                registry.lookup("shelf_books") shouldBe handler
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

private fun withShelfBookHandler(block: suspend (ShelfBookSyncDomainHandler, ListenUpDatabase) -> Unit) =
    runTest {
        val db = createInMemoryTestDatabase()
        try {
            block(ShelfBookSyncDomainHandler(db, RoomTransactionRunner(db), ClientSyncDomainRegistry()), db)
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

private fun junctionPayload(
    shelfId: String,
    bookId: String,
    sortOrder: Int = 0,
    revision: Long = 1L,
    deletedAt: Long? = null,
) = ShelfBookSyncPayload(
    id = "$shelfId:$bookId",
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
