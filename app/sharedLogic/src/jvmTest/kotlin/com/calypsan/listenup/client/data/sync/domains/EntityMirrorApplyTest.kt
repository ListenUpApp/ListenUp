package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.api.sync.EntityKind
import com.calypsan.listenup.api.sync.EntitySyncPayload
import com.calypsan.listenup.client.data.local.db.BookEntity
import com.calypsan.listenup.client.data.local.db.BookSeriesCrossRef
import com.calypsan.listenup.client.data.local.db.EntityEntity
import com.calypsan.listenup.client.data.local.db.SeriesEntity
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.Timestamp
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

private fun payload(
    id: String,
    kind: EntityKind = EntityKind.CHARACTER,
    homeBookId: String? = "b1",
    revision: Long = 5,
    deletedAt: Long? = null,
) = EntitySyncPayload(
    id = id,
    kind = kind,
    name = "Name $id",
    homeBookId = homeBookId,
    revision = revision,
    updatedAt = 10,
    createdAt = 1,
    deletedAt = deletedAt,
)

private fun entity(
    id: String,
    homeBookId: String? = null,
    homeSeriesId: String? = null,
    deletedAt: Long? = null,
) = EntityEntity(
    id = id,
    kind = EntityKind.ITEM,
    name = id,
    homeBookId = homeBookId,
    homeSeriesId = homeSeriesId,
    deletedAt = deletedAt,
    createdAt = 0,
    updatedAt = 0,
)

private fun book(id: String) =
    BookEntity(
        id = BookId(id),
        libraryId = LibraryId("lib"),
        folderId = FolderId("folder"),
        title = id,
        totalDuration = 0L,
        createdAt = Timestamp(0L),
        updatedAt = Timestamp(0L),
    )

/**
 * The `entities` mirror: frames land in Room with their kind (UNKNOWN included), tombstones keep the row
 * for undo, and the access gate's candidate set is every live entity a change to the asked-about books can
 * reach — book-homed directly, series-homed through `book_series`.
 */
class EntityMirrorApplyTest :
    FunSpec({
        test("a frame upserts the row, kind and home included") {
            runTest {
                val db = createInMemoryTestDatabase()
                EntityMirrorApply(db).upsert(payload("e1", kind = EntityKind.PEOPLE))
                val row = db.entityDao().getById("e1").shouldNotBeNull()
                row.kind shouldBe EntityKind.PEOPLE
                row.homeBookId shouldBe "b1"
                row.revision shouldBe 5
                db.close()
            }
        }

        test("an UNKNOWN kind from a newer server is mirrored, not dropped") {
            runTest {
                val db = createInMemoryTestDatabase()
                EntityMirrorApply(db).upsert(payload("e1", kind = EntityKind.UNKNOWN))
                db.entityDao().getById("e1").shouldNotBeNull().kind shouldBe EntityKind.UNKNOWN
                db.close()
            }
        }

        test("a catch-up tombstone soft-deletes by id and keeps the row's kind and name for undo") {
            runTest {
                val db = createInMemoryTestDatabase()
                val apply = EntityMirrorApply(db)
                apply.upsert(payload("e1"))
                // Tombstones arrive with the kind blanked to UNKNOWN and no home; only the id applies.
                apply.tombstoneFromItem(payload("e1", kind = EntityKind.UNKNOWN, revision = 9, deletedAt = 50, homeBookId = null))
                db.entityDao().getById("e1").shouldBeNull()
                val tombstone = db.entityDao().findById("e1").shouldNotBeNull()
                tombstone.deletedAt shouldBe 50
                tombstone.revision shouldBe 9
                tombstone.kind shouldBe EntityKind.CHARACTER
                tombstone.name shouldBe "Name e1"
                db.close()
            }
        }

        test("the access gate's candidates are the live entities homed on the asked-about books") {
            runTest {
                val db = createInMemoryTestDatabase()
                db.entityDao().upsert(entity("a", homeBookId = "b1"))
                db.entityDao().upsert(entity("b", homeBookId = "b2"))
                db.entityDao().upsert(entity("gone", homeBookId = "b1", deletedAt = 3))
                db.entityDao().liveIdsTouchingBooks(listOf("b1"), listOf("b1")) shouldBe listOf("a")
                db.close()
            }
        }

        test("the access gate's candidates include entities homed on a series that holds an asked-about book") {
            runTest {
                val db = createInMemoryTestDatabase()
                db.bookDao().upsert(book("b1"))
                db.bookDao().upsert(book("b2"))
                db.seriesDao().upsert(
                    SeriesEntity(
                        id = SeriesId("s1"),
                        name = "S",
                        description = null,
                        createdAt = Timestamp(0L),
                        updatedAt = Timestamp(0L),
                    ),
                )
                db.bookSeriesDao().insert(BookSeriesCrossRef(BookId("b1"), SeriesId("s1"), 1.0))
                db.entityDao().upsert(entity("in-series", homeSeriesId = "s1"))
                db.entityDao().upsert(entity("other-series", homeSeriesId = "s2"))
                db.entityDao().upsert(entity("on-b2", homeBookId = "b2"))

                db.entityDao().liveIdsTouchingBooks(listOf("b1"), listOf("b1")) shouldContainExactlyInAnyOrder listOf("in-series")
                db.close()
            }
        }

        test("entities declares a Targeted Books gate, after every other book-keyed domain") {
            val db = createInMemoryTestDatabase()
            val gate = entitiesDomain(db).accessGate.shouldNotBeNull()
            val delta = gate.delta.shouldBeInstanceOf<AccessDeltaPolicy.Targeted>()
            delta.axis shouldBe ScopeAxis.Books
            delta.order shouldBe 7
            db.close()
        }
    })
