package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.CollectionSyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.sync.domains.collectionsDomain
import com.calypsan.listenup.client.data.sync.domains.toHandler
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/**
 * Covers [com.calypsan.listenup.client.data.sync.domains.collectionsDomain]: Room
 * write-through for SSE collections events plus the access gate (extracted from the
 * combined collections test when the domain migrated to the descriptor catalog).
 */
class CollectionsDomainTest :
    FunSpec({

        test("collection Created event upserts the row into Room") {
            withHandler { handler, db ->
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
            withHandler { handler, db ->
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
            withHandler { handler, db ->
                handler.onEvent(createdCollection(collectionPayload("c1")), isOwnEcho = false)
                handler
                    .onEvent(SyncEvent.Deleted(id = "c1", revision = 2L, occurredAt = 500L), isOwnEcho = false)
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()
                db.collectionDao().getById("c1") shouldBe null
            }
        }

        test("tombstoned row is EXCLUDED from digestRows — the digest counts live rows only (F1)") {
            withHandler { handler, db ->
                handler.onEvent(createdCollection(collectionPayload("c1")), isOwnEcho = false)
                handler.onEvent(SyncEvent.Deleted(id = "c1", revision = 2L, occurredAt = 500L), isOwnEcho = false)
                db.collectionDao().getById("c1") shouldBe null
                db.collectionDao().digestRows(Long.MAX_VALUE).map { it.id } shouldNotContain "c1"
            }
        }

        test("collection onCatchUpItem live item upserts the row") {
            withHandler { handler, db ->
                handler
                    .onCatchUpItem(collectionPayload("c2", name = "Sci-Fi"), isTombstone = false)
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()
                db.collectionDao().getById("c2")!!.name shouldBe "Sci-Fi"
            }
        }

        test("collection onCatchUpItem tombstone soft-deletes the row") {
            withHandler { handler, db ->
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
                val handler = collectionsDomain(db).toHandler(RoomTransactionRunner(db), registry)
                handler.domainName shouldBe "collections"
                registry.lookup("collections") shouldBe handler
            } finally {
                db.close()
            }
        }

        test("composed collections handler exposes the access gate") {
            val db = createInMemoryTestDatabase()
            try {
                val handler =
                    collectionsDomain(db)
                        .toHandler(RoomTransactionRunner(db), ClientSyncDomainRegistry())
                handler.shouldBeInstanceOf<AccessFilteredSyncHandler>()
            } finally {
                db.close()
            }
        }
    })

// ── Helpers ─────────────────────────────────────────────────────────────────────

private fun withHandler(block: suspend (SyncDomainHandler<CollectionSyncPayload>, ListenUpDatabase) -> Unit) =
    runTest {
        val db = createInMemoryTestDatabase()
        try {
            block(collectionsDomain(db).toHandler(RoomTransactionRunner(db), ClientSyncDomainRegistry()), db)
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
