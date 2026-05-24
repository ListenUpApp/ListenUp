package com.calypsan.listenup.client.data.repository

import app.cash.turbine.test
import com.calypsan.listenup.client.data.local.db.ListeningEventEntity
import com.calypsan.listenup.client.data.local.db.UserProfileEntity
import com.calypsan.listenup.client.data.local.db.UserStatsEntity
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardPeriod
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.TimeZone

@OptIn(ExperimentalTime::class)
class LeaderboardRepositoryImplTest :
    FunSpec({

        // ── Helpers ───────────────────────────────────────────────────────────

        fun db() = createInMemoryTestDatabase()

        fun statsEntity(
            id: String,
            totalSeconds: Long = 0L,
            booksFinished: Int = 0,
            currentStreakDays: Int = 0,
            longestStreakDays: Int = 0,
        ) = UserStatsEntity(
            id = id,
            totalSecondsAllTime = totalSeconds,
            totalSecondsLast7Days = 0L,
            totalSecondsLast30Days = 0L,
            booksStarted = 0,
            booksFinished = booksFinished,
            currentStreakDays = currentStreakDays,
            longestStreakDays = longestStreakDays,
            lastEventDate = null,
        )

        fun profileEntity(
            id: String,
            displayName: String,
        ) = UserProfileEntity(
            id = id,
            displayName = displayName,
            updatedAt = 0L,
        )

        fun eventEntity(
            id: String,
            userId: String,
            endedAt: Long,
            durationMs: Long = 30_000L,
        ) = ListeningEventEntity(
            id = id,
            userId = userId,
            bookId = "book-$id",
            startPositionMs = 0L,
            endPositionMs = durationMs,
            startedAt = endedAt - durationMs,
            endedAt = endedAt,
            playbackSpeed = 1.0f,
            tz = "UTC",
            deviceLabel = null,
        )

        // Fixed "now" at 2026-05-23 00:00:00 UTC
        val nowMs = 1_748_131_200_000L
        val nowInstant = Instant.fromEpochMilliseconds(nowMs)
        val fixedClock =
            object : Clock {
                override fun now(): Instant = nowInstant
            }

        // ── AllTime: all three categories from user_stats ─────────────────────

        test("AllTime snapshot builds all three category lists from user_stats") {
            runTest {
                val database = db()
                val repo =
                    LeaderboardRepositoryImpl(
                        userStatsDao = database.userStatsDao(),
                        listeningEventDao = database.listeningEventDao(),
                        clock = fixedClock,
                    )

                database.userStatsDao().upsertAll(
                    listOf(
                        statsEntity("u1", totalSeconds = 3600, booksFinished = 5, longestStreakDays = 10),
                        statsEntity("u2", totalSeconds = 7200, booksFinished = 2, longestStreakDays = 20),
                        statsEntity("u3", totalSeconds = 1800, booksFinished = 8, longestStreakDays = 5),
                    ),
                )

                repo.observeSnapshot(LeaderboardPeriod.AllTime).test {
                    val snapshot = awaitItem()

                    // time: u2 first (7200), then u1 (3600), then u3 (1800)
                    snapshot.time shouldHaveSize 3
                    snapshot.time[0].userId shouldBe "u2"
                    snapshot.time[0].rank shouldBe 1
                    snapshot.time[1].userId shouldBe "u1"
                    snapshot.time[1].rank shouldBe 2
                    snapshot.time[2].userId shouldBe "u3"
                    snapshot.time[2].rank shouldBe 3

                    // books: u3 first (8), then u1 (5), then u2 (2)
                    snapshot.books shouldHaveSize 3
                    snapshot.books[0].userId shouldBe "u3"
                    snapshot.books[0].rank shouldBe 1

                    // streak: u2 first (20), then u1 (10), then u3 (5)
                    snapshot.streak shouldHaveSize 3
                    snapshot.streak[0].userId shouldBe "u2"
                    snapshot.streak[0].rank shouldBe 1

                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        // ── AllTime: profile JOIN populates displayName ───────────────────────

        test("AllTime snapshot joins user_profiles for displayName") {
            runTest {
                val database = db()
                val repo =
                    LeaderboardRepositoryImpl(
                        userStatsDao = database.userStatsDao(),
                        listeningEventDao = database.listeningEventDao(),
                        clock = fixedClock,
                    )

                database.userStatsDao().upsertAll(listOf(statsEntity("u1", totalSeconds = 100)))
                database.userProfileDao().upsert(profileEntity("u1", "Alice"))

                repo.observeSnapshot(LeaderboardPeriod.AllTime).test {
                    val snapshot = awaitItem()
                    snapshot.time[0].displayName shouldBe "Alice"
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        // ── AllTime: missing profile falls back to "User" ─────────────────────

        test("AllTime snapshot uses fallback displayName when user_profiles row is absent") {
            runTest {
                val database = db()
                val repo =
                    LeaderboardRepositoryImpl(
                        userStatsDao = database.userStatsDao(),
                        listeningEventDao = database.listeningEventDao(),
                        clock = fixedClock,
                    )

                // Seed stats but no profile row
                database.userStatsDao().upsertAll(listOf(statsEntity("u1", totalSeconds = 100)))

                repo.observeSnapshot(LeaderboardPeriod.AllTime).test {
                    val snapshot = awaitItem()
                    snapshot.time[0].displayName shouldBe "User"
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        // ── AllTime: dense tie ranking ────────────────────────────────────────

        test("AllTime snapshot produces dense ranking for tied values") {
            runTest {
                val database = db()
                val repo =
                    LeaderboardRepositoryImpl(
                        userStatsDao = database.userStatsDao(),
                        listeningEventDao = database.listeningEventDao(),
                        clock = fixedClock,
                    )

                // Three users with identical time → all rank 1; then a lower value → rank 4
                database.userStatsDao().upsertAll(
                    listOf(
                        statsEntity("u1", totalSeconds = 500),
                        statsEntity("u2", totalSeconds = 500),
                        statsEntity("u3", totalSeconds = 500),
                        statsEntity("u4", totalSeconds = 100),
                    ),
                )

                repo.observeSnapshot(LeaderboardPeriod.AllTime).test {
                    val snapshot = awaitItem()
                    val ranks = snapshot.time.map { it.rank }
                    ranks[0] shouldBe 1
                    ranks[1] shouldBe 1
                    ranks[2] shouldBe 1
                    ranks[3] shouldBe 4 // skips 2 and 3 — dense ranking
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        // ── Week: time from listening_events; books and streak empty ──────────

        test("Week snapshot builds time list from listening_events within window") {
            runTest {
                val database = db()
                val repo =
                    LeaderboardRepositoryImpl(
                        userStatsDao = database.userStatsDao(),
                        listeningEventDao = database.listeningEventDao(),
                        clock = fixedClock,
                        timeZone = { TimeZone.UTC },
                    )

                // nowMs = 2026-05-23 00:00:00 UTC; week window starts 6 days back
                val inWindow = nowMs - (3 * 86_400_000L) // 3 days ago — inside window
                val outOfWindow = nowMs - (8 * 86_400_000L) // 8 days ago — outside window

                database.listeningEventDao().upsertAll(
                    listOf(
                        // u1: 60s in window
                        eventEntity("e1", "u1", endedAt = inWindow, durationMs = 60_000L),
                        // u2: 120s in window
                        eventEntity("e2", "u2", endedAt = inWindow, durationMs = 120_000L),
                        // u3: outside window — should not appear
                        eventEntity("e3", "u3", endedAt = outOfWindow, durationMs = 60_000L),
                    ),
                )
                database.userProfileDao().upsert(profileEntity("u1", "Bob"))
                database.userProfileDao().upsert(profileEntity("u2", "Carol"))

                repo.observeSnapshot(LeaderboardPeriod.Week).test {
                    val snapshot = awaitItem()

                    // Only in-window users appear
                    snapshot.time shouldHaveSize 2
                    snapshot.time[0].userId shouldBe "u2" // 120s > 60s
                    snapshot.time[0].totalSeconds shouldBe 120L
                    snapshot.time[0].displayName shouldBe "Carol"
                    snapshot.time[1].userId shouldBe "u1"
                    snapshot.time[1].totalSeconds shouldBe 60L
                    snapshot.time[1].displayName shouldBe "Bob"

                    // books and streak are empty for bounded periods
                    snapshot.books.shouldBeEmpty()
                    snapshot.streak.shouldBeEmpty()

                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        // ── Snapshot carries all three lists — category switch needs no re-fetch ─

        test("Single AllTime emission contains all three pre-computed lists") {
            runTest {
                val database = db()
                val repo =
                    LeaderboardRepositoryImpl(
                        userStatsDao = database.userStatsDao(),
                        listeningEventDao = database.listeningEventDao(),
                        clock = fixedClock,
                    )

                database.userStatsDao().upsertAll(
                    listOf(statsEntity("u1", totalSeconds = 1000, booksFinished = 3, longestStreakDays = 7)),
                )

                repo.observeSnapshot(LeaderboardPeriod.AllTime).test {
                    val snapshot = awaitItem()
                    // All three lists available in one emission — no second subscription needed
                    snapshot.time shouldHaveSize 1
                    snapshot.books shouldHaveSize 1
                    snapshot.streak shouldHaveSize 1
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }
    })
