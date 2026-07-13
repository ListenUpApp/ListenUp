package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.sync.PlaybackPositionSyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.PlaybackPositionEntity
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.sync.domains.playbackPositionsDomain
import com.calypsan.listenup.client.data.sync.domains.toHandler
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

class PlaybackPositionsDomainTest :
    FunSpec({

        test("a Created event for a book with no local row inserts the position") {
            withHandler { handler, db ->
                handler
                    .onEvent(created(payload("pos-1", "book-1", positionMs = 10_000L)))
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()
                val row = db.playbackPositionDao().get(BookId("book-1"))
                row shouldNotBe null
                row!!.positionMs shouldBe 10_000L
                row.revision shouldBe 1L
            }
        }

        test("an Updated event for a book with no local row inserts the position") {
            withHandler { handler, db ->
                handler
                    .onEvent(updated(payload("pos-1", "book-1", positionMs = 20_000L, revision = 3L)))
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()
                val row = db.playbackPositionDao().get(BookId("book-1"))
                row shouldNotBe null
                row!!.positionMs shouldBe 20_000L
                row.revision shouldBe 3L
            }
        }

        test("event with older lastPlayedAt than local row is a no-op (lastPlayedAt-wins policy)") {
            withHandler { handler, db ->
                // Seed a local row with a fresh lastPlayedAt
                db.playbackPositionDao().save(
                    localRow(
                        bookId = "book-1",
                        positionMs = 99_000L,
                        lastPlayedAt = 2000L,
                        revision = 5L,
                    ),
                )

                // Apply an event whose lastPlayedAt is older — must be a no-op
                handler.onEvent(
                    updated(payload("pos-1", "book-1", positionMs = 1_000L, lastPlayedAt = 1000L, revision = 6L)),
                )

                val row = db.playbackPositionDao().get(BookId("book-1"))!!
                // Local positionMs unchanged — the stale server snapshot was rejected
                row.positionMs shouldBe 99_000L
            }
        }

        test("event with equal lastPlayedAt is also a no-op (stale echo guard)") {
            withHandler { handler, db ->
                db.playbackPositionDao().save(
                    localRow(
                        bookId = "book-1",
                        positionMs = 99_000L,
                        lastPlayedAt = 2000L,
                        revision = 5L,
                    ),
                )

                // lastPlayedAt equal — >= test makes this a no-op too (prevents echo flicker)
                handler.onEvent(
                    updated(payload("pos-1", "book-1", positionMs = 50_000L, lastPlayedAt = 2000L, revision = 6L)),
                )

                val row = db.playbackPositionDao().get(BookId("book-1"))!!
                row.positionMs shouldBe 99_000L
            }
        }

        test("event with newer lastPlayedAt than local row is applied") {
            withHandler { handler, db ->
                db.playbackPositionDao().save(
                    localRow(
                        bookId = "book-1",
                        positionMs = 10_000L,
                        lastPlayedAt = 1000L,
                        revision = 2L,
                    ),
                )

                handler.onEvent(
                    updated(payload("pos-1", "book-1", positionMs = 88_000L, lastPlayedAt = 5000L, revision = 7L)),
                )

                val row = db.playbackPositionDao().get(BookId("book-1"))!!
                row.positionMs shouldBe 88_000L
                row.lastPlayedAt shouldBe 5000L
                row.revision shouldBe 7L
            }
        }

        test("applied event preserves local-only columns from the existing row") {
            withHandler { handler, db ->
                // Seed a row with local-only columns set to non-default values
                db.playbackPositionDao().save(
                    localRow(
                        bookId = "book-1",
                        positionMs = 1_000L,
                        lastPlayedAt = 100L,
                        revision = 1L,
                        hasCustomSpeed = true,
                        syncedAt = 999L,
                        finishedAt = null,
                        startedAt = 500L,
                    ),
                )

                // Apply an event with a newer lastPlayedAt — sets position fields
                handler.onEvent(
                    updated(
                        payload(
                            "pos-1",
                            "book-1",
                            positionMs = 77_000L,
                            lastPlayedAt = 9000L,
                            revision = 10L,
                        ),
                    ),
                )

                val row = db.playbackPositionDao().get(BookId("book-1"))!!
                // Wire fields updated
                row.positionMs shouldBe 77_000L
                row.lastPlayedAt shouldBe 9000L
                row.revision shouldBe 10L
                // Local-only columns preserved
                row.hasCustomSpeed shouldBe true
                row.syncedAt shouldBe 999L
                row.startedAt shouldBe 500L
            }
        }

        test("onCatchUpItem with isTombstone soft-deletes the position") {
            withHandler { handler, db ->
                handler.onEvent(created(payload("pos-1", "book-1")))
                handler
                    .onCatchUpItem(
                        payload("pos-1", "book-1", deletedAt = 123L, revision = 9L),
                        isTombstone = true,
                    ).shouldBeInstanceOf<AppResult.Success<Unit>>()
                val row = db.playbackPositionDao().get(BookId("book-1")).shouldNotBeNull()
                row.deletedAt shouldBe 123L
            }
        }

        test("handler self-registers under domainName 'playback_positions'") {
            val registry = ClientSyncDomainRegistry()
            val db = createInMemoryTestDatabase()
            try {
                val handler = playbackPositionsDomain(db).toHandler(RoomTransactionRunner(db), registry)
                handler.domainName shouldBe "playback_positions"
                registry.lookup("playback_positions") shouldBe handler
            } finally {
                db.close()
            }
        }
    })

// ── Fixtures ─────────────────────────────────────────────────────────────────

private fun withHandler(block: suspend (SyncDomainHandler<PlaybackPositionSyncPayload>, ListenUpDatabase) -> Unit) =
    runTest {
        val db = createInMemoryTestDatabase()
        try {
            block(playbackPositionsDomain(db).toHandler(RoomTransactionRunner(db), ClientSyncDomainRegistry()), db)
        } finally {
            db.close()
        }
    }

private fun created(p: PlaybackPositionSyncPayload) =
    SyncEvent.Created(
        id = p.id,
        revision = p.revision,
        occurredAt = p.updatedAt,
        clientOpId = null,
        payload = p,
    )

private fun updated(p: PlaybackPositionSyncPayload) =
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
    positionMs: Long = 0L,
    lastPlayedAt: Long = 100L,
    revision: Long = 1L,
    finished: Boolean = false,
    playbackSpeed: Float = 1.0f,
    currentChapterId: String? = null,
    deletedAt: Long? = null,
) = PlaybackPositionSyncPayload(
    id = id,
    bookId = bookId,
    positionMs = positionMs,
    lastPlayedAt = lastPlayedAt,
    finished = finished,
    playbackSpeed = playbackSpeed,
    currentChapterId = currentChapterId,
    revision = revision,
    updatedAt = 200L,
    createdAt = 50L,
    deletedAt = deletedAt,
)

private fun localRow(
    bookId: String,
    positionMs: Long = 0L,
    lastPlayedAt: Long? = null,
    revision: Long = 0L,
    hasCustomSpeed: Boolean = false,
    syncedAt: Long? = null,
    finishedAt: Long? = null,
    startedAt: Long? = null,
) = PlaybackPositionEntity(
    bookId = BookId(bookId),
    positionMs = positionMs,
    playbackSpeed = 1.0f,
    hasCustomSpeed = hasCustomSpeed,
    updatedAt = 100L,
    syncedAt = syncedAt,
    lastPlayedAt = lastPlayedAt,
    isFinished = false,
    finishedAt = finishedAt,
    startedAt = startedAt,
    revision = revision,
    deletedAt = null,
)
