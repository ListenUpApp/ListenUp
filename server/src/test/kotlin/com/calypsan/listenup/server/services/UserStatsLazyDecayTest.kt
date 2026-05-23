@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.sync.ListeningEventSyncPayload
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldNotBeNull
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * Verifies the lazy window-decay logic on [UserStatsRepository.pullSince]:
 *
 *  - A stale stats row (updatedAt > 1 hour ago) triggers [UserStatsUpdater.recomputeWindowsOnly]
 *    before the page is returned, so the returned row reflects current windows.
 *  - A fresh stats row (updatedAt within the last hour) is returned unchanged —
 *    no recompute is fired.
 *
 * Strategy: we seed the stats row using a repo whose clock is set to `thenMs`
 * (the historical time), so `updatedAt` is written as `thenMs`. Then we evaluate
 * staleness with a repo whose clock is set to `nowMs`. This way we can precisely
 * control the apparent age of the stats row without raw SQL.
 */
class UserStatsLazyDecayTest :
    FunSpec({

        // Base instant: 2026-05-22 12:00:00 UTC
        val nowMs = 1_779_451_200_000L
        val dayMs = 86_400_000L

        fun eventAt(
            id: String,
            bookId: String,
            endedAtMs: Long,
            wallSeconds: Long = 30L,
        ): ListeningEventSyncPayload =
            ListeningEventSyncPayload(
                id = id,
                bookId = bookId,
                startPositionMs = 0L,
                endPositionMs = wallSeconds * 1_000L,
                startedAt = endedAtMs - wallSeconds * 1_000L,
                endedAt = endedAtMs,
                playbackSpeed = 1.0f,
                tz = "UTC",
                deviceLabel = null,
                revision = 0L,
                updatedAt = 0L,
                createdAt = 0L,
                deletedAt = null,
            )

        test("stale stats row triggers recompute: windows are corrected in pullSince result") {
            withInMemoryDatabase {
                val db = this
                // thenMs: 2 hours before nowMs — the stats row was written then, making it stale.
                val thenMs = nowMs - 2 * 60 * 60 * 1_000L
                val thenClock = FixedClock(Instant.fromEpochMilliseconds(thenMs))
                val nowClock = FixedClock(Instant.fromEpochMilliseconds(nowMs))

                val bus = ChangeBus()
                // statsRepoThen writes rows with updatedAt = thenMs (stale)
                val statsRepoThen = UserStatsRepository(db = db, bus = bus, registry = SyncRegistry(), clock = thenClock)
                val eventRepo = ListeningEventRepository(db = db, bus = ChangeBus(), registry = SyncRegistry())

                runTest {
                    // Seed an event from 10 days ago for u1.
                    // After recompute against nowMs, the 7-day window should be 0 (event is outside 7d).
                    val oldEvent = eventAt("evt-old", "book-1", endedAtMs = nowMs - 10 * dayMs, wallSeconds = 3600L)
                    eventRepo.upsert(oldEvent, clientOpId = null, userId = "u1")

                    // Upsert a stats row whose windows say 3600s in 7d (stale — computed back when
                    // the event was within the 7-day window). updatedAt will be set to thenMs by the thenClock.
                    val staleStats =
                        com.calypsan.listenup.api.sync.UserStatsSyncPayload(
                            id = "u1",
                            totalSecondsAllTime = 3600L,
                            totalSecondsLast7Days = 3600L,
                            totalSecondsLast30Days = 3600L,
                            booksStarted = 1,
                            booksFinished = 0,
                            currentStreakDays = 1,
                            longestStreakDays = 1,
                            lastEventDate = null,
                            revision = 0L,
                            updatedAt = 0L, // overwritten by writePayload to thenMs
                            createdAt = 0L,
                            deletedAt = null,
                        )
                    statsRepoThen.upsert(staleStats, clientOpId = null, userId = "u1")

                    val revisionAfterSeed = statsRepoThen.getForUser("u1").shouldNotBeNull().revision

                    // Now create a repo with the current clock and a lazy-decay updater.
                    val updaterWithNowClock = UserStatsUpdater(db = db, userStatsRepo = statsRepoThen, clock = nowClock)
                    val statsRepoWithDecay =
                        UserStatsRepository(
                            db = db,
                            bus = bus,
                            registry = SyncRegistry(),
                            clock = nowClock,
                            userStatsUpdater = updaterWithNowClock,
                        )

                    val page = statsRepoWithDecay.pullSince(userId = "u1", cursor = 0L, limit = 50)

                    val item = page.items.firstOrNull().shouldNotBeNull()
                    // After recompute: event is 10 days old → outside 7-day window → totalSecondsLast7Days = 0
                    item.totalSecondsLast7Days shouldBe 0L
                    // Revision must have advanced (recompute fired the upsert)
                    item.revision shouldBeGreaterThan revisionAfterSeed
                }
            }
        }

        test("fresh stats row skips recompute: windows are returned unchanged") {
            withInMemoryDatabase {
                val db = this
                // thenMs: 30 minutes before nowMs — within the 1-hour freshness threshold.
                val thenMs = nowMs - 30 * 60 * 1_000L
                val thenClock = FixedClock(Instant.fromEpochMilliseconds(thenMs))
                val nowClock = FixedClock(Instant.fromEpochMilliseconds(nowMs))

                val bus = ChangeBus()
                val statsRepoThen = UserStatsRepository(db = db, bus = bus, registry = SyncRegistry(), clock = thenClock)
                val eventRepo = ListeningEventRepository(db = db, bus = ChangeBus(), registry = SyncRegistry())

                runTest {
                    val oldEvent = eventAt("evt-old", "book-1", endedAtMs = nowMs - 10 * dayMs, wallSeconds = 3600L)
                    eventRepo.upsert(oldEvent, clientOpId = null, userId = "u1")

                    // Upsert stats with stale window values, but updatedAt = thenMs (fresh — 30 min ago).
                    val freshStats =
                        com.calypsan.listenup.api.sync.UserStatsSyncPayload(
                            id = "u1",
                            totalSecondsAllTime = 3600L,
                            totalSecondsLast7Days = 3600L,
                            totalSecondsLast30Days = 3600L,
                            booksStarted = 1,
                            booksFinished = 0,
                            currentStreakDays = 1,
                            longestStreakDays = 1,
                            lastEventDate = null,
                            revision = 0L,
                            updatedAt = 0L,
                            createdAt = 0L,
                            deletedAt = null,
                        )
                    statsRepoThen.upsert(freshStats, clientOpId = null, userId = "u1")

                    val revisionAfterSeed = statsRepoThen.getForUser("u1").shouldNotBeNull().revision

                    val updaterWithNowClock = UserStatsUpdater(db = db, userStatsRepo = statsRepoThen, clock = nowClock)
                    val statsRepoWithDecay =
                        UserStatsRepository(
                            db = db,
                            bus = bus,
                            registry = SyncRegistry(),
                            clock = nowClock,
                            userStatsUpdater = updaterWithNowClock,
                        )

                    val page = statsRepoWithDecay.pullSince(userId = "u1", cursor = 0L, limit = 50)

                    val item = page.items.firstOrNull().shouldNotBeNull()
                    // No recompute — row is fresh — stale window value 3600 is returned as-is
                    item.totalSecondsLast7Days shouldBe 3600L
                    // Revision is unchanged
                    item.revision shouldBe revisionAfterSeed
                }
            }
        }
    })
