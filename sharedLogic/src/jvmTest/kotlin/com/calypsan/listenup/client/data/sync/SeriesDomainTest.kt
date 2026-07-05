package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.sync.SeriesSyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.local.db.SeriesEntity
import com.calypsan.listenup.client.data.sync.domains.seriesDomain
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
 * Covers [com.calypsan.listenup.client.data.sync.domains.seriesDomain]: Room
 * write-through for SSE series events with enrichment copy-forward.
 */
class SeriesDomainTest :
    FunSpec({

        test("a Created event inserts the series row") {
            withHandler { handler, db ->
                handler
                    .onEvent(created(payload("s1", "The Stormlight Archive")), isOwnEcho = false)
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()
                val row = db.seriesDao().getById("s1")
                row shouldNotBe null
                row!!.name shouldBe "The Stormlight Archive"
                row.revision shouldBe 1L
            }
        }

        test("an Updated event preserves enrichment columns the wire does not carry") {
            withHandler { handler, db ->
                db.seriesDao().upsert(
                    SeriesEntity(
                        id = SeriesId("s1"),
                        name = "Old Name",
                        description = "An epic.",
                        asin = "B00ASIN",
                        coverPath = "/covers/s1.jpg",
                        createdAt = Timestamp(1L),
                        updatedAt = Timestamp(1L),
                    ),
                )
                handler.onEvent(
                    SyncEvent.Updated(
                        id = "s1",
                        revision = 4,
                        occurredAt = 200L,
                        clientOpId = null,
                        payload = payload("s1", "New Name", revision = 4),
                    ),
                    isOwnEcho = false,
                )
                val row = db.seriesDao().getById("s1")!!
                row.name shouldBe "New Name"
                row.revision shouldBe 4L
                row.description shouldBe "An epic."
                row.asin shouldBe "B00ASIN"
                row.coverPath shouldBe "/covers/s1.jpg"
            }
        }

        test("tombstoned row is EXCLUDED from digestRows — the digest counts live rows only (F1)") {
            withHandler { handler, db ->
                handler.onEvent(created(payload("s1", "The Stormlight Archive")), isOwnEcho = false)
                handler.onEvent(
                    SyncEvent.Deleted(id = "s1", revision = 2L, occurredAt = 500L),
                    isOwnEcho = false,
                )
                // observeById filters tombstones — invisible to reads
                db.seriesDao().observeById("s1").first() shouldBe null
                // and EXCLUDED from the digest — the digest counts live rows only, so a client that
                // tombstoned this row locally converges (F1). Deletions still reach clients via the
                // firehose and the tombstone-ungated access-filtered catch-up. Digest-drift reconciliation
                db.seriesDao().digestRows(Long.MAX_VALUE).map { it.id } shouldNotContain "s1"
            }
        }

        test("onCatchUpItem with isTombstone soft-deletes the series") {
            withHandler { handler, db ->
                handler.onEvent(created(payload("s1", "The Stormlight Archive")), isOwnEcho = false)
                handler
                    .onCatchUpItem(payload("s1", "The Stormlight Archive", deletedAt = 100L), isTombstone = true)
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()
                db.seriesDao().getById("s1")!!.deletedAt shouldBe 100L
            }
        }

        test("handler self-registers under domainName 'series'") {
            val registry = ClientSyncDomainRegistry()
            val db = createInMemoryTestDatabase()
            try {
                val handler = seriesDomain(db).toHandler(RoomTransactionRunner(db), registry)
                handler.domainName shouldBe "series"
                registry.lookup("series") shouldBe handler
            } finally {
                db.close()
            }
        }
    })

private fun withHandler(block: suspend (SyncDomainHandler<SeriesSyncPayload>, ListenUpDatabase) -> Unit) =
    runTest {
        val db = createInMemoryTestDatabase()
        try {
            block(seriesDomain(db).toHandler(RoomTransactionRunner(db), ClientSyncDomainRegistry()), db)
        } finally {
            db.close()
        }
    }

private fun created(p: SeriesSyncPayload) =
    SyncEvent.Created(
        id = p.id,
        revision = p.revision,
        occurredAt = p.updatedAt,
        clientOpId = null,
        payload = p,
    )

private fun payload(
    id: String,
    name: String,
    revision: Long = 1L,
    deletedAt: Long? = null,
) = SeriesSyncPayload(
    id = id,
    name = name,
    sortName = null,
    revision = revision,
    updatedAt = 100L,
    createdAt = 1L,
    deletedAt = deletedAt,
)
