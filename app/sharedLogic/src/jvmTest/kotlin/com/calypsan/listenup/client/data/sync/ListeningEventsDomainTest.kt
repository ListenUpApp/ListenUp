package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.sync.ListeningEventSyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.sync.domains.listeningEventsDomain
import com.calypsan.listenup.client.data.sync.domains.toHandler
import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.client.test.fake.FakeAuthSession
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class ListeningEventsDomainTest :
    FunSpec({

        test("Created event for a new id inserts the row with all wire fields") {
            withHandler { handler, db ->
                handler
                    .onEvent(created(payload("ev-1", "book-1")))
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

        test("Created event is stamped with the signed-in user's id so cross-device events count") {
            withHandler(userId = "u1") { handler, db ->
                handler.onEvent(created(payload("ev-x", "book-1")))

                // The wire payload omits userId; the handler must stamp the current user's id,
                // otherwise the user-scoped stats query (WHERE userId = :userId) excludes it.
                db
                    .listeningEventDao()
                    .getById("ev-x")
                    .shouldNotBeNull()
                    .userId shouldBe "u1"
            }
        }

        test("onCatchUpItem also stamps the signed-in user's id") {
            withHandler(userId = "u9") { handler, db ->
                handler.onCatchUpItem(payload("ev-y", "book-2"), isTombstone = false)

                db
                    .listeningEventDao()
                    .getById("ev-y")
                    .shouldNotBeNull()
                    .userId shouldBe "u9"
            }
        }

        test("Created event for an existing id is a no-op (append-only)") {
            withHandler { handler, db ->
                handler.onEvent(created(payload("ev-1", "book-1", startPositionMs = 0L, endPositionMs = 60_000L)))
                // Second event same id, different positions — must be ignored
                handler.onEvent(created(payload("ev-1", "book-1", startPositionMs = 999L, endPositionMs = 999_000L)))

                val row = db.listeningEventDao().getById("ev-1").shouldNotBeNull()
                // Original positions preserved, not overwritten by the second event
                row.startPositionMs shouldBe 0L
                row.endPositionMs shouldBe 60_000L
            }
        }

        test("Updated event for an existing id is also a no-op (append-only)") {
            withHandler { handler, db ->
                handler.onEvent(created(payload("ev-2", "book-1", startPositionMs = 100L, endPositionMs = 200L)))
                handler.onEvent(
                    updated(payload("ev-2", "book-1", startPositionMs = 500L, endPositionMs = 600L, revision = 2L)),
                )

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
                handler.onEvent(created(payload("ev-4", "book-3", startPositionMs = 10L, endPositionMs = 20L)))
                handler.onCatchUpItem(payload("ev-4", "book-3", startPositionMs = 999L, endPositionMs = 9999L), isTombstone = false)

                val row = db.listeningEventDao().getById("ev-4").shouldNotBeNull()
                row.startPositionMs shouldBe 10L
            }
        }

        test("onCatchUpItem with isTombstone soft-deletes the row") {
            withHandler { handler, db ->
                handler.onEvent(created(payload("ev-5", "book-1")))
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

        test("re-delivered LIVE event un-tombstones after a catch-up tombstone (AppendOnlyMirrorApply.restore through Room)") {
            // The stranded-tombstone healer: a row soft-deleted by a defensive catch-up tombstone,
            // then re-delivered LIVE, must clear deletedAt and align its revision — otherwise the
            // (id, revision) digests agree and no reconcile can ever heal it.
            withHandler(userId = "u1") { handler, db ->
                handler.onCatchUpItem(payload("e1", "book-1"), isTombstone = false)
                val inserted = db.listeningEventDao().getById("e1").shouldNotBeNull()
                inserted.deletedAt shouldBe null
                inserted.userId shouldBe "u1"

                handler.onCatchUpItem(payload("e1", "book-1", deletedAt = 500L, revision = 2L), isTombstone = true)
                db.listeningEventDao().getById("e1")!!.deletedAt shouldNotBe null

                handler.onCatchUpItem(payload("e1", "book-1", revision = 3L), isTombstone = false)
                val restored = db.listeningEventDao().getById("e1")!!
                restored.deletedAt shouldBe null
                restored.revision shouldBe 3L
            }
        }

        test("tombstoned row is EXCLUDED from digestRows — the digest counts live rows only (F1)") {
            withHandler { handler, db ->
                handler.onEvent(created(payload("ev-6", "book-1")))
                handler.onCatchUpItem(
                    payload("ev-6", "book-1", deletedAt = 555L, revision = 9L),
                    isTombstone = true,
                )
                // observeEventsForBook filters tombstones — invisible to reads
                db
                    .listeningEventDao()
                    .observeEventsForBook("book-1")
                    .first()
                    .none { it.id == "ev-6" } shouldBe true
                // and EXCLUDED from the digest — the digest counts live rows only, so a client that
                // tombstoned this row locally converges (F1). Deletions still reach clients via the
                // firehose and the tombstone-ungated access-filtered catch-up. Digest-drift reconciliation
                db.listeningEventDao().digestRows(Long.MAX_VALUE).map { it.id } shouldNotContain "ev-6"
            }
        }

        // ── Regression: getUserId() must be used, not authState snapshot ────────────────

        test("synced event is stamped with getUserId(), not blank, while authState is Initializing") {
            // authState is still Initializing (startup race), but getUserId() has the persisted id.
            // The handler must stamp getUserId() so the user-scoped stats query counts the row.
            val auth = FakeAuthSession(userId = "user-123", authState = AuthState.Initializing)
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    val handler =
                        listeningEventsDomain(db, auth)
                            .toHandler(RoomTransactionRunner(db), ClientSyncDomainRegistry())
                    handler.onCatchUpItem(payload("e1", "book-1"), isTombstone = false)
                    db
                        .listeningEventDao()
                        .getById("e1")
                        .shouldNotBeNull()
                        .userId shouldBe "user-123"
                } finally {
                    db.close()
                }
            }
        }

        test("synced event is skipped (not stamped blank) when getUserId() is null") {
            // getUserId() returns null → signed out or storage unreadable; must not poison the DB.
            val auth = FakeAuthSession(userId = null, authState = AuthState.Initializing)
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    val handler =
                        listeningEventsDomain(db, auth)
                            .toHandler(RoomTransactionRunner(db), ClientSyncDomainRegistry())
                    handler.onCatchUpItem(payload("e2", "book-1"), isTombstone = false)
                    db.listeningEventDao().getById("e2").shouldBeNull()
                } finally {
                    db.close()
                }
            }
        }

        test("handler self-registers under domainName 'listening_events'") {
            val registry = ClientSyncDomainRegistry()
            val db = createInMemoryTestDatabase()
            try {
                val handler =
                    listeningEventsDomain(db, FakeAuthSession("u1"))
                        .toHandler(RoomTransactionRunner(db), registry)
                handler.domainName shouldBe "listening_events"
                registry.lookup("listening_events") shouldBe handler
            } finally {
                db.close()
            }
        }
    })

// ── Fixtures ─────────────────────────────────────────────────────────────────

private fun withHandler(
    userId: String = "u1",
    block: suspend (SyncDomainHandler<ListeningEventSyncPayload>, ListenUpDatabase) -> Unit,
) = runTest {
    val db = createInMemoryTestDatabase()
    try {
        val handler =
            listeningEventsDomain(db, FakeAuthSession(userId))
                .toHandler(RoomTransactionRunner(db), ClientSyncDomainRegistry())
        block(handler, db)
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
