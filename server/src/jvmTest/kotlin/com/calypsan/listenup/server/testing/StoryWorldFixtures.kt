package com.calypsan.listenup.server.testing

import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.worldevent.WorldEventOp
import com.calypsan.listenup.api.dto.worldevent.WorldEventUpsert
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.EntityKind
import com.calypsan.listenup.api.sync.EntitySyncPayload
import com.calypsan.listenup.api.sync.WorldEventType
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.EntityId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.WorldEventId
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.EntityRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.sync.WorldEventRepository
import io.kotest.matchers.shouldBe
import kotlin.time.Clock

/**
 * Seeds a series named [name] containing [bookIds] (each seeded as a book). The library and folder must
 * already exist ([seedTestLibraryAndFolder]).
 */
internal suspend fun SqlTestDatabases.seedSeriesWithBooks(
    name: String,
    vararg bookIds: String,
): SeriesId {
    val series = SeriesRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry()).resolveOrCreate(name)
    bookIds.forEachIndexed { index, bookId ->
        sql.seedTestBook(bookId)
        sql.bookSeriesMembershipsQueries.insertIfAbsent(
            book_id = bookId,
            series_id = series.value,
            sequence = (index + 1).toDouble(),
            ordinal = 0L,
        )
    }
    return series
}

/** An [EntityRepository] over the test database, in its own registry. */
internal fun SqlTestDatabases.entityRepository(
    bus: ChangeBus = ChangeBus(),
    clock: Clock = Clock.System,
): EntityRepository = EntityRepository(db = sql, bus = bus, registry = SyncRegistry(), driver = driver, clock = clock)

/**
 * A minimal entity payload; exactly one of [homeSeriesId] / [homeBookId] should be set. [updatedAt] is what
 * the caller claims; the server ignores it and stamps its own clock.
 */
internal fun entityPayload(
    id: String,
    kind: EntityKind = EntityKind.CHARACTER,
    name: String = id,
    homeSeriesId: String? = null,
    homeBookId: String? = null,
    parentId: String? = null,
    updatedAt: Long = 1_000L,
) = EntitySyncPayload(
    id = id,
    kind = kind,
    name = name,
    descriptor = null,
    parentId = parentId,
    homeSeriesId = homeSeriesId,
    homeBookId = homeBookId,
    imageRef = null,
    createdBy = "u1",
    updatedBy = "u1",
    revision = 0L,
    updatedAt = updatedAt,
    createdAt = updatedAt,
    deletedAt = null,
)

/** A [WorldEventRepository] over the test database, in its own registry. */
internal fun SqlTestDatabases.worldEventRepository(
    bus: ChangeBus = ChangeBus(),
    clock: Clock = Clock.System,
): WorldEventRepository =
    WorldEventRepository(db = sql, bus = bus, registry = SyncRegistry(), driver = driver, clock = clock)

/** A world-event upsert; exactly one of [homeSeriesId] / [homeBookId] should be set. */
internal fun eventUpsert(
    id: String,
    type: WorldEventType = WorldEventType.NOTE,
    text: String = "note $id",
    homeSeriesId: String? = null,
    homeBookId: String? = null,
    bookId: String? = null,
    positionMs: Long? = null,
    subject: String? = null,
    obj: String? = null,
    detail: String? = null,
) = WorldEventUpsert(
    id = WorldEventId(id),
    type = type,
    text = text,
    detail = detail,
    homeSeriesId = homeSeriesId?.let(::SeriesId),
    homeBookId = homeBookId?.let(::BookId),
    bookId = bookId?.let(::BookId),
    positionMs = positionMs,
    subjectEntityId = subject?.let(::EntityId),
    objectEntityId = obj?.let(::EntityId),
)

/** Applies [upsert] as a batch of one by [actor] and asserts it landed. */
internal suspend fun WorldEventRepository.record(
    upsert: WorldEventUpsert,
    actor: UserId = UserId("u1"),
) {
    applyBatch(listOf(WorldEventOp.Upsert(upsert)), actor) shouldBe AppResult.Success(Unit)
}
