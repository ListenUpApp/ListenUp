package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.sync.ListeningEventSyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.sync.handlers.ListeningEventSyncDomainHandler
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

class ListeningEventSyncDomainHandlerTest :
    FunSpec({

        test("Created event for a new id inserts the row with all wire fields") {
            withHandler { handler, db ->
                handler
                    .onEvent(created(payload("ev-1", "book-1")), isOwnEcho = false)
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()

                val row = db.listeningEventDao().getById("ev-1")
                row shouldNotBe null
                row!!.id shouldBe "ev-1"
                row.bookId shouldBe "book-1"
                row.startPositionMs shouldBe 0L
                row.endPositionMs shouldBe 60_000L
                row.startedAt shouldBe 1_000L
                row.endedAt shouldBe 61_000L
                row.playbackSpeed shouldBe 1.0f
                row.tz shouldBe "UTC"
                row.deviceLabel shouldBe null
                row.revision shouldBe 1L
                row.deletedAt shouldBe null
            }
        }

        test("Created event for an existing id is a no-op (append-only)") {
            withHandler { handler, db ->
                handler.onEvent(created(payload("ev-1", "book-1", startPositionMs = 0L, endPositionMs = 60_000L)), isOwnEcho = false)
                // Second event same id, different positions — must be ignored
                handler.onEvent(created(payload("ev-1", "book-1", startPositionMs = 999L, endPositionMs = 999_000L)), isOwnEcho = false)

                val row = db.listeningEventDao().getById("ev-1").shouldNotBeNull()
                // Original positions preserved, not overwritten by the second event
                row.startPositionMs shouldBe 0L
                row.endPositionMs shouldBe 60_000L
            }
        }

        test("Updated event for an existing id is also a no-op (append-only)") {
            withHandler { handler, db ->
                handler.onEvent(created(payload("ev-2", "book-1", startPositionMs = 100L, endPositionMs = 200L)), isOwnEcho = false)
                handler.onEvent(updated(payload("ev-2", "book-1", startPositionMs = 500L, endPositionMs = 600L, revision = 2L)), isOwnEcho = false)

                val row = db.listeningEventDao().getById("ev-2").shouldNotBeNull()
                row.startPositionMs shouldBe 100L
                row.endPositionMs shouldBe 200L
            }
        }

        test("onCatchUpItem for a row not in Room inserts it") {
            withHandler { handler, db ->
                handler
                    .onCatchUpItem(
                        payload("ev-3", "book-2", startPositionMs = 5_000L, endPositionMs = 30_000L),
                        isTombstone = false,
                    ).shouldBeInstanceOf<AppResult.Success<Unit>>()

                val row = db.listeningEventDao().getById("ev-3").shouldNotBeNull()
                row.startPositionMs shouldBe 5_000L
                row.endPositionMs shouldBe 30_000L
            }
        }

        test("onCatchUpItem for an existing row is a no-op (append-only)") {
            withHandler { handler, db ->
                handler.onEvent(created(payload("ev-4", "book-3", startPositionMs = 10L, endPositionMs = 20L)), isOwnEcho = false)
                handler.onCatchUpItem(payload("ev-4", "book-3", startPositionMs = 999L, endPositionMs = 9999L), isTombstone = false)

                val row = db.listeningEventDao().getById("ev-4").shouldNotBeNull()
                row.startPositionMs shouldBe 10L
            }
        }

        test("onCatchUpItem with isTombstone soft-deletes the row") {
            withHandler { handler, db ->
                handler.onEvent(created(payload("ev-5", "book-1")), isOwnEcho = false)
                handler
                    .onCatchUpItem(
                        payload("ev-5", "book-1", deletedAt = 555L, revision = 9L),
                        isTombstone = true,
                    ).shouldBeInstanceOf<AppResult.Success<Unit>>()

                val row = db.listeningEventDao().getById("ev-5").shouldNotBeNull()
                row.deletedAt shouldBe 555L
                row.revision shouldBe 9L
            }
        }

        test("handler self-registers under domainName 'listening_events'") {
            val registry = ClientSyncDomainRegistry()
            val db = createInMemoryTestDatabase()
            try {
                val handler = ListeningEventSyncDomainHandler(db, RoomTransactionRunner(db), registry)
                handler.domainName shouldBe "listening_events"
                registry.lookup("listening_events") shouldBe handler
            } finally {
                db.close()
            }
        }
    })

// ── Fixtures ─────────────────────────────────────────────────────────────────

private fun withHandler(block: suspend (ListeningEventSyncDomainHandler, ListenUpDatabase) -> Unit) =
    runTest {
        val db = createInMemoryTestDatabase()
        try {
            block(ListeningEventSyncDomainHandler(db, RoomTransactionRunner(db), ClientSyncDomainRegistry()), db)
        } finally {
            db.close()
        }
    }

private fun created(p: ListeningEventSyncPayload) =
    SyncEvent.Created(
        id = p.id,
        revision = p.revision,
        occurredAt = p.updatedAt,
        clientOpId = null,
        payload = p,
    )

private fun updated(p: ListeningEventSyncPayload) =
    SyncEvent.Updated(
        id = p.id,
        revision = p.revision,
        occurredAt = p.updatedAt,
        clientOpId = null,
        payload = p,
    )

private fun payload(
    id: String,
    bookId: String,
    startPositionMs: Long = 0L,
    endPositionMs: Long = 60_000L,
    startedAt: Long = 1_000L,
    endedAt: Long = 61_000L,
    playbackSpeed: Float = 1.0f,
    tz: String = "UTC",
    deviceLabel: String? = null,
    revision: Long = 1L,
    deletedAt: Long? = null,
) = ListeningEventSyncPayload(
    id = id,
    bookId = bookId,
    startPositionMs = startPositionMs,
    endPositionMs = endPositionMs,
    startedAt = startedAt,
    endedAt = endedAt,
    playbackSpeed = playbackSpeed,
    tz = tz,
    deviceLabel = deviceLabel,
    revision = revision,
    updatedAt = 200L,
    createdAt = 50L,
    deletedAt = deletedAt,
)
