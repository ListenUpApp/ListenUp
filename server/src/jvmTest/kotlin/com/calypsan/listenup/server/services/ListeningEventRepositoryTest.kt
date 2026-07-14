@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.activity.ActivityType
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.ListeningEventSyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.core.ListeningEventId
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.PublicProfileRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.activityRecorder
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

class ListeningEventRepositoryTest :
    FunSpec({

        test("upsert inserts a new event and publishes a Created BusEvent for that userId") {
            withSqlDatabase {
                val bus = ChangeBus()
                val repo = ListeningEventRepository(db = sql, bus = bus, registry = SyncRegistry())
                runTest {
                    val deferred = async { bus.subscribe().first() }
                    advanceUntilIdle()

                    val payload = listeningEventPayload("evt-1", "book-1")
                    val result = repo.upsert(payload, clientOpId = null, userId = "u1")
                    result.shouldBeInstanceOf<AppResult.Success<*>>()

                    val busEvent = deferred.await()
                    busEvent.userId shouldBe "u1"
                    busEvent.repo.domainName shouldBe "listening_events"
                    busEvent.event.shouldBeInstanceOf<SyncEvent.Created<*>>()
                }
            }
        }

        test("upsert of an existing id is idempotent — domain fields unchanged, revision advances") {
            withSqlDatabase {
                val repo = ListeningEventRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    val original = listeningEventPayload("evt-2", "book-2", startPositionMs = 1_000L)
                    val first = (repo.upsert(original, clientOpId = null, userId = "u1") as AppResult.Success).data

                    // Re-upsert with different domain fields — should be ignored
                    val duplicate = original.copy(startPositionMs = 9_999L)
                    val second = (repo.upsert(duplicate, clientOpId = null, userId = "u1") as AppResult.Success).data

                    // Domain field unchanged
                    second.startPositionMs shouldBe 1_000L
                    // Revision advanced
                    (second.revision > first.revision) shouldBe true
                }
            }
        }

        test("a re-upsert of another user's event id cannot stomp that row (cross-user authz)") {
            withSqlDatabase {
                val repo = ListeningEventRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    // Victim userB owns the event.
                    val victim =
                        (
                            repo.upsert(
                                listeningEventPayload("shared-id", "book-b", startPositionMs = 1_000L),
                                clientOpId = "op-B",
                                userId = "userB",
                            ) as AppResult.Success
                        ).data

                    // Attacker userA replays the SAME id with attacker-controlled fields.
                    repo.upsert(
                        listeningEventPayload("shared-id", "book-a", startPositionMs = 55_555L),
                        clientOpId = "op-A",
                        userId = "userA",
                    )

                    // userB's row is untouched — same owner, domain fields, and sync columns.
                    val row = sql.listeningEventsQueries.selectById("shared-id").executeAsOne()
                    row.user_id shouldBe "userB"
                    row.book_id shouldBe "book-b"
                    row.start_position_ms shouldBe 1_000L
                    row.client_op_id shouldBe "op-B"
                    row.revision shouldBe victim.revision
                }
            }
        }

        test("pullSince(userId = u1) returns only u1's events") {
            withSqlDatabase {
                val repo = ListeningEventRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    repo.upsert(listeningEventPayload("evt-u1", "book-1"), clientOpId = null, userId = "u1")
                    repo.upsert(listeningEventPayload("evt-u2", "book-1"), clientOpId = null, userId = "u2")

                    val page = repo.pullSince(userId = "u1", cursor = 0L, limit = 50)
                    page.items.size shouldBe 1
                    page.items.first().id shouldBe "evt-u1"
                }
            }
        }

        test("pullSince with null userId fails fast for user-scoped domain") {
            withSqlDatabase {
                val repo = ListeningEventRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    val threw = runCatching { repo.pullSince(userId = null, cursor = 0L, limit = 50) }
                    threw.isFailure shouldBe true
                }
            }
        }

        test("idAsString unwraps the value class to its raw string") {
            withSqlDatabase {
                val repo = ListeningEventRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                repo.idAsStringForTest(ListeningEventId("evt-1")) shouldBe "evt-1"
            }
        }

        test("upsert with statsRecorder wired fires the cascade and materialises totalSecondsAllTime") {
            withSqlDatabase {
                sql.seedTestUser("u-wire")
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val statsRepo = UserStatsRepository(db = sql, bus = bus, registry = registry)
                val publicProfileRepo = PublicProfileRepository(db = sql, bus = bus, registry = registry)
                val recorder =
                    StatsRecorder(
                        sql = sql,
                        userStatsRepo = statsRepo,
                        bookReadsRepository = BookReadsRepository(db = sql),
                        publicProfileMaintainer = PublicProfileMaintainer(sql = sql, publicProfileRepo = publicProfileRepo),
                        activityRecorder = activityRecorder(bus = bus),
                        statsBackfill = UserStatsBackfillService(sql = sql, userStatsRepo = statsRepo),
                    )
                val repo =
                    ListeningEventRepository(
                        db = sql,
                        bus = ChangeBus(),
                        registry = SyncRegistry(),
                        statsRecorder = recorder,
                    )
                runTest {
                    val payload = listeningEventPayload("evt-wire-1", "book-wire-1")
                    repo.upsert(payload, clientOpId = null, userId = "u-wire")

                    val stats = statsRepo.getForUser("u-wire").shouldNotBeNull()
                    stats.totalSecondsAllTime shouldBe 60L
                }
            }
        }
        test("a completed listening event records one listening_session with durationMs == endedAt - startedAt") {
            withSqlDatabase {
                sql.seedTestUser("u-act")
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val activities = ActivityRepository(db = sql)
                val activityRecorder = activityRecorder(bus = bus)
                val statsRepo = UserStatsRepository(db = sql, bus = bus, registry = registry)
                val publicProfileRepo = PublicProfileRepository(db = sql, bus = bus, registry = registry)
                val recorder =
                    StatsRecorder(
                        sql = sql,
                        userStatsRepo = statsRepo,
                        bookReadsRepository = BookReadsRepository(db = sql),
                        publicProfileMaintainer = PublicProfileMaintainer(sql = sql, publicProfileRepo = publicProfileRepo),
                        activityRecorder = activityRecorder,
                        statsBackfill = UserStatsBackfillService(sql = sql, userStatsRepo = statsRepo),
                    )
                val repo =
                    ListeningEventRepository(
                        db = sql,
                        bus = ChangeBus(),
                        registry = SyncRegistry(),
                        statsRecorder = recorder,
                    )
                runTest {
                    // 60-second span: endedAt - startedAt = 60_000 ms
                    repo.upsert(listeningEventPayload("evt-act-1", "book-act-1"), clientOpId = null, userId = "u-act")

                    val sessions =
                        activities.page(before = null, limit = 50).filter { it.type == ActivityType.LISTENING_SESSION }
                    sessions shouldHaveSize 1
                    sessions.single().bookId shouldBe "book-act-1"
                    sessions.single().durationMs shouldBe 60_000L
                }
            }
        }

        test("re-firing an already-committed listening event records NO duplicate listening_session") {
            withSqlDatabase {
                sql.seedTestUser("u-act")
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val activities = ActivityRepository(db = sql)
                val activityRecorder = activityRecorder(bus = bus)
                val statsRepo = UserStatsRepository(db = sql, bus = bus, registry = registry)
                val publicProfileRepo = PublicProfileRepository(db = sql, bus = bus, registry = registry)
                val recorder =
                    StatsRecorder(
                        sql = sql,
                        userStatsRepo = statsRepo,
                        bookReadsRepository = BookReadsRepository(db = sql),
                        publicProfileMaintainer = PublicProfileMaintainer(sql = sql, publicProfileRepo = publicProfileRepo),
                        activityRecorder = activityRecorder,
                        statsBackfill = UserStatsBackfillService(sql = sql, userStatsRepo = statsRepo),
                    )
                val repo =
                    ListeningEventRepository(
                        db = sql,
                        bus = ChangeBus(),
                        registry = SyncRegistry(),
                        statsRecorder = recorder,
                    )
                runTest {
                    val payload = listeningEventPayload("evt-act-dup", "book-act-dup")
                    repo.upsert(payload, clientOpId = null, userId = "u-act")
                    // Ack-lost re-fire of the SAME id
                    repo.upsert(payload.copy(startPositionMs = 9_999L), clientOpId = "retry", userId = "u-act")

                    val sessions =
                        activities.page(before = null, limit = 50).filter { it.type == ActivityType.LISTENING_SESSION }
                    sessions shouldHaveSize 1
                }
            }
        }

        // Regression: ABS-imported events carry "abs:<uuid>" ids (40 chars). ListeningEventTable.id
        // was varchar(36), causing Exposed to throw "Value can't be stored to database column because
        // exceeds length (40 > 36)" on every session import — apply always returned ApplyFailed.
        test("upsert accepts a 40-char abs:<uuid> id produced by SessionConverter") {
            withSqlDatabase {
                val repo = ListeningEventRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    // "abs:" (4) + 36-char UUID = 40 chars — the real id shape emitted by SessionConverter.
                    val absId = "abs:11111111-1111-1111-1111-111111111111"
                    val payload = listeningEventPayload(absId, "book-abs-regression")
                    val result = repo.upsert(payload, clientOpId = null, userId = "u-abs")
                    result.shouldBeInstanceOf<AppResult.Success<*>>()

                    // Confirm the row landed — pullSince returns all rows at revision >= 0.
                    val page = repo.pullSince(userId = "u-abs", cursor = 0L, limit = 50)
                    page.items.shouldHaveSize(1)
                    page.items.single().id shouldBe absId
                }
            }
        }

        // Regression: imported listening sessions (whose real end time is in the past) were
        // stamped at clock.now() (import time) instead of the session's real endedAt.
        // The feed orders by occurred_at, so import sessions landed at import time in the feed
        // rather than at their actual play date.
        test("imported listening session stamps the activity at the session's real end time") {
            withSqlDatabase {
                sql.seedTestUser("u-import")
                val realEndedAt = 1_000_000L
                val fixedNow = 9_999_999_999L
                val fixedClock = FixedClock(Instant.fromEpochMilliseconds(fixedNow))
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val activities = ActivityRepository(db = sql)
                val activityRecorder =
                    ActivityRecorder(
                        syncRepo = ActivitySyncRepository(db = sql, bus = bus, registry = SyncRegistry(), driver = driver),
                        clock = fixedClock,
                    )
                val statsRepo = UserStatsRepository(db = sql, bus = bus, registry = registry)
                val publicProfileRepo = PublicProfileRepository(db = sql, bus = bus, registry = registry)
                val recorder =
                    StatsRecorder(
                        sql = sql,
                        userStatsRepo = statsRepo,
                        bookReadsRepository = BookReadsRepository(db = sql),
                        publicProfileMaintainer = PublicProfileMaintainer(sql = sql, publicProfileRepo = publicProfileRepo),
                        activityRecorder = activityRecorder,
                        statsBackfill = UserStatsBackfillService(sql = sql, userStatsRepo = statsRepo),
                    )
                val repo =
                    ListeningEventRepository(
                        db = sql,
                        bus = ChangeBus(),
                        registry = SyncRegistry(),
                        clock = fixedClock,
                        statsRecorder = recorder,
                    )
                runTest {
                    val payload =
                        listeningEventPayload(
                            "evt-import-1",
                            "book-import-1",
                            startedAt = realEndedAt - 60_000L,
                            endedAt = realEndedAt,
                        )
                    repo.upsert(payload, clientOpId = null, userId = "u-import")

                    val activity =
                        activities.page(before = null, limit = 10).single { it.type == ActivityType.LISTENING_SESSION }
                    activity.occurredAt shouldBe realEndedAt
                    activity.createdAt shouldBe fixedNow
                }
            }
        }
    })

private fun listeningEventPayload(
    id: String,
    bookId: String,
    startPositionMs: Long = 0L,
    startedAt: Long = 1_730_000_000_000L,
    endedAt: Long = 1_730_000_060_000L,
): ListeningEventSyncPayload =
    ListeningEventSyncPayload(
        id = id,
        bookId = bookId,
        startPositionMs = startPositionMs,
        endPositionMs = startPositionMs + 60_000L,
        startedAt = startedAt,
        endedAt = endedAt,
        playbackSpeed = 1.0f,
        tz = "Europe/London",
        deviceLabel = null,
        revision = 0L,
        updatedAt = 0L,
        createdAt = 0L,
        deletedAt = null,
    )
