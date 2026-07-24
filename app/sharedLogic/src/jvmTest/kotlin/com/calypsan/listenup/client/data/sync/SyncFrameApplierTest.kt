package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.sync.ContributorSyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.api.sync.SyncFrame
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.sync.domains.contributorsDomain
import com.calypsan.listenup.client.data.sync.domains.toHandler
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.client.test.stubImageStorage
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/**
 * [SyncFrameApplier] is the client seam that gives an RPC mutation read-your-writes: it routes each
 * returned [SyncFrame] to its domain handler's `onEvent`. These tests pin the two properties the
 * echo-in-response design depends on — correct routing into Room, and idempotency under a re-apply
 * (the response now, the firehose echo for the same revision later).
 */
class SyncFrameApplierTest :
    FunSpec({

        fun contributorFrame(
            id: String,
            name: String,
            revision: Long,
        ): SyncFrame {
            val payload =
                ContributorSyncPayload(
                    id = id,
                    name = name,
                    sortName = name,
                    revision = revision,
                    updatedAt = 100L,
                    createdAt = 1L,
                    deletedAt = null,
                )
            val event: SyncEvent<ContributorSyncPayload> =
                SyncEvent.Updated(
                    id = id,
                    revision = revision,
                    occurredAt = 100L,
                    clientOpId = null,
                    payload = payload,
                )
            return SyncFrame(
                domain = "contributors",
                revision = revision,
                json = contractJson.encodeToString(SyncEvent.serializer(ContributorSyncPayload.serializer()), event),
            )
        }

        fun withApplier(block: suspend (SyncFrameApplier, ListenUpDatabase) -> Unit) =
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    val registry = ClientSyncDomainRegistry()
                    // Register the real production contributors handler — the applier routes to it by name.
                    contributorsDomain(db, stubImageStorage()).toHandler(RoomTransactionRunner(db), registry)
                    block(SyncFrameApplier(registry), db)
                } finally {
                    db.close()
                }
            }

        test("applies a contributor frame into Room through the registered handler") {
            withApplier { applier, db ->
                applier.apply(listOf(contributorFrame("c1", "Brandon Sanderson", revision = 3)))

                val row = db.contributorDao().getById("c1")
                row.shouldNotBeNull()
                row.name shouldBe "Brandon Sanderson"
                row.revision shouldBe 3L
            }
        }

        test("re-applying the SAME frame is idempotent — one row, no double-effect") {
            withApplier { applier, db ->
                val frame = contributorFrame("c1", "Brandon Sanderson", revision = 3)

                applier.apply(listOf(frame))
                applier.apply(listOf(frame)) // the later firehose echo for the same revision

                val rows = db.contributorDao().digestRows(Long.MAX_VALUE).filter { it.id == "c1" }
                rows.size shouldBe 1
                db.contributorDao().getById("c1")!!.revision shouldBe 3L
            }
        }

        test("a frame for an unregistered domain is a graceful no-op") {
            withApplier { applier, _ ->
                // No 'series' handler registered here — must not throw, must not affect other state.
                applier.apply(listOf(SyncFrame(domain = "series", revision = 1L, json = "{}")))
            }
        }
    })
