@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.MutableClock
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * POSITION-01 — [PlaybackPositionRepository.recordPosition]'s clock-skew clamp. `lastPlayedAt`
 * arrives verbatim from the client's device clock; a device with a clock set into the future must
 * not be able to plant a value that permanently outranks every honest write that follows —
 * positions are digest-opt-out, so nothing else ever reconciles a poisoned row. Split out of
 * [PlaybackPositionRepositoryTest] (which is already at detekt's `LargeClass` ceiling) the same way
 * [PlaybackPositionImportBatchTest] is.
 */
class PlaybackPositionClockSkewTest :
    FunSpec({

        test("recordPosition clamps a future-skewed lastPlayedAt, and a later honest write heals the row") {
            withSqlDatabase {
                val now0 = 1_730_000_000_000L
                val clock = MutableClock(Instant.fromEpochMilliseconds(now0))
                val repo =
                    PlaybackPositionRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry(), clock = clock)
                runTest {
                    val threeDaysAhead = now0 + 3L * 24 * 60 * 60 * 1000
                    repo.recordPosition(
                        userId = "u1",
                        bookId = "book-1",
                        positionMs = 1_000L,
                        lastPlayedAt = threeDaysAhead,
                        finished = false,
                        playbackSpeed = 1.0f,
                        currentChapterId = null,
                    )

                    // Clamped to now0 + 5min, NOT the raw 3-days-ahead value the device reported.
                    val poisoned = repo.getPosition("u1", "book-1").shouldNotBeNull()
                    poisoned.lastPlayedAt shouldBe now0 + 5 * 60 * 1000L

                    // Distinct clock instant — the server clock advances past the clamp ceiling
                    // before the next (honest) write arrives, exactly as it would once real wall-clock
                    // time passes. Since the clamp already bounded the stored value to now0 + 5min,
                    // any later write with a genuinely honest (near-current) timestamp beats it via
                    // ordinary lastPlayedAt-wins — no waiting for the clock to catch up to the raw
                    // 3-day skew the device originally reported.
                    clock.instant = Instant.fromEpochMilliseconds(now0 + 6 * 60 * 1000L)
                    val honestLastPlayedAt = clock.instant.toEpochMilliseconds() + 1_000L
                    val result =
                        repo.recordPosition(
                            userId = "u1",
                            bookId = "book-1",
                            positionMs = 2_000L,
                            lastPlayedAt = honestLastPlayedAt,
                            finished = false,
                            playbackSpeed = 1.0f,
                            currentChapterId = null,
                        )
                    result.shouldBeInstanceOf<AppResult.Success<*>>()

                    val healed = repo.getPosition("u1", "book-1").shouldNotBeNull()
                    healed.lastPlayedAt shouldBe honestLastPlayedAt
                    healed.positionMs shouldBe 2_000L
                }
            }
        }

        test("recordPosition heals a pre-poisoned row inserted directly with a far-future lastPlayedAt") {
            withSqlDatabase {
                // Simulates a row poisoned before this clamp existed (or written by a path that
                // bypasses recordPosition entirely) — inserted straight through SQLDelight, never
                // touching the write-time clamp. Clamp #2 bounds this row's comparison "budget" to
                // now + SKEW_TOLERANCE just like a fresh write, but can never make it LOSE any sooner
                // than plain lastPlayedAt-wins already would (both sides collapse to the same ceiling
                // while still in the future, so nothing ever beats it until real time reaches the raw
                // value) — this pins that a pre-fix poisoned row is still eventually recoverable once
                // an honest write's own timestamp genuinely supersedes it, not stuck forever.
                val poisonedAt = 1_730_300_000_000L
                sql.playbackPositionsQueries.insert(
                    id = "pos-1",
                    user_id = "u1",
                    book_id = "book-1",
                    position_ms = 1_000L,
                    last_played_at = poisonedAt,
                    finished = 0L,
                    playback_speed = 1.0,
                    current_chapter_id = null,
                    revision = 1L,
                    created_at = poisonedAt,
                    updated_at = poisonedAt,
                    deleted_at = null,
                    client_op_id = null,
                )

                // The server clock has caught up to (just past) the raw poisoned timestamp — modeling
                // wall-clock time genuinely reaching it. An honest write dated after it must win.
                val clock = MutableClock(Instant.fromEpochMilliseconds(poisonedAt))
                val repo =
                    PlaybackPositionRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry(), clock = clock)
                runTest {
                    val result =
                        repo.recordPosition(
                            userId = "u1",
                            bookId = "book-1",
                            positionMs = 5_000L,
                            lastPlayedAt = poisonedAt + 1_000L,
                            finished = false,
                            playbackSpeed = 1.0f,
                            currentChapterId = null,
                        )
                    result.shouldBeInstanceOf<AppResult.Success<*>>()

                    val healed = repo.getPosition("u1", "book-1").shouldNotBeNull()
                    healed.lastPlayedAt shouldBe poisonedAt + 1_000L
                    healed.positionMs shouldBe 5_000L
                }
            }
        }

        test("recordPosition: an older honest lastPlayedAt still loses when both values are within tolerance") {
            withSqlDatabase {
                val now0 = 1_730_000_000_000L
                val clock = MutableClock(Instant.fromEpochMilliseconds(now0))
                val repo =
                    PlaybackPositionRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry(), clock = clock)
                runTest {
                    repo.recordPosition(
                        userId = "u1",
                        bookId = "book-1",
                        positionMs = 99_000L,
                        lastPlayedAt = now0 + 60_000L,
                        finished = false,
                        playbackSpeed = 1.0f,
                        currentChapterId = null,
                    )

                    // Older, but still well within SKEW_TOLERANCE on both sides — no clamping in
                    // play, this is the plain stale-write no-op regression pin.
                    val result =
                        repo.recordPosition(
                            userId = "u1",
                            bookId = "book-1",
                            positionMs = 10_000L,
                            lastPlayedAt = now0 + 30_000L,
                            finished = true,
                            playbackSpeed = 2.0f,
                            currentChapterId = "chap-stale",
                        )
                    result.shouldBeInstanceOf<AppResult.Success<*>>()

                    val stored = repo.getPosition("u1", "book-1").shouldNotBeNull()
                    stored.positionMs shouldBe 99_000L
                    stored.lastPlayedAt shouldBe now0 + 60_000L
                }
            }
        }

        test("recordPosition stores an old lastPlayedAt from a long-offline device unmodified") {
            withSqlDatabase {
                val now0 = 1_730_000_000_000L
                val clock = MutableClock(Instant.fromEpochMilliseconds(now0))
                val repo =
                    PlaybackPositionRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry(), clock = clock)
                runTest {
                    val sixDaysAgo = now0 - 6L * 24 * 60 * 60 * 1000
                    val result =
                        repo.recordPosition(
                            userId = "u1",
                            bookId = "book-1",
                            positionMs = 42_000L,
                            lastPlayedAt = sixDaysAgo,
                            finished = false,
                            playbackSpeed = 1.0f,
                            currentChapterId = null,
                        )
                    result.shouldBeInstanceOf<AppResult.Success<*>>()

                    // Old timestamps are never touched by the clamp — only future-dating is capped.
                    val stored = repo.getPosition("u1", "book-1").shouldNotBeNull()
                    stored.lastPlayedAt shouldBe sixDaysAgo
                }
            }
        }
    })
