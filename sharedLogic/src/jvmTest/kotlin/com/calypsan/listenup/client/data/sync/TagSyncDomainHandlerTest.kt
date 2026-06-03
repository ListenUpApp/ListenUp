package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/**
 * Covers [TagSyncDomainHandler]: Room write-through for SSE tag events (Room v22).
 */
class TagSyncDomainHandlerTest :
    FunSpec({

        test("Created event inserts the tag row into Room") {
            withHandler { handler, db ->
                handler
                    .onEvent(created(tagPayload("t1", "Sci-Fi", "sci-fi")), isOwnEcho = false)
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()
                val row = db.tagDao().getById("t1")
                row shouldNotBe null
                row!!.name shouldBe "Sci-Fi"
                row.slug shouldBe "sci-fi"
                row.revision shouldBe 1L
                row.deletedAt shouldBe null
            }
        }

        test("Updated event overwrites the existing tag row") {
            withHandler { handler, db ->
                handler.onEvent(created(tagPayload("t1", "Sci-Fi", "sci-fi", revision = 1L)), isOwnEcho = false)
                handler.onEvent(
                    SyncEvent.Updated(
                        id = "t1",
                        revision = 2L,
                        occurredAt = 200L,
                        clientOpId = null,
                        payload = tagPayload("t1", "Science Fiction", "sci-fi", revision = 2L),
                    ),
                    isOwnEcho = false,
                )
                val row = db.tagDao().getById("t1")!!
                row.name shouldBe "Science Fiction"
                row.revision shouldBe 2L
            }
        }

        test("Deleted event soft-deletes the tag row") {
            withHandler { handler, db ->
                handler.onEvent(created(tagPayload("t1", "Sci-Fi", "sci-fi")), isOwnEcho = false)
                handler
                    .onEvent(
                        SyncEvent.Deleted(id = "t1", revision = 2L, occurredAt = 500L),
                        isOwnEcho = false,
                    ).shouldBeInstanceOf<AppResult.Success<Unit>>()
                // getById filters tombstones — should return null after soft-delete
                db.tagDao().getById("t1") shouldBe null
            }
        }

        test("onCatchUpItem live item upserts the tag row") {
            withHandler { handler, db ->
                handler
                    .onCatchUpItem(tagPayload("t1", "Fantasy", "fantasy"), isTombstone = false)
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()
                val row = db.tagDao().getById("t1")
                row shouldNotBe null
                row!!.name shouldBe "Fantasy"
            }
        }

        test("onCatchUpItem tombstone soft-deletes the tag row") {
            withHandler { handler, db ->
                handler.onCatchUpItem(tagPayload("t1", "Fantasy", "fantasy"), isTombstone = false)
                handler
                    .onCatchUpItem(
                        tagPayload("t1", "Fantasy", "fantasy", revision = 3L, deletedAt = 700L),
                        isTombstone = true,
                    ).shouldBeInstanceOf<AppResult.Success<Unit>>()
                db.tagDao().getById("t1") shouldBe null
            }
        }

        test("handler self-registers under domainName 'tags'") {
            val registry = ClientSyncDomainRegistry()
            val db = createInMemoryTestDatabase()
            try {
                val handler = TagSyncDomainHandler(db, RoomTransactionRunner(db), registry)
                handler.domainName shouldBe "tags"
                registry.lookup("tags") shouldBe handler
            } finally {
                db.close()
            }
        }
    })

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun withHandler(block: suspend (TagSyncDomainHandler, ListenUpDatabase) -> Unit) =
    runTest {
        val db = createInMemoryTestDatabase()
        try {
            block(TagSyncDomainHandler(db, RoomTransactionRunner(db), ClientSyncDomainRegistry()), db)
        } finally {
            db.close()
        }
    }

private fun tagPayload(
    id: String,
    name: String,
    slug: String,
    revision: Long = 1L,
    deletedAt: Long? = null,
) = Tag(id = id, name = name, slug = slug, revision = revision, updatedAt = 100L, deletedAt = deletedAt)

private fun created(payload: Tag) =
    SyncEvent.Created(
        id = payload.id,
        revision = payload.revision,
        occurredAt = payload.updatedAt,
        clientOpId = null,
        payload = payload,
    )
