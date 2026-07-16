@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.core.MentionTokens
import com.calypsan.listenup.api.dto.world.WorldEventOp
import com.calypsan.listenup.api.dto.world.WorldEventUpsert
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.EntityKind
import com.calypsan.listenup.api.sync.EntitySyncPayload
import com.calypsan.listenup.api.sync.WorldEventSource
import com.calypsan.listenup.api.sync.WorldEventType
import com.calypsan.listenup.server.testing.MutableClock
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestSeries
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * Tests for [WorldEventRepository] — the Story World unified event log domain, dual-homed under
 * exactly one of a series or a standalone book, optionally anchored to a book position. Every
 * test runs against a real migrated database as a SQLDelight
 * [com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase] (`sql`), with rows seeded through
 * the SQLDelight seed helpers (see [withSqlDatabase]).
 *
 * Coverage:
 *  - round-trip of a series-homed event via [WorldEventRepository.applyBatch];
 *  - server-side mention recompute: the union of text-embedded tokens, `subjectEntityId`, and
 *    `objectEntityId` lands in the `world_event_mentions` junction, never a client-authored set
 *    ([com.calypsan.listenup.api.dto.world.WorldEventUpsert] carries no `mentionIds` field at all);
 *  - the `updatedAt`-wins staleness guard applied per-op, including a stale item mixed into an
 *    otherwise-fresh batch (a no-op for that one item, not a whole-batch failure);
 *  - whole-batch atomicity: a [WorldEventOp.Delete] targeting a nonexistent event fails the
 *    entire batch and leaves nothing applied;
 *  - soft delete (a [WorldEventOp.Delete] op) tombstones the row, minimizes its content on
 *    [SqlSyncableRepository.pullSince], and clears the mention junction;
 *  - [WorldEventRepository.listForEntity] resolves via the junction;
 *  - [WorldEventRepository.listForBook] / [WorldEventRepository.listForWorld].
 */
