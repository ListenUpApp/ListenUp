package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.api.sync.EntityKind
import com.calypsan.listenup.api.sync.EntitySyncPayload
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/**
 * Inbound-apply invariants for [EntityMirrorApply]: an [EntitySyncPayload] upserts the entity
 * row (a plain single-row aggregate, no child collection to replace), and a tombstone removes
 * it from the live set.
 */
class EntityMirrorApplyTest :
    FunSpec({
        test("upsert inserts the entity row, series-homed") {
            runTest {
                val db = createInMemoryTestDatabase()
                val apply = EntityMirrorApply(db)

                apply.upsert(entityPayload(id = "e1", homeSeriesId = "series1", homeBookId = null))

                val row = db.entityDao().getById("e1")
                row.shouldNotBeNull()
                row.name shouldBe "Kaladin"
                row.kind shouldBe EntityKind.CHARACTER
                row.homeSeriesId shouldBe "series1"
                row.homeBookId.shouldBeNull()
                db.close()
            }
        }

        test("upsert inserts the entity row, book-homed") {
            runTest {
                val db = createInMemoryTestDatabase()
                val apply = EntityMirrorApply(db)

                apply.upsert(entityPayload(id = "e1", homeSeriesId = null, homeBookId = "book1"))

                val row = db.entityDao().getById("e1")
                row.shouldNotBeNull()
                row.homeSeriesId.shouldBeNull()
                row.homeBookId shouldBe "book1"
                db.close()
            }
        }

        test("re-upsert with a changed name replaces the prior row, not merges it") {
            runTest {
                val db = createInMemoryTestDatabase()
                val apply = EntityMirrorApply(db)
                apply.upsert(entityPayload(id = "e1", revision = 1L, name = "Old Name"))

                apply.upsert(entityPayload(id = "e1", revision = 2L, name = "New Name"))

                val row = db.entityDao().getById("e1")
                row.shouldNotBeNull()
                row.name shouldBe "New Name"
                row.revision shouldBe 2L
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
    name: String = "Kaladin",
    homeSeriesId: String? = "series1",
    homeBookId: String? = null,
    revision: Long = 0L,
    deletedAt: Long? = null,
) = EntitySyncPayload(
    id = id,
    kind = EntityKind.CHARACTER,
    name = name,
    homeSeriesId = homeSeriesId,
    homeBookId = homeBookId,
    revision = revision,
    updatedAt = 0L,
    createdAt = 0L,
    deletedAt = deletedAt,
)
