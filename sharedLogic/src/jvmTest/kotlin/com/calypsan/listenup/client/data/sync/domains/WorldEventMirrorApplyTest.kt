package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.api.core.MentionTokens
import com.calypsan.listenup.api.sync.WorldEventSource
import com.calypsan.listenup.api.sync.WorldEventSyncPayload
import com.calypsan.listenup.api.sync.WorldEventType
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/**
 * Inbound-apply invariants for [WorldEventMirrorApply]: a [WorldEventSyncPayload] upserts the
 * event root row AND replaces its mention junction wholesale (recomputed from `text` +
 * `subjectEntityId` + `objectEntityId`, never trusted from the payload's own `mentionIds` — see
 * [worldEventMentionIds]'s KDoc), and a tombstone removes the event from the live set and clears
 * its mention rows. Mirrors [EntityMirrorApplyTest], plus the mention-junction coverage a
 * plain-aggregate [com.calypsan.listenup.api.sync.EntitySyncPayload] apply has no equivalent for.
 */
class WorldEventMirrorApplyTest :
    FunSpec({
        test("upsert inserts the event row, series-homed") {
            runTest {
                val db = createInMemoryTestDatabase()
                val apply = WorldEventMirrorApply(db)

                apply.upsert(eventPayload(id = "ev1", homeSeriesId = "series1", homeBookId = null))

                val row = db.worldEventDao().getById("ev1")
                row.shouldNotBeNull()
                row.text shouldBe "Kaladin enters the Shattered Plains."
                row.type shouldBe WorldEventType.NOTE
                row.homeSeriesId shouldBe "series1"
                row.homeBookId.shouldBeNull()
                db.close()
            }
        }

        test("upsert inserts the event row, book-homed") {
            runTest {
                val db = createInMemoryTestDatabase()
                val apply = WorldEventMirrorApply(db)

                apply.upsert(eventPayload(id = "ev1", homeSeriesId = null, homeBookId = "book1"))

                val row = db.worldEventDao().getById("ev1")
                row.shouldNotBeNull()
                row.homeSeriesId.shouldBeNull()
                row.homeBookId shouldBe "book1"
                db.close()
            }
        }

        test("upsert recomputes mentions from text + subject/object rather than trusting the payload's mentionIds") {
            runTest {
                val db = createInMemoryTestDatabase()
                val apply = WorldEventMirrorApply(db)
                val text = "${MentionTokens.token("kaladin", "Kaladin")} enters."

                apply.upsert(
                    eventPayload(
                        id = "ev1",
                        text = text,
                        subjectEntityId = "kaladin",
                        objectEntityId = "syl",
                        // Deliberately wrong/stale mentionIds — the apply must ignore this and
                        // recompute from text/subject/object instead. "kaladin" appears both as the
                        // inline token and subjectEntityId — the recompute is a union, so it still
                        // collapses to one mention row for kaladin.
                        mentionIds = listOf("someone-else"),
                    ),
                )

                val mentionedIds =
                    db
                        .worldEventDao()
                        .mentionsForEventRaw("ev1")
                        .map { it.entityId }
                        .toSet()
                mentionedIds shouldBe setOf("kaladin", "syl")
                db.close()
            }
        }

        test("re-upsert replaces the mention set wholesale, not merges it") {
            runTest {
                val db = createInMemoryTestDatabase()
                val apply = WorldEventMirrorApply(db)
                apply.upsert(eventPayload(id = "ev1", revision = 1L, subjectEntityId = "kaladin"))

                apply.upsert(eventPayload(id = "ev1", revision = 2L, subjectEntityId = "shallan"))

                val row = db.worldEventDao().getById("ev1")
                row.shouldNotBeNull()
                row.subjectEntityId shouldBe "shallan"
                db.worldEventDao().mentionsForEventRaw("ev1").map { it.entityId } shouldBe listOf("shallan")
                db.close()
            }
        }

        test("tombstoneFromItem soft-deletes the event — it drops out of the live set — and clears its mentions") {
            runTest {
                val db = createInMemoryTestDatabase()
                val apply = WorldEventMirrorApply(db)
                apply.upsert(eventPayload(id = "ev1", subjectEntityId = "kaladin"))

                apply.tombstoneFromItem(eventPayload(id = "ev1", revision = 2L, deletedAt = 500L))

                db.worldEventDao().getById("ev1").shouldBeNull()
                db.worldEventDao().mentionsForEventRaw("ev1").shouldBeEmpty()
                db.close()
            }
        }

        test("tombstoneById soft-deletes by id directly (the SSE Deleted-frame path) and clears its mentions") {
            runTest {
                val db = createInMemoryTestDatabase()
                val apply = WorldEventMirrorApply(db)
                apply.upsert(eventPayload(id = "ev1", subjectEntityId = "kaladin"))

                apply.tombstoneById(id = "ev1", deletedAt = 500L, revision = 2L)

                db.worldEventDao().getById("ev1").shouldBeNull()
                db.worldEventDao().mentionsForEventRaw("ev1").shouldBeEmpty()
                db.close()
            }
        }
    })

private fun eventPayload(
    id: String,
    text: String = "Kaladin enters the Shattered Plains.",
    type: WorldEventType = WorldEventType.NOTE,
    homeSeriesId: String? = "series1",
    homeBookId: String? = null,
    subjectEntityId: String? = null,
    objectEntityId: String? = null,
    mentionIds: List<String> = emptyList(),
    revision: Long = 0L,
    deletedAt: Long? = null,
) = WorldEventSyncPayload(
    id = id,
    homeSeriesId = homeSeriesId,
    homeBookId = homeBookId,
    type = type,
    text = text,
    subjectEntityId = subjectEntityId,
    objectEntityId = objectEntityId,
    mentionIds = mentionIds,
    source = WorldEventSource.MANUAL,
    revision = revision,
    updatedAt = 0L,
    createdAt = 0L,
    deletedAt = deletedAt,
)