class WorldEventRepositoryTest :
    FunSpec({

        fun seriesHomedEntity(
            id: String,
            seriesId: String,
            name: String = id,
        ) = EntitySyncPayload(
            id = id,
            kind = EntityKind.CHARACTER,
            name = name,
            homeSeriesId = seriesId,
            homeBookId = null,
            imageRef = null,
            revision = 0L,
            updatedAt = 1_000L,
            createdAt = 1_000L,
            deletedAt = null,
        )

        // ── round-trip ──────────────────────────────────────────────────────────

        test("applyBatch upserts a series-homed NOTE event and findById returns it") {
            withSqlDatabase {
                sql.seedTestSeries("mistborn")
                val repo = WorldEventRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())

                runTest {
                    val result =
                        repo.applyBatch(
                            listOf(
                                WorldEventOp.Upsert(
                                    WorldEventUpsert(
                                        id = "e1",
                                        homeSeriesId = "mistborn",
                                        type = WorldEventType.NOTE,
                                        text = "Vin wakes up.",
                                    ),
                                ),
                            ),
                        )
                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()

                    val stored = repo.findById("e1")
                    stored shouldNotBe null
                    stored?.text shouldBe "Vin wakes up."
                    stored?.homeSeriesId shouldBe "mistborn"
                    stored?.homeBookId shouldBe null
                    stored?.type shouldBe WorldEventType.NOTE
                    stored?.source shouldBe WorldEventSource.MANUAL
                }
            }
        }

        test("applyBatch upserts a book-homed, book-anchored event") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("hoa1")
                val repo = WorldEventRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())

                runTest {
                    repo.applyBatch(
                        listOf(
                            WorldEventOp.Upsert(
                                WorldEventUpsert(
                                    id = "e1",
                                    homeBookId = "hoa1",
                                    bookId = "hoa1",
                                    positionMs = 120_000L,
                                    type = WorldEventType.NOTE,
                                    text = "Alcatraz breaks something.",
                                ),
                            ),
                        ),
                    )

                    val stored = repo.findById("e1")
                    stored?.homeBookId shouldBe "hoa1"
                    stored?.homeSeriesId shouldBe null
                    stored?.bookId shouldBe "hoa1"
                    stored?.positionMs shouldBe 120_000L
                }
            }
        }

        // ── mention recompute ───────────────────────────────────────────────────

        test("applyBatch recomputes mentionIds as the union of text tokens, subject, and object") {
            withSqlDatabase {
                sql.seedTestSeries("mistborn")
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val entityRepo = EntityRepository(db = sql, bus = bus, registry = registry)
                val repo = WorldEventRepository(db = sql, bus = bus, registry = registry)

                runTest {
                    entityRepo.upsertEntity(seriesHomedEntity("vin", "mistborn"))
                    entityRepo.upsertEntity(seriesHomedEntity("kelsier", "mistborn"))
                    entityRepo.upsertEntity(seriesHomedEntity("luthadel", "mistborn"))

                    val text = "They meet at ${MentionTokens.token("luthadel", "Luthadel")}."
                    repo.applyBatch(
                        listOf(
                            WorldEventOp.Upsert(
                                WorldEventUpsert(
                                    id = "e1",
                                    homeSeriesId = "mistborn",
                                    type = WorldEventType.MOVES_TO,
                                    text = text,
                                    subjectEntityId = "vin",
                                    objectEntityId = "kelsier",
                                ),
                            ),
                        ),
                    )

                    val stored = repo.findById("e1")
                    stored?.mentionIds?.toSet() shouldBe setOf("luthadel", "vin", "kelsier")

                    val viaJunction = repo.listForEntity("luthadel")
                    viaJunction shouldHaveSize 1
                    viaJunction.first().id shouldBe "e1"
                }
            }
        }

        test("re-upserting an event with different text replaces the mention junction wholesale") {
            withSqlDatabase {
                sql.seedTestSeries("mistborn")
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val entityRepo = EntityRepository(db = sql, bus = bus, registry = registry)
                val repo = WorldEventRepository(db = sql, bus = bus, registry = registry)

                runTest {
                    entityRepo.upsertEntity(seriesHomedEntity("vin", "mistborn"))
                    entityRepo.upsertEntity(seriesHomedEntity("kelsier", "mistborn"))

                    repo.applyBatch(
                        listOf(
                            WorldEventOp.Upsert(
                                WorldEventUpsert(
                                    id = "e1",
                                    homeSeriesId = "mistborn",
                                    type = WorldEventType.NOTE,
                                    text = "about vin",
                                    subjectEntityId = "vin",
                                ),
                            ),
                        ),
                    )
                    repo.applyBatch(
                        listOf(
                            WorldEventOp.Upsert(
                                WorldEventUpsert(
                                    id = "e1",
                                    homeSeriesId = "mistborn",
                                    type = WorldEventType.NOTE,
                                    text = "about kelsier now",
                                    subjectEntityId = "kelsier",
                                ),
                            ),
                        ),
                    )

                    repo.findById("e1")?.mentionIds shouldBe listOf("kelsier")
                    repo.listForEntity("vin") shouldHaveSize 0
                    repo.listForEntity("kelsier") shouldHaveSize 1
                }
            }
        }

        // ── stale-write guard ──────────────────────────────────────────────────

        test("a stale item mixed into a fresh batch is a no-op for that item only") {
            withSqlDatabase {
                sql.seedTestSeries("mistborn")
                val clock = MutableClock(Instant.fromEpochMilliseconds(5_000L))
                val repo = WorldEventRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry(), clock = clock)

                runTest {
                    repo.applyBatch(
                        listOf(
                            WorldEventOp.Upsert(
                                WorldEventUpsert(id = "e1", homeSeriesId = "mistborn", type = WorldEventType.NOTE, text = "Fresh."),
                            ),
                        ),
                    )

                    // Rewind the clock: this batch's stamp is now strictly older than e1's stored updatedAt.
                    clock.instant = Instant.fromEpochMilliseconds(1_000L)
                    val result =
                        repo.applyBatch(
                            listOf(
                                WorldEventOp.Upsert(
                                    WorldEventUpsert(
                                        id = "e1",
                                        homeSeriesId = "mistborn",
                                        type = WorldEventType.NOTE,
                                        text = "Stale — must not apply.",
                                    ),
                                ),
                                WorldEventOp.Upsert(
                                    WorldEventUpsert(id = "e2", homeSeriesId = "mistborn", type = WorldEventType.NOTE, text = "New."),
                                ),
                            ),
                        )

                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()
                    repo.findById("e1")?.text shouldBe "Fresh."
                    repo.findById("e2")?.text shouldBe "New."
                }
            }
        }

        // ── whole-batch atomicity ────────────────────────────────────────────────

        test("a Delete targeting a nonexistent event fails the whole batch and applies nothing") {
            withSqlDatabase {
                sql.seedTestSeries("mistborn")
                val repo = WorldEventRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())

                runTest {
                    val result =
                        repo.applyBatch(
                            listOf(
                                WorldEventOp.Upsert(
                                    WorldEventUpsert(
                                        id = "e1",
                                        homeSeriesId = "mistborn",
                                        type = WorldEventType.NOTE,
                                        text = "Should not persist.",
                                    ),
                                ),
                                WorldEventOp.Delete(id = "ghost"),
                            ),
                        )

                    val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                    failure.error.shouldBeInstanceOf<SyncError.NotFound>()
                    repo.findById("e1") shouldBe null
                }
            }
        }

        // ── soft delete → tombstone ─────────────────────────────────────────────

        test("a Delete op tombstones the event, minimizes content on pullSince, and clears the junction") {
            withSqlDatabase {
                sql.seedTestSeries("mistborn")
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val entityRepo = EntityRepository(db = sql, bus = bus, registry = registry)
                val repo = WorldEventRepository(db = sql, bus = bus, registry = registry)

                runTest {
                    entityRepo.upsertEntity(seriesHomedEntity("vin", "mistborn"))
                    repo.applyBatch(
                        listOf(
                            WorldEventOp.Upsert(
                                WorldEventUpsert(
                                    id = "e1",
                                    homeSeriesId = "mistborn",
                                    type = WorldEventType.NOTE,
                                    text = "about vin",
                                    subjectEntityId = "vin",
                                ),
                            ),
                        ),
                    )

                    repo.applyBatch(listOf(WorldEventOp.Delete(id = "e1")))

                    val page = repo.pullSince(userId = null, cursor = 0L, limit = 50)
                    val tombstone = page.items.first { it.id == "e1" }
                    tombstone.deletedAt shouldNotBe null
                    tombstone.text shouldBe ""
                    tombstone.homeSeriesId shouldBe null
                    tombstone.homeBookId shouldBe null
                    tombstone.subjectEntityId shouldBe null
                    tombstone.mentionIds shouldBe emptyList()

                    repo.listForEntity("vin") shouldHaveSize 0
                }
            }
        }

        // ── reads ────────────────────────────────────────────────────────────────

        test("listForBook returns only events anchored to that book") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("hoa1")
                sql.seedTestBook("hoa2", rootRelPath = "hoa2/book.m4b")
                val repo = WorldEventRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())

                runTest {
                    repo.applyBatch(
                        listOf(
                            WorldEventOp.Upsert(
                                WorldEventUpsert(
                                    id = "e1",
                                    homeBookId = "hoa1",
                                    bookId = "hoa1",
                                    positionMs = 1_000L,
                                    type = WorldEventType.NOTE,
                                    text = "anchor 1",
                                ),
                            ),
                            WorldEventOp.Upsert(
                                WorldEventUpsert(
                                    id = "e2",
                                    homeBookId = "hoa2",
                                    bookId = "hoa2",
                                    positionMs = 2_000L,
                                    type = WorldEventType.NOTE,
                                    text = "anchor 2",
                                ),
                            ),
                        ),
                    )

                    val forBook1 = repo.listForBook("hoa1")
                    forBook1 shouldHaveSize 1
                    forBook1.first().id shouldBe "e1"
                }
            }
        }

        test("listForWorld resolves by homeSeriesId or homeBookId") {
            withSqlDatabase {
                sql.seedTestSeries("mistborn")
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("hoa1")
                val repo = WorldEventRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())

                runTest {
                    repo.applyBatch(
                        listOf(
                            WorldEventOp.Upsert(
                                WorldEventUpsert(id = "e1", homeSeriesId = "mistborn", type = WorldEventType.NOTE, text = "series note"),
                            ),
                            WorldEventOp.Upsert(
                                WorldEventUpsert(id = "e2", homeBookId = "hoa1", type = WorldEventType.NOTE, text = "book note"),
                            ),
                        ),
                    )

                    repo.listForWorld(homeSeriesId = "mistborn", homeBookId = null) shouldHaveSize 1
                    repo.listForWorld(homeSeriesId = null, homeBookId = "hoa1") shouldHaveSize 1
                    repo.listForWorld(homeSeriesId = null, homeBookId = null) shouldHaveSize 0
                }
            }
        }
    })
