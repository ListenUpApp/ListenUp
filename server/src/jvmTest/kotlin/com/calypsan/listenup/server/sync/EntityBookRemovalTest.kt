package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.entity.StoryWorldOp
import com.calypsan.listenup.api.error.EntityError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.EntityId
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.testing.MutableClock
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.entityPayload
import com.calypsan.listenup.server.testing.seedSeriesWithBooks
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * A book's removal reaches its Story World: book-homed entities are tombstoned with the book (a DELETE
 * history row with no actor), and a re-add of the book brings them back (REVERT, no actor) — the user's
 * curation is never stranded by a rescan. Series-homed entities, and other books' entities, are untouched.
 */
class EntityBookRemovalTest :
    FunSpec({
        test("removing a book tombstones its entities with a system history row, and leaves the rest") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                runTest {
                    val rig = removalRig()
                    val series = seedSeriesWithBooks("S", "b1", "b2")
                    rig.entities.upsertEntity(entityPayload("in-book", homeBookId = "b1"), UserId("u1"))
                    rig.entities.upsertEntity(entityPayload("other-book", homeBookId = "b2"), UserId("u1"))
                    rig.entities.upsertEntity(entityPayload("in-series", homeSeriesId = series.value), UserId("u1"))

                    rig.books.softDelete(BookId("b1"), clientOpId = null)

                    rig.entities
                        .findById(EntityId("in-book"))
                        .shouldNotBeNull()
                        .deletedAt
                        .shouldNotBeNull()
                    rig.entities
                        .findById(EntityId("other-book"))
                        .shouldNotBeNull()
                        .deletedAt
                        .shouldBeNull()
                    rig.entities
                        .findById(EntityId("in-series"))
                        .shouldNotBeNull()
                        .deletedAt
                        .shouldBeNull()
                    val removal = rig.entities.listHistory(EntityId("in-book")).first()
                    removal.op shouldBe StoryWorldOp.DELETE
                    removal.actorId.shouldBeNull()
                }
            }
        }

        test("re-adding the book revives what the removal tombstoned, and only that") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                runTest {
                    val rig = removalRig()
                    seedSeriesWithBooks("S", "b1")
                    rig.entities.upsertEntity(entityPayload("kept", homeBookId = "b1"), UserId("u1"))
                    rig.entities.upsertEntity(entityPayload("binned", homeBookId = "b1"), UserId("u1"))
                    rig.entities.deleteEntity(EntityId("binned"), UserId("u1"))
                    rig.clock.instant += 5.seconds

                    rig.books.softDelete(BookId("b1"), clientOpId = null)
                    val bookRemovedAt =
                        rig.entities
                            .findById(EntityId("kept"))
                            .shouldNotBeNull()
                            .deletedAt
                            .shouldNotBeNull()

                    // An ordinary edit can't bring a tombstoned entity back — only the re-add does.
                    rig.entities
                        .upsertEntity(entityPayload("kept", name = "Renamed", homeBookId = "b1"), UserId("u1"))
                        .shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<EntityError.NotFound>()

                    rig.books.reviveByIds(listOf(BookId("b1")), cascadeFloor = bookRemovedAt)

                    val kept = rig.entities.findById(EntityId("kept")).shouldNotBeNull()
                    kept.deletedAt.shouldBeNull()
                    kept.name shouldBe "kept"
                    val revival = rig.entities.listHistory(EntityId("kept")).first()
                    revival.op shouldBe StoryWorldOp.REVERT
                    revival.actorId.shouldBeNull()
                    // Deleted by hand before the removal: an older tombstone, so it stays deleted.
                    rig.entities
                        .findById(EntityId("binned"))
                        .shouldNotBeNull()
                        .deletedAt
                        .shouldNotBeNull()
                }
            }
        }

        test("a book removed twice still revives its entities when it comes back") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                runTest {
                    val rig = removalRig()
                    seedSeriesWithBooks("S", "b1")
                    rig.entities.upsertEntity(entityPayload("kept", homeBookId = "b1"), UserId("u1"))
                    rig.books.softDelete(BookId("b1"), clientOpId = null)

                    // A second removal of the already-removed book re-stamps the book's deleted_at.
                    rig.clock.instant += 5.seconds
                    rig.books.softDelete(BookId("b1"), clientOpId = null)
                    val secondRemovalAt = rig.clock.instant.toEpochMilliseconds()

                    rig.books.reviveByIds(listOf(BookId("b1")), cascadeFloor = secondRemovalAt)

                    rig.entities
                        .findById(EntityId("kept"))
                        .shouldNotBeNull()
                        .deletedAt
                        .shouldBeNull()
                }
            }
        }

        test("an entity a curator deletes by hand while its book is removed stays deleted on re-add") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                runTest {
                    val rig = removalRig()
                    seedSeriesWithBooks("S", "b1")
                    rig.entities.upsertEntity(entityPayload("curated", homeBookId = "b1"), UserId("u1"))
                    rig.books.softDelete(BookId("b1"), clientOpId = null)
                    val bookRemovedAt = rig.clock.instant.toEpochMilliseconds()
                    val cascade = rig.entities.listHistory(EntityId("curated")).first()

                    // The admin brings it back, then deletes it deliberately.
                    rig.clock.instant += 5.seconds
                    rig.entities
                        .revert(cascade.id, UserId("admin"), allowMergeRevert = true)
                        .shouldBeInstanceOf<AppResult.Success<*>>()
                    rig.entities
                        .deleteEntity(EntityId("curated"), UserId("admin"))
                        .shouldBeInstanceOf<AppResult.Success<*>>()

                    rig.books.reviveByIds(listOf(BookId("b1")), cascadeFloor = bookRemovedAt)

                    rig.entities
                        .findById(EntityId("curated"))
                        .shouldNotBeNull()
                        .deletedAt
                        .shouldNotBeNull()
                }
            }
        }
    })

/** A [BookRepository] wired to an [EntityRepository], both on one advanceable clock. */
private class RemovalRig(
    val books: BookRepository,
    val entities: EntityRepository,
    val clock: MutableClock,
)

private fun SqlTestDatabases.removalRig(): RemovalRig {
    val bus = ChangeBus()
    val registry = SyncRegistry()
    val clock = MutableClock(Instant.fromEpochMilliseconds(1_700_000_000_000L))
    val entities = EntityRepository(db = sql, bus = bus, registry = registry, driver = driver, clock = clock)
    val books =
        BookRepository(
            db = sql,
            driver = driver,
            bus = bus,
            registry = registry,
            contributorRepository = ContributorRepository(sql, bus, registry),
            seriesRepository = SeriesRepository(sql, bus, registry),
            genreRepository = GenreRepository(sql, bus, registry),
            clock = clock,
            entityRepository = entities,
        )
    return RemovalRig(books, entities, clock)
}
