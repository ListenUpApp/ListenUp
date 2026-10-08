package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.MergeReceipt
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.entity.StoryWorldOp
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookSeriesPayload
import com.calypsan.listenup.api.sync.EntityKind
import com.calypsan.listenup.core.EntityId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.EntityRepository
import com.calypsan.listenup.server.sync.ReadingOrderRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.bookPayloadFixture
import com.calypsan.listenup.server.testing.entityPayload
import com.calypsan.listenup.server.testing.rootPrincipal
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/**
 * A series merge carries the source's Story World into the target, and undoing the merge carries back
 * exactly what the merge moved — in its current state, never what was created in the target since.
 */
class SeriesMergeEntitiesTest :
    FunSpec({
        test("merging re-homes the source's entities with a system history row, and undo moves them back") {
            withSqlDatabase {
                runTest {
                    val rig = mergeRig()
                    val (source, target) = rig.seedSeries("Dark Age", "Red Rising Saga")
                    rig.entities.upsertEntity(entityPayload("lysander", homeSeriesId = source.value), UserId("u1"))

                    rig.service.mergeSeries(source, target) shouldBe AppResult.Success(Unit)

                    rig.entities
                        .findById(EntityId("lysander"))
                        .shouldNotBeNull()
                        .homeSeriesId shouldBe target.value
                    val rehome = rig.entities.listHistory(EntityId("lysander")).first()
                    rehome.op shouldBe StoryWorldOp.UPDATE
                    rehome.actorId.shouldBeNull()

                    rig.undoOnlyMergeInto(target)

                    rig.entities
                        .findById(EntityId("lysander"))
                        .shouldNotBeNull()
                        .homeSeriesId shouldBe source.value
                }
            }
        }

        test("an entity edited between merge and undo keeps its edit when it moves back") {
            withSqlDatabase {
                runTest {
                    val rig = mergeRig()
                    val (source, target) = rig.seedSeries("A", "B")
                    rig.entities.upsertEntity(entityPayload("lysander", homeSeriesId = source.value), UserId("u1"))
                    rig.service.mergeSeries(source, target)

                    rig.entities.upsertEntity(
                        entityPayload("lysander", name = "Lysander au Lune", homeSeriesId = target.value),
                        UserId("u2"),
                    )
                    rig.undoOnlyMergeInto(target)

                    val back = rig.entities.findById(EntityId("lysander")).shouldNotBeNull()
                    back.homeSeriesId shouldBe source.value
                    back.name shouldBe "Lysander au Lune"
                }
            }
        }

        test("an entity created under the target after the merge stays there on undo") {
            withSqlDatabase {
                runTest {
                    val rig = mergeRig()
                    val (source, target) = rig.seedSeries("A", "B")
                    rig.service.mergeSeries(source, target)
                    rig.entities.upsertEntity(entityPayload("late", homeSeriesId = target.value), UserId("u1"))

                    rig.undoOnlyMergeInto(target)

                    rig.entities
                        .findById(EntityId("late"))
                        .shouldNotBeNull()
                        .homeSeriesId shouldBe target.value
                }
            }
        }

        test("an undo that fails after its claim has already carried the entities back") {
            withSqlDatabase {
                runTest {
                    val rig = mergeRig()
                    val (source, target) = rig.seedSeries("A", "B")
                    rig.entities.upsertEntity(entityPayload("lysander", homeSeriesId = source.value), UserId("u1"))
                    rig.service.mergeSeries(source, target)
                    val receipt =
                        rig.service
                            .listMergeReceipts(target)
                            .shouldBeInstanceOf<AppResult.Success<List<MergeReceipt>>>()
                            .data
                            .single()

                    // The book re-upserts — the first step after the claim — now fail outright.
                    driver.execute(
                        null,
                        "CREATE TRIGGER fail_book_writes BEFORE UPDATE ON books BEGIN SELECT RAISE(ABORT, 'boom'); END",
                        0,
                    )
                    shouldThrowAny { rig.service.undoSeriesMerge(receipt.id) }

                    // The claim marked the receipt undone, so no retry can reach the entities: they must
                    // already be home.
                    rig.entities
                        .findById(EntityId("lysander"))
                        .shouldNotBeNull()
                        .homeSeriesId shouldBe source.value
                }
            }
        }

        test("undo never leaves a parent link across two homes") {
            withSqlDatabase {
                runTest {
                    val rig = mergeRig()
                    val (source, target) = rig.seedSeries("A", "B")
                    rig.entities.upsertEntity(
                        entityPayload("mars", kind = EntityKind.LOCATION, homeSeriesId = source.value),
                        UserId("u1"),
                    )
                    rig.entities.upsertEntity(
                        entityPayload("luna", kind = EntityKind.LOCATION, homeSeriesId = target.value),
                        UserId("u1"),
                    )
                    rig.entities.upsertEntity(
                        entityPayload("lykos", kind = EntityKind.LOCATION, homeSeriesId = source.value),
                        UserId("u1"),
                    )
                    rig.service.mergeSeries(source, target)

                    // Since the merge: a target-native place moved under a moved one, and a moved place
                    // moved under a target-native one.
                    rig.entities.upsertEntity(
                        entityPayload("luna", kind = EntityKind.LOCATION, homeSeriesId = target.value, parentId = "mars"),
                        UserId("u1"),
                    )
                    rig.entities.upsertEntity(
                        entityPayload("lykos", kind = EntityKind.LOCATION, homeSeriesId = target.value, parentId = "luna"),
                        UserId("u1"),
                    )
                    rig.undoOnlyMergeInto(target)

                    rig.entities
                        .findById(EntityId("luna"))
                        .shouldNotBeNull()
                        .parentId
                        .shouldBeNull()
                    val lykos = rig.entities.findById(EntityId("lykos")).shouldNotBeNull()
                    lykos.homeSeriesId shouldBe source.value
                    lykos.parentId.shouldBeNull()
                }
            }
        }
    })

/** A series service wired to an [EntityRepository], as root. */
private class MergeRig(
    val seriesRepo: SeriesRepository,
    val bookRepo: BookRepository,
    val entities: EntityRepository,
    val service: SeriesServiceImpl,
) {
    /** Seeds a live [sourceName] series holding one book, and an empty live [targetName] series. */
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

    /** Undoes the single open merge into [target], asserting it succeeds. */
    suspend fun undoOnlyMergeInto(target: SeriesId) {
        val receipt =
            service
                .listMergeReceipts(target)
                .shouldBeInstanceOf<AppResult.Success<List<MergeReceipt>>>()
                .data
                .single()
        service.undoSeriesMerge(receipt.id).shouldBeInstanceOf<AppResult.Success<*>>()
    }
}

private fun SqlTestDatabases.mergeRig(): MergeRig {
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
    val service =
        SeriesServiceImpl(
            seriesRepo = seriesRepo,
            bookRepo = bookRepo,
            sqlDb = sql,
            accessPolicy = BookAccessPolicy(sql, driver),
            readingOrders = ReadingOrderRepository(sql, bus, registry),
            principal = rootPrincipal(),
            entityRepo = entities,
        )
    return MergeRig(seriesRepo, bookRepo, entities, service)
}
