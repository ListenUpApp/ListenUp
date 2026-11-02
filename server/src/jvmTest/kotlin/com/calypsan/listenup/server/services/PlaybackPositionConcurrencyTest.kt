package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.RecordPositionResult
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.PublicProfileRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.activityRecorder
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

private const val BASE = 1_730_000_000_000L
private const val WRITERS = 32
private const val ROUNDS = 20

/**
 * Two devices saving one book at once: the read-decide-write in `recordPositionDetailed` spans two
 * transactions, so without a per-(user, book) lock the older write can commit last, concurrent first
 * saves can clash on the unique index, and a finish can be counted twice.
 */
class PlaybackPositionConcurrencyTest :
    FunSpec({
        suspend fun PlaybackPositionRepository.saveFrom(
            userId: String,
            bookId: String,
            i: Int,
            finished: Boolean = false,
        ) = recordPositionDetailed(
            userId = userId,
            bookId = bookId,
            positionMs = i * 1_000L,
            lastPlayedAt = BASE + i,
            finished = finished,
            playbackSpeed = 1f,
            currentChapterId = null,
        )

        test("concurrent first saves never fail") {
            withSqlDatabase {
                runTest {
                    repeat(ROUNDS) { round ->
                        val repo = PlaybackPositionRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                        val results =
                            withContext(Dispatchers.Default) {
                                (1..WRITERS).map { i -> async { repo.saveFrom("u1", "first-$round", i) } }.awaitAll()
                            }
                        results shouldHaveSize WRITERS
                        results.forEach { it.shouldBeInstanceOf<AppResult.Success<RecordPositionResult>>() }
                    }
                }
            }
        }

        test("the newest lastPlayedAt always wins under concurrency") {
            withSqlDatabase {
                runTest {
                    repeat(ROUNDS) { round ->
                        val repo = PlaybackPositionRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                        withContext(Dispatchers.Default) {
                            (1..WRITERS).map { i -> async { repo.saveFrom("u1", "newest-$round", i) } }.awaitAll()
                        }
                        val stored = repo.getPosition("u1", "newest-$round").shouldNotBeNull()
                        stored.lastPlayedAt shouldBe BASE + WRITERS
                        stored.positionMs shouldBe WRITERS * 1_000L
                    }
                }
            }
        }

        test("a finishing race completes the book once") {
            withSqlDatabase {
                sql.seedTestUser("u-race")
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book-race")
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
                    withContext(Dispatchers.Default) {
                        (1..8).map { i -> async { repo.saveFrom("u-race", "book-race", i, finished = true) } }.awaitAll()
                    }
                    statsRepo.getForUser("u-race").shouldNotBeNull().booksFinished shouldBe 1
                }
            }
        }
    })
