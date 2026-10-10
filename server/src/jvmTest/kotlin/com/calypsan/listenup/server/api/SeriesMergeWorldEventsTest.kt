package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.MergeReceipt
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.entity.StoryWorldOp
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookSeriesPayload
import com.calypsan.listenup.api.sync.WorldEventType
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.WorldEventId
import com.calypsan.listenup.domain.storyworld.MentionTokens
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.EntityRepository
import com.calypsan.listenup.server.sync.ReadingOrderRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.sync.WorldEventRepository
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.bookPayloadFixture
import com.calypsan.listenup.server.testing.entityPayload
import com.calypsan.listenup.server.testing.eventUpsert
import com.calypsan.listenup.server.testing.record
import com.calypsan.listenup.server.testing.rootPrincipal
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

private class EventMergeRig(
    val seriesRepo: SeriesRepository,
    val bookRepo: BookRepository,
    val entities: EntityRepository,
    val events: WorldEventRepository,
    val service: SeriesServiceImpl,
) {
    suspend fun seedSeries(
        sourceName: String,
        targetName: String,
    ): Pair<SeriesId, SeriesId> {
        val source = seriesRepo.resolveOrCreate(sourceName)
        val target = seriesRepo.resolveOrCreate(targetName)
        bookRepo.upsert(
            bookPayloadFixture(id = "b1", title = "b1", series = listOf(BookSeriesPayload(source.value, sourceName, 1.0))),
        )
        return source to target
    }

    suspend fun undoOnlyMergeInto(target: SeriesId) {
        val receipt =
            service
                .listMergeReceipts(target)
                .shouldBeInstanceOf<AppResult.Success<List<MergeReceipt>>>()
                .data
                .single()
        service.undoSeriesMerge(receipt.id).shouldBeInstanceOf<AppResult.Success<*>>()
    }

    suspend fun event(id: String) = events.findById(WorldEventId(id)).shouldNotBeNull()
}

private fun SqlTestDatabases.eventMergeRig(): EventMergeRig {
    sql.seedTestLibraryAndFolder()
    sql.seedTestUser("test-root", UserRoleColumn.ROOT)
    val bus = ChangeBus()
    val registry = SyncRegistry()
    val seriesRepo = SeriesRepository(db = sql, bus = bus, registry = registry)
    val bookRepo =
        BookRepository(
            db = sql,
            driver = driver,
            bus = bus,
            registry = registry,
            contributorRepository = ContributorRepository(db = sql, bus = bus, registry = registry),
            seriesRepository = seriesRepo,
            genreRepository = GenreRepository(db = sql, bus = bus, registry = registry),
        )
    val entities = EntityRepository(db = sql, bus = bus, registry = registry, driver = driver)
    val events = WorldEventRepository(db = sql, bus = bus, registry = registry, driver = driver)
    val service =
        SeriesServiceImpl(
            seriesRepo = seriesRepo,
            bookRepo = bookRepo,
            sqlDb = sql,
            accessPolicy = BookAccessPolicy(sql, driver),
            readingOrders = ReadingOrderRepository(sql, bus, registry),
            principal = rootPrincipal(),
            entityRepo = entities,
            worldEventRepo = events,
        )
    return EventMergeRig(seriesRepo, bookRepo, entities, events, service)
}

/** A series merge carries the source's events into the target, and undo carries back exactly what it moved. */
class SeriesMergeWorldEventsTest :
    FunSpec({
        test("merging re-homes the source's events with a system history row, keeping their mentions; undo moves them back") {
            withSqlDatabase {
                runTest {
                    val rig = eventMergeRig()
                    val (source, target) = rig.seedSeries("Dark Age", "Red Rising Saga")
                    rig.entities.upsertEntity(entityPayload("lysander", homeSeriesId = source.value), UserId("u1"))
                    rig.events.record(
                        eventUpsert(
                            "w1",
                            text = MentionTokens.token("lysander", "Lysander"),
                            homeSeriesId = source.value,
                            bookId = "b1",
                            positionMs = 9L,
                        ),
                    )

                    rig.service.mergeSeries(source, target) shouldBe AppResult.Success(Unit)

                    rig.event("w1").homeSeriesId shouldBe target.value
                    rig.event("w1").mentionIds shouldBe listOf("lysander")
                    val rehome = rig.events.listHistory(WorldEventId("w1")).first()
                    rehome.op shouldBe StoryWorldOp.UPDATE
                    rehome.actorId.shouldBeNull()

                    rig.undoOnlyMergeInto(target)

                    rig.event("w1").homeSeriesId shouldBe source.value
                    rig.event("w1").mentionIds shouldBe listOf("lysander")
                }
            }
        }

        test("undo leaves behind an event created in the target since the merge, and returns an edited one as edited") {
            withSqlDatabase {
                runTest {
                    val rig = eventMergeRig()
                    val (source, target) = rig.seedSeries("Dark Age", "Red Rising Saga")
                    rig.events.record(eventUpsert("moved", text = "before", homeSeriesId = source.value))
                    rig.service.mergeSeries(source, target) shouldBe AppResult.Success(Unit)
                    rig.events.record(eventUpsert("moved", text = "after", homeSeriesId = target.value))
                    rig.events.record(eventUpsert("born-in-target", homeSeriesId = target.value))

                    rig.undoOnlyMergeInto(target)

                    rig.event("moved").homeSeriesId shouldBe source.value
                    rig.event("moved").text shouldBe "after"
                    rig.event("born-in-target").homeSeriesId shouldBe target.value
                }
            }
        }

        test("undo sends an event home only where it is still whole: a foreign anchor drops, a foreign participant stays") {
            withSqlDatabase {
                runTest {
                    val rig = eventMergeRig()
                    val (source, target) = rig.seedSeries("Dark Age", "Red Rising Saga")
                    rig.bookRepo.upsert(
                        bookPayloadFixture(
                            id = "t1",
                            title = "t1",
                            series = listOf(BookSeriesPayload(target.value, "Red Rising Saga", 1.0)),
                        ),
                    )
                    rig.entities.upsertEntity(entityPayload("lysander", homeSeriesId = source.value), UserId("u1"))
                    rig.entities.upsertEntity(entityPayload("eo", homeSeriesId = target.value), UserId("u1"))
                    rig.events.record(eventUpsert("pinned", homeSeriesId = source.value, bookId = "b1", positionMs = 9L))
                    rig.events.record(
                        eventUpsert(
                            "scene",
                            type = WorldEventType.ENTERS_SCENE,
                            text = "",
                            homeSeriesId = source.value,
                            subject = "lysander",
                        ),
                    )
                    rig.service.mergeSeries(source, target) shouldBe AppResult.Success(Unit)
                    rig.events.record(eventUpsert("pinned", homeSeriesId = target.value, bookId = "t1", positionMs = 4L))
                    rig.events.record(
                        eventUpsert("scene", type = WorldEventType.ENTERS_SCENE, text = "", homeSeriesId = target.value, subject = "eo"),
                    )

                    rig.undoOnlyMergeInto(target)

                    val pinned = rig.event("pinned")
                    pinned.homeSeriesId shouldBe source.value
                    pinned.bookId.shouldBeNull()
                    pinned.positionMs.shouldBeNull()
                    val scene = rig.event("scene")
                    scene.homeSeriesId shouldBe target.value
                    scene.subjectEntityId shouldBe "eo"
                }
            }
        }
    })
