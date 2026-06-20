package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.CollectionShareSyncPayload
import com.calypsan.listenup.api.sync.CollectionSyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.sync.handlers.CollectionBookSyncDomainHandler
import com.calypsan.listenup.client.data.sync.handlers.CollectionShareSyncDomainHandler
import com.calypsan.listenup.client.data.sync.handlers.CollectionSyncDomainHandler
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.api.result.AppResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/**
 * Covers the three Collections sync handlers (Collections — Room v24):
 * [CollectionSyncDomainHandler] (leaf), [CollectionBookSyncDomainHandler] (junction),
 * and [CollectionShareSyncDomainHandler] (leaf). Each exercises Room write-through for
 * Created/Updated upserts, Deleted tombstones, and catch-up tombstone application.
 */
class CollectionSyncHandlerTest :
    FunSpec({

        // ── collections (leaf) ──────────────────────────────────────────────────

        test("collection Created event upserts the row into Room") {
            withCollectionHandler { handler, db ->
                handler
                    .onEvent(createdCollection(collectionPayload("c1", name = "Favourites")), isOwnEcho = false)
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()
                val row = db.collectionDao().getById("c1")
                row shouldNotBe null
                row!!.name shouldBe "Favourites"
                row.libraryId shouldBe "lib1"
                row.ownerId shouldBe "u1"
                row.revision shouldBe 1L
                row.deletedAt shouldBe null
            }
        }

        test("collection Updated event overwrites the existing row") {
            withCollectionHandler { handler, db ->
                handler.onEvent(createdCollection(collectionPayload("c1", name = "Favourites")), isOwnEcho = false)
                handler.onEvent(
                    SyncEvent.Updated(
                        id = "c1",
                        revision = 2L,
                        occurredAt = 200L,
                        clientOpId = null,
                        payload = collectionPayload("c1", name = "Top Picks", revision = 2L),
                    ),
                    isOwnEcho = false,
                )
                val row = db.collectionDao().getById("c1")!!
                row.name shouldBe "Top Picks"
                row.revision shouldBe 2L
            }
        }

        test("collection Deleted event soft-deletes the row") {
            withCollectionHandler { handler, db ->
                handler.onEvent(createdCollection(collectionPayload("c1")), isOwnEcho = false)
                handler
                    .onEvent(SyncEvent.Deleted(id = "c1", revision = 2L, occurredAt = 500L), isOwnEcho = false)
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()
                db.collectionDao().getById("c1") shouldBe null
            }
        }

        test("collection onCatchUpItem live item upserts the row") {
            withCollectionHandler { handler, db ->
                handler
                    .onCatchUpItem(collectionPayload("c2", name = "Sci-Fi"), isTombstone = false)
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()
                db.collectionDao().getById("c2")!!.name shouldBe "Sci-Fi"
            }
        }

        test("collection onCatchUpItem tombstone soft-deletes the row") {
            withCollectionHandler { handler, db ->
                handler.onCatchUpItem(collectionPayload("c1"), isTombstone = false)
                handler
                    .onCatchUpItem(
                        collectionPayload("c1", revision = 3L, deletedAt = 700L),
                        isTombstone = true,
                    ).shouldBeInstanceOf<AppResult.Success<Unit>>()
                db.collectionDao().getById("c1") shouldBe null
            }
        }

        test("collection handler self-registers under domainName 'collections'") {
            val registry = ClientSyncDomainRegistry()
            val db = createInMemoryTestDatabase()
            try {
                val handler = CollectionSyncDomainHandler(db, RoomTransactionRunner(db), registry)
                handler.domainName shouldBe "collections"
                registry.lookup("collections") shouldBe handler
            } finally {
                db.close()
            }
        }

        // ── collection_books (junction) ─────────────────────────────────────────

        test("collection_books Created event inserts the junction row") {
            withCollectionBookHandler { handler, db ->
                handler
                    .onEvent(createdJunction(junctionPayload("c1", "b1", createdAt = 100L, revision = 1L)), isOwnEcho = false)
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
            withCollectionBookHandler { handler, db ->
                handler.onEvent(createdJunction(junctionPayload("c1", "b1", deletedAt = 500L, revision = 2L)), isOwnEcho = false)
                handler.onEvent(
                    SyncEvent.Updated(
                        id = "c1:b1",
                        revision = 3L,
                        occurredAt = 600L,
                        clientOpId = null,
                        payload = junctionPayload("c1", "b1", revision = 3L, deletedAt = null),
                    ),
                    isOwnEcho = false,
                )
                val row = db.collectionBookDao().findByKey("c1", "b1")!!
                row.deletedAt shouldBe null
                row.revision shouldBe 3L
            }
        }

        test("collection_books Deleted event tombstones via synthetic id") {
            withCollectionBookHandler { handler, db ->
                handler.onEvent(createdJunction(junctionPayload("c1", "b1", revision = 1L)), isOwnEcho = false)
                handler
                    .onEvent(SyncEvent.Deleted(id = "c1:b1", revision = 2L, occurredAt = 800L), isOwnEcho = false)
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()
                val row = db.collectionBookDao().findByKey("c1", "b1")!!
                row.deletedAt shouldBe 800L
                row.revision shouldBe 2L
            }
        }

        test("collection_books Deleted event with malformed id logs and returns Success") {
            withCollectionBookHandler { handler, _ ->
                handler
                    .onEvent(SyncEvent.Deleted(id = "no-colon-here", revision = 1L, occurredAt = 100L), isOwnEcho = false)
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()
            }
        }

        test("collection_books onCatchUpItem tombstone sets deletedAt") {
            withCollectionBookHandler { handler, db ->
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
                val handler = CollectionBookSyncDomainHandler(db, RoomTransactionRunner(db), registry)
                handler.domainName shouldBe "collection_books"
                registry.lookup("collection_books") shouldBe handler
            } finally {
                db.close()
            }
        }

        // ── collection_shares (leaf) ──────────────────────────────────────────────

        test("collection_shares Created event upserts the row with permission roundtrip") {
            withCollectionShareHandler { handler, db ->
                handler
                    .onEvent(createdShare(sharePayload("s1", "c1", permission = SharePermission.Write)), isOwnEcho = false)
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()
                val row = db.collectionShareDao().getById("s1")
                row shouldNotBe null
                row!!.collectionId shouldBe "c1"
                row.sharedWithUserId shouldBe "u2"
                row.sharedByUserId shouldBe "u1"
                row.permission shouldBe "write"
                row.deletedAt shouldBe null
            }
        }

        test("collection_shares Read permission roundtrips to 'read' string") {
            withCollectionShareHandler { handler, db ->
                handler.onEvent(createdShare(sharePayload("s1", "c1", permission = SharePermission.Read)), isOwnEcho = false)
                db.collectionShareDao().getById("s1")!!.permission shouldBe "read"
            }
        }

        test("collection_shares Deleted event soft-deletes (revokes) the row") {
            withCollectionShareHandler { handler, db ->
                handler.onEvent(createdShare(sharePayload("s1", "c1")), isOwnEcho = false)
                handler
                    .onEvent(SyncEvent.Deleted(id = "s1", revision = 2L, occurredAt = 500L), isOwnEcho = false)
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()
                db.collectionShareDao().getById("s1") shouldBe null
            }
        }

        test("collection_shares onCatchUpItem tombstone soft-deletes the row") {
            withCollectionShareHandler { handler, db ->
                handler.onCatchUpItem(sharePayload("s1", "c1"), isTombstone = false)
                handler
                    .onCatchUpItem(sharePayload("s1", "c1", revision = 3L, deletedAt = 700L), isTombstone = true)
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()
                db.collectionShareDao().getById("s1") shouldBe null
            }
        }

        test("collection_shares handler self-registers under domainName 'collection_shares'") {
            val registry = ClientSyncDomainRegistry()
            val db = createInMemoryTestDatabase()
            try {
                val handler = CollectionShareSyncDomainHandler(db, RoomTransactionRunner(db), registry)
                handler.domainName shouldBe "collection_shares"
                registry.lookup("collection_shares") shouldBe handler
            } finally {
                db.close()
            }
        }
    })

// ── Helpers ─────────────────────────────────────────────────────────────────────

private fun withCollectionHandler(block: suspend (CollectionSyncDomainHandler, ListenUpDatabase) -> Unit) =
    runTest {
        val db = createInMemoryTestDatabase()
        try {
            block(CollectionSyncDomainHandler(db, RoomTransactionRunner(db), ClientSyncDomainRegistry()), db)
        } finally {
            db.close()
        }
    }

private fun withCollectionBookHandler(block: suspend (CollectionBookSyncDomainHandler, ListenUpDatabase) -> Unit) =
    runTest {
        val db = createInMemoryTestDatabase()
        try {
            block(CollectionBookSyncDomainHandler(db, RoomTransactionRunner(db), ClientSyncDomainRegistry()), db)
        } finally {
            db.close()
        }
    }

private fun withCollectionShareHandler(block: suspend (CollectionShareSyncDomainHandler, ListenUpDatabase) -> Unit) =
    runTest {
        val db = createInMemoryTestDatabase()
        try {
            block(CollectionShareSyncDomainHandler(db, RoomTransactionRunner(db), ClientSyncDomainRegistry()), db)
        } finally {
            db.close()
        }
    }

private fun collectionPayload(
    id: String,
    name: String = "My Collection",
    revision: Long = 1L,
    deletedAt: Long? = null,
) = CollectionSyncPayload(
    id = id,
    libraryId = "lib1",
    ownerId = "u1",
    name = name,
    isInbox = false,
    revision = revision,
    updatedAt = 100L,
    deletedAt = deletedAt,
)

private fun createdCollection(payload: CollectionSyncPayload) =
    SyncEvent.Created(
        id = payload.id,
        revision = payload.revision,
        occurredAt = payload.updatedAt,
        clientOpId = null,
        payload = payload,
    )

private fun junctionPayload(
    collectionId: String,
    bookId: String,
    createdAt: Long = 100L,
    revision: Long = 1L,
    deletedAt: Long? = null,
) = CollectionBookSyncPayload(
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

private fun sharePayload(
    id: String,
    collectionId: String,
    permission: SharePermission = SharePermission.Read,
    revision: Long = 1L,
    deletedAt: Long? = null,
) = CollectionShareSyncPayload(
    id = id,
    collectionId = collectionId,
    sharedWithUserId = "u2",
    sharedByUserId = "u1",
    permission = permission,
    revision = revision,
    updatedAt = 100L,
    deletedAt = deletedAt,
)

private fun createdShare(payload: CollectionShareSyncPayload) =
    SyncEvent.Created(
        id = payload.id,
        revision = payload.revision,
        occurredAt = payload.updatedAt,
        clientOpId = null,
        payload = payload,
    )
