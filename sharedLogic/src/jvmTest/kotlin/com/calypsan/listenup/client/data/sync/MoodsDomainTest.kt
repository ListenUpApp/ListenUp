package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.sync.Mood
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.sync.domains.moodsDomain
import com.calypsan.listenup.client.data.sync.domains.toHandler
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/**
 * Covers [com.calypsan.listenup.client.data.sync.domains.moodsDomain]: Room
 * write-through for SSE mood events.
 *
 * Mirrors [TagsDomainTest] — verifies payload → entity mapping across
 * Created / Updated / Deleted events and catch-up paging.
 */
class MoodsDomainTest :
    FunSpec({

        test("Created event inserts the mood row into Room") {
            withHandler { handler, db ->
                handler
                    .onEvent(created(moodPayload("m1", "Feel-Good", "feel-good")))
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()
                val row = db.moodDao().getById("m1")
                row shouldNotBe null
                row!!.name shouldBe "Feel-Good"
                row.slug shouldBe "feel-good"
                row.revision shouldBe 1L
                row.deletedAt shouldBe null
            }
        }

        test("Updated event overwrites the existing mood row") {
            withHandler { handler, db ->
                handler.onEvent(created(moodPayload("m1", "Feel-Good", "feel-good", revision = 1L)))
                handler.onEvent(
                    SyncEvent.Updated(
                        id = "m1",
                        revision = 2L,
                        occurredAt = 200L,
                        clientOpId = null,
                        payload = moodPayload("m1", "Uplifting", "feel-good", revision = 2L),
                    ),
                )
                val row = db.moodDao().getById("m1")!!
                row.name shouldBe "Uplifting"
                row.slug shouldBe "feel-good"
                row.revision shouldBe 2L
            }
        }

        test("Deleted event soft-deletes the mood row") {
            withHandler { handler, db ->
                handler.onEvent(created(moodPayload("m1", "Feel-Good", "feel-good")))
                handler
                    .onEvent(
                        SyncEvent.Deleted(id = "m1", revision = 2L, occurredAt = 500L),
                    ).shouldBeInstanceOf<AppResult.Success<Unit>>()
                db.moodDao().getById("m1") shouldBe null
            }
        }

        test("tombstoned row is EXCLUDED from digestRows — the digest counts live rows only (F1)") {
            withHandler { handler, db ->
                handler.onEvent(created(moodPayload("m1", "Feel-Good", "feel-good")))
                handler.onEvent(
                    SyncEvent.Deleted(id = "m1", revision = 2L, occurredAt = 500L),
                )
                db.moodDao().getById("m1") shouldBe null
                db.moodDao().digestRows(Long.MAX_VALUE).map { it.id } shouldNotContain "m1"
            }
        }

        test("onCatchUpItem live item upserts the mood row") {
            withHandler { handler, db ->
                handler
                    .onCatchUpItem(moodPayload("m1", "Tense", "tense"), isTombstone = false)
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()
                val row = db.moodDao().getById("m1")
                row shouldNotBe null
                row!!.name shouldBe "Tense"
            }
        }

        test("onCatchUpItem tombstone soft-deletes the mood row") {
            withHandler { handler, db ->
                handler.onCatchUpItem(moodPayload("m1", "Tense", "tense"), isTombstone = false)
                handler
                    .onCatchUpItem(
                        moodPayload("m1", "Tense", "tense", revision = 3L, deletedAt = 700L),
                        isTombstone = true,
                    ).shouldBeInstanceOf<AppResult.Success<Unit>>()
                db.moodDao().getById("m1") shouldBe null
            }
        }

        test("handler self-registers under domainName 'moods'") {
            val registry = ClientSyncDomainRegistry()
            val db = createInMemoryTestDatabase()
            try {
                val handler = moodsDomain(db).toHandler(RoomTransactionRunner(db), registry)
                handler.domainName shouldBe "moods"
                registry.lookup("moods") shouldBe handler
            } finally {
                db.close()
            }
        }
    })

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun withHandler(block: suspend (SyncDomainHandler<Mood>, ListenUpDatabase) -> Unit) =
    runTest {
        val db = createInMemoryTestDatabase()
        try {
            block(moodsDomain(db).toHandler(RoomTransactionRunner(db), ClientSyncDomainRegistry()), db)
        } finally {
            db.close()
        }
    }

private fun moodPayload(
    id: String,
    name: String,
    slug: String,
    revision: Long = 1L,
    deletedAt: Long? = null,
) = Mood(id = id, name = name, slug = slug, revision = revision, updatedAt = 100L, deletedAt = deletedAt)

private fun created(payload: Mood) =
    SyncEvent.Created(
        id = payload.id,
        revision = payload.revision,
        occurredAt = payload.updatedAt,
        clientOpId = null,
        payload = payload,
    )
