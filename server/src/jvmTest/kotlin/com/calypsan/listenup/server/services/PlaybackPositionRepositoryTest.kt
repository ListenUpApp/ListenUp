@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.activity.ActivityType
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.core.PlaybackPositionId
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.PublicProfileRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.activityRecorder
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

class PlaybackPositionRepositoryTest :
    FunSpec({

        test("recordPosition inserts a new row and publishes a Created BusEvent for that userId") {
            withSqlDatabase {
                val bus = ChangeBus()
                val repo = PlaybackPositionRepository(db = sql, bus = bus, registry = SyncRegistry())
                runTest {
                    val deferred = async { bus.subscribe().first() }
                    advanceUntilIdle()

                    val result =
                        repo.recordPosition(
                            userId = "u1",
                            bookId = "book-1",
                            positionMs = 42_000L,
                            lastPlayedAt = 1_730_000_000_000L,
                            finished = false,
                            playbackSpeed = 1.0f,
                            currentChapterId = "chap-1",
                        )
                    result.shouldBeInstanceOf<AppResult.Success<*>>()

                    val busEvent = deferred.await()
                    busEvent.userId shouldBe "u1"
                    busEvent.repo.domainName shouldBe "playback_positions"
                    val event = busEvent.event
                    event.shouldBeInstanceOf<SyncEvent.Created<*>>()
                }
            }
        }

        test("recordPosition with a greater lastPlayedAt updates the existing row") {
            withSqlDatabase {
                val repo = PlaybackPositionRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    repo.recordPosition(
                        userId = "u1",
                        bookId = "book-1",
                        positionMs = 42_000L,
                        lastPlayedAt = 1_730_000_000_000L,
                        finished = false,
                        playbackSpeed = 1.0f,
                        currentChapterId = null,
                    )

                    val result =
                        repo.recordPosition(
                            userId = "u1",
                            bookId = "book-1",
                            positionMs = 99_000L,
                            lastPlayedAt = 1_730_000_999_000L,
                            finished = false,
                            playbackSpeed = 1.25f,
                            currentChapterId = "chap-2",
                        )
                    result.shouldBeInstanceOf<AppResult.Success<*>>()

                    val stored = repo.getPosition("u1", "book-1").shouldNotBeNull()
                    stored.positionMs shouldBe 99_000L
                    stored.playbackSpeed shouldBe 1.25f
                    stored.currentChapterId shouldBe "chap-2"
                }
            }
        }

        test("recordPosition with a stale (lesser) lastPlayedAt is a no-op — stored position unchanged") {
            withSqlDatabase {
                val repo = PlaybackPositionRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    repo.recordPosition(
                        userId = "u1",
                        bookId = "book-1",
                        positionMs = 99_000L,
                        lastPlayedAt = 1_730_000_999_000L,
                        finished = false,
                        playbackSpeed = 1.0f,
                        currentChapterId = null,
                    )

                    val result =
                        repo.recordPosition(
                            userId = "u1",
                            bookId = "book-1",
                            positionMs = 10_000L,
                            lastPlayedAt = 1_720_000_000_000L, // older
                            finished = true,
                            playbackSpeed = 2.0f,
                            currentChapterId = "chap-stale",
                        )
                    result.shouldBeInstanceOf<AppResult.Success<*>>()

                    val stored = repo.getPosition("u1", "book-1").shouldNotBeNull()
                    stored.positionMs shouldBe 99_000L
                    stored.playbackSpeed shouldBe 1.0f
                    stored.currentChapterId shouldBe null
                }
            }
        }

        test("pullSince(userId = u1) returns only u1's positions") {
            withSqlDatabase {
                val repo = PlaybackPositionRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    repo.recordPosition(
                        userId = "u1",
                        bookId = "book-1",
                        positionMs = 1_000L,
                        lastPlayedAt = 1_730_000_000_000L,
                        finished = false,
                        playbackSpeed = 1.0f,
                        currentChapterId = null,
                    )
                    repo.recordPosition(
                        userId = "u2",
                        bookId = "book-1",
                        positionMs = 2_000L,
                        lastPlayedAt = 1_730_000_000_000L,
                        finished = false,
                        playbackSpeed = 1.0f,
                        currentChapterId = null,
                    )

                    val u1Page = repo.pullSince(userId = "u1", cursor = 0L, limit = 50)
                    u1Page.items.all { it.bookId == "book-1" } shouldBe true
                    u1Page.items.size shouldBe 1
                    u1Page.items.first().positionMs shouldBe 1_000L
                }
            }
        }

        test("getPosition returns the stored payload") {
            withSqlDatabase {
                val repo = PlaybackPositionRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    repo.recordPosition(
                        userId = "u1",
                        bookId = "book-abc",
                        positionMs = 5_000L,
                        lastPlayedAt = 1_730_000_000_000L,
                        finished = true,
                        playbackSpeed = 1.5f,
                        currentChapterId = "chap-x",
                    )

                    val position = repo.getPosition("u1", "book-abc").shouldNotBeNull()
                    position.bookId shouldBe "book-abc"
                    position.positionMs shouldBe 5_000L
                    position.finished shouldBe true
                    position.playbackSpeed shouldBe 1.5f
                    position.currentChapterId shouldBe "chap-x"
                }
            }
        }

        test("getPosition returns null for an absent (userId, bookId) pair") {
            withSqlDatabase {
                val repo = PlaybackPositionRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    repo.getPosition("u1", "nonexistent-book").shouldBeNull()
                }
            }
        }

        test("idAsString unwraps the value class to its raw string") {
            withSqlDatabase {
                val repo = PlaybackPositionRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                repo.idAsStringForTest(PlaybackPositionId("pos-42")) shouldBe "pos-42"
            }
        }

        test("recordPosition false→true flip increments booksFinished via StatsRecorder") {
            withSqlDatabase {
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
                    PlaybackPositionRepository(
                        db = sql,
                        bus = ChangeBus(),
                        registry = SyncRegistry(),
                        statsRecorder = recorder,
                    )
                runTest {
                    // First call: not finished — no flip
                    repo.recordPosition(
                        userId = "u-flip",
                        bookId = "book-flip",
                        positionMs = 10_000L,
                        lastPlayedAt = 1_730_000_000_000L,
                        finished = false,
                        playbackSpeed = 1.0f,
                        currentChapterId = null,
                    )
                    statsRepo.getForUser("u-flip")?.booksFinished shouldBe null

                    // Second call: finished=true — flip fires
                    repo.recordPosition(
                        userId = "u-flip",
                        bookId = "book-flip",
                        positionMs = 99_000L,
                        lastPlayedAt = 1_730_000_999_000L,
                        finished = true,
                        playbackSpeed = 1.0f,
                        currentChapterId = null,
                    )
                    statsRepo.getForUser("u-flip").shouldNotBeNull().booksFinished shouldBe 1
                }
            }
        }

        test("recordPosition finished=true on a new row (no prior) also counts as a flip") {
            withSqlDatabase {
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
                    PlaybackPositionRepository(
                        db = sql,
                        bus = ChangeBus(),
                        registry = SyncRegistry(),
                        statsRecorder = recorder,
                    )
                runTest {
                    repo.recordPosition(
                        userId = "u-new-fin",
                        bookId = "book-new",
                        positionMs = 99_000L,
                        lastPlayedAt = 1_730_000_000_000L,
                        finished = true,
                        playbackSpeed = 1.0f,
                        currentChapterId = null,
                    )
                    statsRepo.getForUser("u-new-fin").shouldNotBeNull().booksFinished shouldBe 1
                }
            }
        }

        test("recordPosition: finished=false → finished=true flip hard-deletes the active_sessions row") {
            withSqlDatabase {
                val activeSessionRepo = ActiveSessionRepository(db = sql, bus = ChangeBus())
                val repo =
                    PlaybackPositionRepository(
                        db = sql,
                        bus = ChangeBus(),
                        registry = SyncRegistry(),
                        activeSessionRepo = activeSessionRepo,
                    )
                runTest {
                    // Seed an active session for (u1, book-flip)
                    activeSessionRepo.startOrRefresh("u1", "book-flip")

                    // First record: finished=false — no flip, session survives
                    repo.recordPosition(
                        userId = "u1",
                        bookId = "book-flip",
                        positionMs = 10_000L,
                        lastPlayedAt = 1_730_000_000_000L,
                        finished = false,
                        playbackSpeed = 1.0f,
                        currentChapterId = null,
                    )
                    activeSessionRepo.listReadersForBook("book-flip", excludeUserId = "none") shouldHaveSize 1

                    // Second record: finished=true — flip fires, session deleted
                    repo.recordPosition(
                        userId = "u1",
                        bookId = "book-flip",
                        positionMs = 99_000L,
                        lastPlayedAt = 1_730_000_999_000L,
                        finished = true,
                        playbackSpeed = 1.0f,
                        currentChapterId = null,
                    )
                    activeSessionRepo.listReadersForBook("book-flip", excludeUserId = "none").shouldBeEmpty()
                }
            }
        }

        test("recordPosition: finished=true on new (no prior position) also deletes the active_sessions row") {
            withSqlDatabase {
                val activeSessionRepo = ActiveSessionRepository(db = sql, bus = ChangeBus())
                val repo =
                    PlaybackPositionRepository(
                        db = sql,
                        bus = ChangeBus(),
                        registry = SyncRegistry(),
                        activeSessionRepo = activeSessionRepo,
                    )
                runTest {
                    activeSessionRepo.startOrRefresh("u-new-fin", "book-new")

                    repo.recordPosition(
                        userId = "u-new-fin",
                        bookId = "book-new",
                        positionMs = 99_000L,
                        lastPlayedAt = 1_730_000_000_000L,
                        finished = true,
                        playbackSpeed = 1.0f,
                        currentChapterId = null,
                    )
                    activeSessionRepo.listReadersForBook("book-new", excludeUserId = "none").shouldBeEmpty()
                }
            }
        }

        test("recordPosition: finished=true when priorFinished=true is a no-op for active_sessions") {
            withSqlDatabase {
                val activeSessionRepo = ActiveSessionRepository(db = sql, bus = ChangeBus())
                val repo =
                    PlaybackPositionRepository(
                        db = sql,
                        bus = ChangeBus(),
                        registry = SyncRegistry(),
                        activeSessionRepo = activeSessionRepo,
                    )
                runTest {
                    // Seed a position already at finished=true
                    repo.recordPosition(
                        userId = "u1",
                        bookId = "book-already-done",
                        positionMs = 99_000L,
                        lastPlayedAt = 1_730_000_000_000L,
                        finished = true,
                        playbackSpeed = 1.0f,
                        currentChapterId = null,
                    )
                    // Seed a separate active session
                    activeSessionRepo.startOrRefresh("u1", "book-already-done")

                    // Re-record with finished=true (no flip — priorFinished was already true)
                    repo.recordPosition(
                        userId = "u1",
                        bookId = "book-already-done",
                        positionMs = 100_000L,
                        lastPlayedAt = 1_730_000_999_000L,
                        finished = true,
                        playbackSpeed = 1.0f,
                        currentChapterId = null,
                    )
                    // Session NOT deleted — no flip event fired
                    activeSessionRepo.listReadersForBook("book-already-done", excludeUserId = "none") shouldHaveSize 1
                }
            }
        }

        test("recordPosition: finished=false does not touch active_sessions") {
            withSqlDatabase {
                val activeSessionRepo = ActiveSessionRepository(db = sql, bus = ChangeBus())
                val repo =
                    PlaybackPositionRepository(
                        db = sql,
                        bus = ChangeBus(),
                        registry = SyncRegistry(),
                        activeSessionRepo = activeSessionRepo,
                    )
                runTest {
                    activeSessionRepo.startOrRefresh("u1", "book-in-progress")

                    repo.recordPosition(
                        userId = "u1",
                        bookId = "book-in-progress",
                        positionMs = 10_000L,
                        lastPlayedAt = 1_730_000_000_000L,
                        finished = false,
                        playbackSpeed = 1.0f,
                        currentChapterId = null,
                    )
                    activeSessionRepo.listReadersForBook("book-in-progress", excludeUserId = "none") shouldHaveSize 1
                }
            }
        }

        test("recordPosition finish-flip records exactly one finished_book for (user, book)") {
            withSqlDatabase {
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val userStatsRepo = UserStatsRepository(db = sql, bus = bus, registry = registry)
                val bookReadsRepo = BookReadsRepository(db = sql)
                val publicProfileRepo = PublicProfileRepository(db = sql, bus = bus, registry = registry)
                val publicProfileMaintainer = PublicProfileMaintainer(sql = sql, publicProfileRepo = publicProfileRepo)
                val activities = ActivityRepository(db = sql)
                val activityRecorder = activityRecorder(bus = bus)
                val statsBackfill = UserStatsBackfillService(sql = sql, userStatsRepo = userStatsRepo)
                val recorder =
                    StatsRecorder(
                        sql = sql,
                        userStatsRepo = userStatsRepo,
                        bookReadsRepository = bookReadsRepo,
                        publicProfileMaintainer = publicProfileMaintainer,
                        activityRecorder = activityRecorder,
                        statsBackfill = statsBackfill,
                    )
                val repo =
                    PlaybackPositionRepository(
                        db = sql,
                        bus = ChangeBus(),
                        registry = SyncRegistry(),
                        statsRecorder = recorder,
                    )
                runTest {
                    // First record: in progress — no finish
                    repo.recordPosition(
                        userId = "u1",
                        bookId = "book-1",
                        positionMs = 10_000L,
                        lastPlayedAt = 1_730_000_000_000L,
                        finished = false,
                        playbackSpeed = 1.0f,
                        currentChapterId = null,
                    )
                    // Second record: finished=true — flip fires
                    repo.recordPosition(
                        userId = "u1",
                        bookId = "book-1",
                        positionMs = 99_000L,
                        lastPlayedAt = 1_730_000_999_000L,
                        finished = true,
                        playbackSpeed = 1.0f,
                        currentChapterId = null,
                    )

                    val finished = activities.page(before = null, limit = 50).filter { it.type == ActivityType.FINISHED_BOOK }
                    finished shouldHaveSize 1
                    finished.single().userId shouldBe "u1"
                    finished.single().bookId shouldBe "book-1"
                }
            }
        }

        test("recordPosition first-ever in-progress position records one started_book (isReread=false)") {
            withSqlDatabase {
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val statsRepo = UserStatsRepository(db = sql, bus = bus, registry = registry)
                val publicProfileRepo = PublicProfileRepository(db = sql, bus = bus, registry = registry)
                val activities = ActivityRepository(db = sql)
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
                    PlaybackPositionRepository(
                        db = sql,
                        bus = ChangeBus(),
                        registry = SyncRegistry(),
                        statsRecorder = recorder,
                    )
                runTest {
                    repo.recordPosition(
                        userId = "u1",
                        bookId = "book-1",
                        positionMs = 10_000L,
                        lastPlayedAt = 1_730_000_000_000L,
                        finished = false,
                        playbackSpeed = 1.0f,
                        currentChapterId = null,
                    )

                    val started = activities.page(before = null, limit = 50).filter { it.type == ActivityType.STARTED_BOOK }
                    started shouldHaveSize 1
                    started.single().bookId shouldBe "book-1"
                    started.single().isReread shouldBe false
                }
            }
        }

        test("recordPosition re-read (prior finished, new in-progress) records one started_book with isReread=true") {
            withSqlDatabase {
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val statsRepo = UserStatsRepository(db = sql, bus = bus, registry = registry)
                val publicProfileRepo = PublicProfileRepository(db = sql, bus = bus, registry = registry)
                val activities = ActivityRepository(db = sql)
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
                    PlaybackPositionRepository(
                        db = sql,
                        bus = ChangeBus(),
                        registry = SyncRegistry(),
                        statsRecorder = recorder,
                    )
                runTest {
                    // Seed a finished position
                    repo.recordPosition(
                        userId = "u1",
                        bookId = "book-1",
                        positionMs = 99_000L,
                        lastPlayedAt = 1_730_000_000_000L,
                        finished = true,
                        playbackSpeed = 1.0f,
                        currentChapterId = null,
                    )
                    // Re-open it (in progress again) — a re-read
                    repo.recordPosition(
                        userId = "u1",
                        bookId = "book-1",
                        positionMs = 5_000L,
                        lastPlayedAt = 1_730_000_999_000L,
                        finished = false,
                        playbackSpeed = 1.0f,
                        currentChapterId = null,
                    )

                    val started = activities.page(before = null, limit = 50).filter { it.type == ActivityType.STARTED_BOOK }
                    started shouldHaveSize 1
                    started.single().bookId shouldBe "book-1"
                    started.single().isReread shouldBe true
                }
            }
        }

        test("recordPosition with activeSessionRepo=null does not throw on flip") {
            withSqlDatabase {
                val repo =
                    PlaybackPositionRepository(
                        db = sql,
                        bus = ChangeBus(),
                        registry = SyncRegistry(),
                        activeSessionRepo = null,
                    )
                runTest {
                    // Should complete without throwing even though activeSessionRepo is null
                    val result =
                        repo.recordPosition(
                            userId = "u1",
                            bookId = "book-1",
                            positionMs = 99_000L,
                            lastPlayedAt = 1_730_000_000_000L,
                            finished = true,
                            playbackSpeed = 1.0f,
                            currentChapterId = null,
                        )
                    result.shouldBeInstanceOf<AppResult.Success<*>>()
                }
            }
        }

        test("finishing a book appends a book_reads row dated lastPlayedAt") {
            withSqlDatabase {
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val statsRepo = UserStatsRepository(db = sql, bus = bus, registry = registry)
                val publicProfileRepo = PublicProfileRepository(db = sql, bus = bus, registry = registry)
                val reads =
                    BookReadsRepository(
                        db = sql,
                        clock = FixedClock(Instant.fromEpochMilliseconds(1_700_000_000_000L)),
                    )
                val recorder =
                    StatsRecorder(
                        sql = sql,
                        userStatsRepo = statsRepo,
                        bookReadsRepository = reads,
                        publicProfileMaintainer = PublicProfileMaintainer(sql = sql, publicProfileRepo = publicProfileRepo),
                        activityRecorder = activityRecorder(bus = bus),
                        statsBackfill = UserStatsBackfillService(sql = sql, userStatsRepo = statsRepo),
                    )
                val repo =
                    PlaybackPositionRepository(
                        db = sql,
                        bus = ChangeBus(),
                        registry = SyncRegistry(),
                        statsRecorder = recorder,
                    )
                runTest {
                    repo.recordPosition(
                        userId = "u1",
                        bookId = "b1",
                        positionMs = 5_000L,
                        lastPlayedAt = 1_700_000_000_000L,
                        finished = true,
                        playbackSpeed = 1f,
                        currentChapterId = null,
                    )
                    reads.finishesForUserBook("u1", "b1") shouldBe listOf(1_700_000_000_000L)
                }
            }
        }
    })
