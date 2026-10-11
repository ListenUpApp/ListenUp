package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.api.sync.WorldEventSyncPayload
import com.calypsan.listenup.api.sync.WorldEventType
import com.calypsan.listenup.client.data.local.db.BookEntity
import com.calypsan.listenup.client.data.local.db.BookSeriesCrossRef
import com.calypsan.listenup.client.data.local.db.SeriesEntity
import com.calypsan.listenup.client.data.local.db.WorldEventEntity
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

private fun payload(
    id: String,
    type: WorldEventType = WorldEventType.JOINS,
    mentionIds: List<String> = listOf("c1", "g1"),
    revision: Long = 5,
    deletedAt: Long? = null,
) = WorldEventSyncPayload(
    id = id,
    homeSeriesId = "s1",
    bookId = "b1",
    positionMs = 60_000L,
    type = type,
    text = "Text $id",
    detail = "Primus",
    subjectEntityId = "c1",
    objectEntityId = "g1",
    mentionIds = mentionIds,
    revision = revision,
    updatedAt = 10,
    createdAt = 1,
    deletedAt = deletedAt,
)

private fun event(
    id: String,
    homeBookId: String? = null,
    homeSeriesId: String? = null,
    bookId: String? = null,
    deletedAt: Long? = null,
) = WorldEventEntity(
    id = id,
    homeBookId = homeBookId,
    homeSeriesId = homeSeriesId,
    bookId = bookId,
    positionMs = bookId?.let { 0L },
    type = WorldEventType.NOTE,
    text = id,
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
 * The `world_events` mirror: frames land with their mentions (the server's set replacing the local guess),
 * tombstones keep the row for undo, an unknown type is mirrored, and the gate's candidates are every live
 * event a change to the asked-about books can reach — by home, by anchor, or through the home series.
 */
class WorldEventMirrorApplyTest :
    FunSpec({
        test("a frame upserts the row and replaces its mentions with the server's set") {
            runTest {
                val db = createInMemoryTestDatabase()
                val apply = WorldEventMirrorApply(db)
                apply.upsert(payload("w1", mentionIds = listOf("c1", "g1", "stale")))
                apply.upsert(payload("w1", mentionIds = listOf("c1", "g1"), revision = 6))

                val row =
                    db
                        .worldEventDao()
                        .observeById("w1")
                        .first()
                        .shouldNotBeNull()
                row.event.type shouldBe WorldEventType.JOINS
                row.event.detail shouldBe "Primus"
                row.event.positionMs shouldBe 60_000L
                row.mentionIds shouldContainExactlyInAnyOrder listOf("c1", "g1")
                db.close()
            }
        }

        test("an UNKNOWN type from a newer server is mirrored, not dropped") {
            runTest {
                val db = createInMemoryTestDatabase()
                WorldEventMirrorApply(db).upsert(payload("w1", type = WorldEventType.UNKNOWN))
                db
                    .worldEventDao()
                    .getById("w1")
                    .shouldNotBeNull()
                    .type shouldBe WorldEventType.UNKNOWN
                db.close()
            }
        }

        test("a catch-up tombstone soft-deletes by id and keeps the row's content for undo") {
            runTest {
                val db = createInMemoryTestDatabase()
                val apply = WorldEventMirrorApply(db)
                apply.upsert(payload("w1"))
                apply.tombstoneFromItem(
                    payload("w1", type = WorldEventType.UNKNOWN, revision = 9, deletedAt = 50, mentionIds = emptyList()),
                )

                db.worldEventDao().getById("w1").shouldBeNull()
                val tombstone = db.worldEventDao().findById("w1").shouldNotBeNull()
                tombstone.deletedAt shouldBe 50
                tombstone.revision shouldBe 9
                tombstone.type shouldBe WorldEventType.JOINS
                tombstone.text shouldBe "Text w1"
                db.close()
            }
        }

        test("the gate's candidates are the live events homed on, anchored to, or in a series holding the books") {
            runTest {
                val db = createInMemoryTestDatabase()
                db.bookDao().upsert(book("b1"))
                db.seriesDao().upsert(
                    SeriesEntity(id = SeriesId("s1"), name = "S", description = null, createdAt = Timestamp(0L), updatedAt = Timestamp(0L)),
                )
                db.bookSeriesDao().insert(BookSeriesCrossRef(BookId("b1"), SeriesId("s1"), 1.0))
                val dao = db.worldEventDao()
                dao.upsert(event("homed", homeBookId = "b1"))
                dao.upsert(event("anchored", homeSeriesId = "s9", bookId = "b1"))
                dao.upsert(event("in-series", homeSeriesId = "s1"))
                dao.upsert(event("elsewhere", homeBookId = "b2"))
                dao.upsert(event("gone", homeBookId = "b1", deletedAt = 3))

                dao.liveIdsTouchingBooks(listOf("b1"), listOf("b1"), listOf("b1")) shouldContainExactlyInAnyOrder
                    listOf("homed", "anchored", "in-series")
                db.close()
            }
        }

        test("world_events declares a Targeted Books gate, after every other book-keyed domain") {
            val db = createInMemoryTestDatabase()
            val delta =
                worldEventsDomain(db)
                    .accessGate
                    .shouldNotBeNull()
                    .delta
                    .shouldBeInstanceOf<AccessDeltaPolicy.Targeted>()
            delta.axis shouldBe ScopeAxis.Books
            delta.order shouldBe 9
            db.close()
        }
    })
