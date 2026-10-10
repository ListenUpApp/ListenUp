package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.entity.StoryWorldOp
import com.calypsan.listenup.api.dto.worldevent.WorldEventOp
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.WorldEventId
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.eventUpsert
import com.calypsan.listenup.server.testing.record
import com.calypsan.listenup.server.testing.seedSeriesWithBooks
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

private class EventRemovalRig(
    val books: BookRepository,
    val events: WorldEventRepository,
) {
    suspend fun deletedAt(id: String) = events.findById(WorldEventId(id)).shouldNotBeNull().deletedAt
}

private fun SqlTestDatabases.eventRemovalRig(): EventRemovalRig {
    val bus = ChangeBus()
    val registry = SyncRegistry()
    val events = WorldEventRepository(db = sql, bus = bus, registry = registry, driver = driver)
    val books =
        BookRepository(
            db = sql,
            driver = driver,
            bus = bus,
            registry = registry,
            contributorRepository = ContributorRepository(sql, bus, registry),
            seriesRepository = SeriesRepository(sql, bus, registry),
            genreRepository = GenreRepository(sql, bus, registry),
            worldEventRepository = events,
        )
    return EventRemovalRig(books, events)
}

/** A book removal takes the events it homes or anchors with it; a re-add brings back exactly those. */
class WorldEventBookRemovalTest :
    FunSpec({
        test("removing a book tombstones the events it homes or anchors, with a system history row, and leaves the rest") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                runTest {
                    val saga = seedSeriesWithBooks("Red Rising", "b1", "b2")
                    sql.seedTestBook("solo")
                    val rig = eventRemovalRig()
                    rig.events.record(eventUpsert("homed", homeBookId = "solo"))
                    rig.events.record(eventUpsert("anchored", homeSeriesId = saga.value, bookId = "b1", positionMs = 5L))
                    rig.events.record(eventUpsert("elsewhere", homeSeriesId = saga.value, bookId = "b2", positionMs = 5L))
                    rig.events.record(eventUpsert("unanchored", homeSeriesId = saga.value))

                    rig.books.softDelete(BookId("solo"), clientOpId = null)
                    rig.books.softDelete(BookId("b1"), clientOpId = null)

                    rig.deletedAt("homed").shouldNotBeNull()
                    rig.deletedAt("anchored").shouldNotBeNull()
                    rig.deletedAt("elsewhere").shouldBeNull()
                    rig.deletedAt("unanchored").shouldBeNull()
                    val cascade = rig.events.listHistory(WorldEventId("anchored")).first()
                    cascade.op shouldBe StoryWorldOp.DELETE
                    cascade.actorId.shouldBeNull()
                }
            }
        }

        test("re-adding the book revives what the removal tombstoned, but not an event deleted by hand") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                runTest {
                    val saga = seedSeriesWithBooks("Red Rising", "b1")
                    val rig = eventRemovalRig()
                    rig.events.record(eventUpsert("kept", homeSeriesId = saga.value, bookId = "b1", positionMs = 5L))
                    rig.events.record(eventUpsert("binned", homeSeriesId = saga.value, bookId = "b1", positionMs = 6L))
                    rig.events.applyBatch(listOf(WorldEventOp.Delete(WorldEventId("binned"))), UserId("u1")) shouldBe
                        AppResult.Success(Unit)

                    rig.books.softDelete(BookId("b1"), clientOpId = null)
                    rig.books.reviveByIds(listOf(BookId("b1")), cascadeFloor = 0L)

                    rig.deletedAt("kept").shouldBeNull()
                    rig.deletedAt("binned").shouldNotBeNull()
                }
            }
        }
    })
