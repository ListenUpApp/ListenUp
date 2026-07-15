package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.api.sync.BioEntryMode
import com.calypsan.listenup.api.sync.BioEntryPayload
import com.calypsan.listenup.api.sync.EntityKind
import com.calypsan.listenup.api.sync.EntitySyncPayload
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/**
 * Inbound-apply invariants for [EntityMirrorApply]: an [EntitySyncPayload] upserts the entity
 * row and fully replaces its bio-entry child set (delete-then-insert), and a tombstone removes
 * it from the live set — the same whole-aggregate-replace contract [BookMirrorApply] enforces
 * for `chapters`.
 */
class EntityMirrorApplyTest :
    FunSpec({
        test("upsert inserts the entity row and its bio entries") {
            runTest {
                val db = createInMemoryTestDatabase()
                val apply = EntityMirrorApply(db)

                apply.upsert(
                    entityPayload(
                        id = "e1",
                        bioEntries = listOf(bioEntryPayload(id = "b1", text = "A soldier.", sortKey = 0)),
                    ),
                )

                val row = db.entityDao().getById("e1")
                row.shouldNotBeNull()
                row.name shouldBe "Kaladin"
                row.kind shouldBe EntityKind.CHARACTER
                row.homeSeriesId shouldBe "series1"
                val entries = db.bioEntryDao().getForEntity("e1")
                entries.map { it.id to it.text } shouldBe listOf("b1" to "A soldier.")
                db.close()
            }
        }

        test("re-upsert with a different bio-entry set fully replaces the prior set, not merges it") {
            runTest {
                val db = createInMemoryTestDatabase()
                val apply = EntityMirrorApply(db)
                apply.upsert(
                    entityPayload(
                        id = "e1",
                        revision = 1L,
                        bioEntries =
                            listOf(
                                bioEntryPayload(id = "b1", text = "First.", sortKey = 0),
                                bioEntryPayload(id = "b2", text = "Second.", sortKey = 1),
                            ),
                    ),
                )

                apply.upsert(
                    entityPayload(
                        id = "e1",
                        revision = 2L,
                        bioEntries = listOf(bioEntryPayload(id = "b3", text = "Only this now.", sortKey = 0)),
                    ),
                )

                val entries = db.bioEntryDao().getForEntity("e1")
                entries.map { it.id } shouldBe listOf("b3")
                db.close()
            }
        }

        test("re-upsert with an empty bio-entry set clears the prior entries") {
            runTest {
                val db = createInMemoryTestDatabase()
                val apply = EntityMirrorApply(db)
                apply.upsert(
                    entityPayload(id = "e1", bioEntries = listOf(bioEntryPayload(id = "b1", text = "Gone soon.", sortKey = 0))),
                )

                apply.upsert(entityPayload(id = "e1", revision = 2L, bioEntries = emptyList()))

                db.bioEntryDao().getForEntity("e1").shouldBeEmpty()
                db.close()
            }
        }

        test("tombstoneFromItem soft-deletes the entity — it drops out of the live set") {
            runTest {
                val db = createInMemoryTestDatabase()
                val apply = EntityMirrorApply(db)
                apply.upsert(entityPayload(id = "e1"))

                apply.tombstoneFromItem(entityPayload(id = "e1", revision = 2L, deletedAt = 500L))

                db.entityDao().getById("e1").shouldBeNull()
                db.close()
            }
        }

        test("tombstoneById soft-deletes by id directly (the SSE Deleted-frame path)") {
            runTest {
                val db = createInMemoryTestDatabase()
                val apply = EntityMirrorApply(db)
                apply.upsert(entityPayload(id = "e1"))

                apply.tombstoneById(id = "e1", deletedAt = 500L, revision = 2L)

                db.entityDao().getById("e1").shouldBeNull()
                db.close()
            }
        }
    })

private fun entityPayload(
    id: String,
    revision: Long = 0L,
    deletedAt: Long? = null,
    bioEntries: List<BioEntryPayload> = emptyList(),
) = EntitySyncPayload(
    id = id,
    kind = EntityKind.CHARACTER,
    name = "Kaladin",
    homeSeriesId = "series1",
    bioEntries = bioEntries,
    revision = revision,
    updatedAt = 0L,
    createdAt = 0L,
    deletedAt = deletedAt,
)

private fun bioEntryPayload(
    id: String,
    text: String,
    sortKey: Int,
) = BioEntryPayload(id = id, mode = BioEntryMode.APPEND, text = text, sortKey = sortKey)
