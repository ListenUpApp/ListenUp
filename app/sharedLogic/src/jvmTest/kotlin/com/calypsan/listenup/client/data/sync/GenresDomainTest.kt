package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.GenreSyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.sync.domains.genresDomain
import com.calypsan.listenup.client.data.sync.domains.toHandler
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/**
 * Covers [com.calypsan.listenup.client.data.sync.domains.genresDomain]: Room
 * write-through for SSE genre events (hierarchy fields included).
 */
class GenresDomainTest :
    FunSpec({

        test("Created event inserts the genre row with hierarchy fields") {
            withHandler { handler, db ->
                handler
                    .onEvent(created(genrePayload("g1", "Fantasy", "fantasy")))
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()
                val row = db.genreDao().getById("g1")
                row shouldNotBe null
                row!!.name shouldBe "Fantasy"
                row.slug shouldBe "fantasy"
                row.path shouldBe "fantasy"
                row.depth shouldBe 0
                row.revision shouldBe 1L
                row.deletedAt shouldBe null
            }
        }

        test("Updated event overwrites the existing genre row") {
            withHandler { handler, db ->
                handler.onEvent(created(genrePayload("g1", "Fantasy", "fantasy")))
                handler.onEvent(
                    SyncEvent.Updated(
                        id = "g1",
                        revision = 2L,
                        occurredAt = 200L,
                        clientOpId = null,
                        payload = genrePayload("g1", "High Fantasy", "fantasy", revision = 2L),
                    ),
                )
                val row = db.genreDao().getById("g1")!!
                row.name shouldBe "High Fantasy"
                row.revision shouldBe 2L
            }
        }

        test("Deleted event soft-deletes the genre row") {
            withHandler { handler, db ->
                handler.onEvent(created(genrePayload("g1", "Fantasy", "fantasy")))
                handler
                    .onEvent(
                        SyncEvent.Deleted(id = "g1", revision = 2L, occurredAt = 500L),
                    ).shouldBeInstanceOf<AppResult.Success<Unit>>()
                db.genreDao().getById("g1") shouldBe null
            }
        }

        test("tombstoned row is EXCLUDED from digestRows — the digest counts live rows only (F1)") {
            withHandler { handler, db ->
                handler.onEvent(created(genrePayload("g1", "Fantasy", "fantasy")))
                handler.onEvent(
                    SyncEvent.Deleted(id = "g1", revision = 2L, occurredAt = 500L),
                )
                db.genreDao().getById("g1") shouldBe null
                db.genreDao().digestRows(Long.MAX_VALUE).map { it.id } shouldNotContain "g1"
            }
        }

        test("onCatchUpItem live item upserts; tombstone soft-deletes") {
            withHandler { handler, db ->
                handler
                    .onCatchUpItem(genrePayload("g1", "Mystery", "mystery"), isTombstone = false)
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()
                db.genreDao().getById("g1") shouldNotBe null
                handler
                    .onCatchUpItem(
                        genrePayload("g1", "Mystery", "mystery", revision = 3L, deletedAt = 700L),
                        isTombstone = true,
                    ).shouldBeInstanceOf<AppResult.Success<Unit>>()
                db.genreDao().getById("g1") shouldBe null
            }
        }

        test("handler self-registers under domainName 'genres'") {
            val registry = ClientSyncDomainRegistry()
            val db = createInMemoryTestDatabase()
            try {
                val handler = genresDomain(db).toHandler(RoomTransactionRunner(db), registry)
                handler.domainName shouldBe "genres"
                registry.lookup("genres") shouldBe handler
            } finally {
                db.close()
            }
        }
    })

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun withHandler(block: suspend (SyncDomainHandler<GenreSyncPayload>, ListenUpDatabase) -> Unit) =
    runTest {
        val db = createInMemoryTestDatabase()
        try {
            block(genresDomain(db).toHandler(RoomTransactionRunner(db), ClientSyncDomainRegistry()), db)
        } finally {
            db.close()
        }
    }

private fun genrePayload(
    id: String,
    name: String,
    slug: String,
    revision: Long = 1L,
    deletedAt: Long? = null,
) = GenreSyncPayload(
    id = id,
    name = name,
    slug = slug,
    path = slug,
    parentId = null,
    depth = 0,
    sortOrder = 0,
    revision = revision,
    deletedAt = deletedAt,
    createdAt = 100L,
    updatedAt = 100L,
)

private fun created(payload: GenreSyncPayload) =
    SyncEvent.Created(
        id = payload.id,
        revision = payload.revision,
        occurredAt = payload.updatedAt,
        clientOpId = null,
        payload = payload,
    )
