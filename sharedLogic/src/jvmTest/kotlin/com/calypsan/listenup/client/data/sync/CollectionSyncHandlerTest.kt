package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.api.sync.CollectionShareSyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.sync.handlers.CollectionShareSyncDomainHandler
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.api.result.AppResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/**
 * Covers the [CollectionShareSyncDomainHandler] leaf: Room write-through for
 * Created/Updated upserts, Deleted tombstones, and catch-up tombstone application
 * (Collections — Room v24). (The `collections` leaf domain moved to
 * [com.calypsan.listenup.client.data.sync.domains.collectionsDomain] — see
 * `CollectionsDomainTest`; the `collection_books` junction moved to
 * [com.calypsan.listenup.client.data.sync.domains.collectionBooksDomain] — see
 * `CollectionBooksDomainTest`.)
 */
class CollectionSyncHandlerTest :
    FunSpec({

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

private fun withCollectionShareHandler(block: suspend (CollectionShareSyncDomainHandler, ListenUpDatabase) -> Unit) =
    runTest {
        val db = createInMemoryTestDatabase()
        try {
            block(CollectionShareSyncDomainHandler(db, RoomTransactionRunner(db), ClientSyncDomainRegistry()), db)
        } finally {
            db.close()
        }
    }

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
