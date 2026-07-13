package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.CollectionShareSyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.sync.domains.collectionSharesDomain
import com.calypsan.listenup.client.data.sync.domains.toHandler
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/**
 * Covers [com.calypsan.listenup.client.data.sync.domains.collectionSharesDomain]: Room
 * write-through for the `collection_shares` domain — Created/Updated upserts (with the
 * [SharePermission] enum → lowercase wire-string roundtrip), Deleted tombstones, and
 * catch-up tombstone application (Collections — Room v24). Extracted from the combined
 * collections test when the domain migrated to the descriptor catalog.
 */
class CollectionSharesDomainTest :
    FunSpec({

        test("collection_shares Created event upserts the row with permission roundtrip") {
            withHandler { handler, db ->
                handler
                    .onEvent(createdShare(sharePayload("s1", "c1", permission = SharePermission.Write)))
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
            withHandler { handler, db ->
                handler.onEvent(createdShare(sharePayload("s1", "c1", permission = SharePermission.Read)))
                db.collectionShareDao().getById("s1")!!.permission shouldBe "read"
            }
        }

        test("collection_shares Deleted event soft-deletes (revokes) the row") {
            withHandler { handler, db ->
                handler.onEvent(createdShare(sharePayload("s1", "c1")))
                handler
                    .onEvent(SyncEvent.Deleted(id = "s1", revision = 2L, occurredAt = 500L))
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()
                db.collectionShareDao().getById("s1") shouldBe null
            }
        }

        test("tombstoned row is EXCLUDED from digestRows — the digest counts live rows only (F1)") {
            withHandler { handler, db ->
                handler.onEvent(createdShare(sharePayload("s1", "c1")))
                handler.onEvent(SyncEvent.Deleted(id = "s1", revision = 2L, occurredAt = 500L))
                db.collectionShareDao().getById("s1") shouldBe null
                db.collectionShareDao().digestRows(Long.MAX_VALUE).map { it.id } shouldNotContain "s1"
            }
        }

        test("collection_shares onCatchUpItem tombstone soft-deletes the row") {
            withHandler { handler, db ->
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
                val handler = collectionSharesDomain(db).toHandler(RoomTransactionRunner(db), registry)
                handler.domainName shouldBe "collection_shares"
                registry.lookup("collection_shares") shouldBe handler
            } finally {
                db.close()
            }
        }
    })

// ── Helpers ─────────────────────────────────────────────────────────────────────

private fun withHandler(block: suspend (SyncDomainHandler<CollectionShareSyncPayload>, ListenUpDatabase) -> Unit) =
    runTest {
        val db = createInMemoryTestDatabase()
        try {
            block(collectionSharesDomain(db).toHandler(RoomTransactionRunner(db), ClientSyncDomainRegistry()), db)
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
