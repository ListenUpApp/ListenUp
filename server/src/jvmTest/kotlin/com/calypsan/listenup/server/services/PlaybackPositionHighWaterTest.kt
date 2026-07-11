@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/**
 * High-water listening frontier (`maxPositionMs`) — the order-independent max-merge that keeps the
 * furthest-heard position monotonic regardless of `lastPlayedAt` write ordering (Integration
 * Foundations §4). Split from [PlaybackPositionRepositoryTest] to keep each spec focused.
 */
class PlaybackPositionHighWaterTest :
    FunSpec({

        test("recordPosition on a fresh row persists the given maxPositionMs") {
            withSqlDatabase {
                val repo = PlaybackPositionRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    repo.recordPosition(
                        userId = "u1",
                        bookId = "book-1",
                        positionMs = 10_000L,
                        lastPlayedAt = 1_730_000_000_000L,
                        finished = false,
                        playbackSpeed = 1.0f,
                        currentChapterId = null,
                        maxPositionMs = 42_000L,
                    )

                    val stored = repo.getPosition("u1", "book-1").shouldNotBeNull()
                    stored.maxPositionMs shouldBe 42_000L
                }
            }
        }

        test(
            "recordPosition with an OLDER lastPlayedAt but a HIGHER maxPositionMs bumps the max " +
                "without touching position/speed (order-independent max-merge)",
        ) {
            withSqlDatabase {
                val repo = PlaybackPositionRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    repo.recordPosition(
                        userId = "u1",
                        bookId = "book-1",
                        positionMs = 50_000L,
                        lastPlayedAt = 1_730_000_999_000L,
                        finished = false,
                        playbackSpeed = 1.0f,
                        currentChapterId = null,
                        maxPositionMs = 50_000L,
                    )
                    val before = repo.getPosition("u1", "book-1").shouldNotBeNull()

                    // Stale write (older lastPlayedAt) — would normally be a total no-op — but it
                    // carries a higher maxPositionMs, which must still advance.
                    val result =
                        repo.recordPosition(
                            userId = "u1",
                            bookId = "book-1",
                            positionMs = 5_000L,
                            lastPlayedAt = 1_720_000_000_000L, // older
                            finished = true,
                            playbackSpeed = 2.0f,
                            currentChapterId = "chap-stale",
                            maxPositionMs = 200_000L, // higher
                        )
                    result.shouldBeInstanceOf<AppResult.Success<*>>()

                    val after = repo.getPosition("u1", "book-1").shouldNotBeNull()
                    // Every other column untouched — the stale write's content never applied.
                    after.positionMs shouldBe 50_000L
                    after.playbackSpeed shouldBe 1.0f
                    after.currentChapterId shouldBe null
                    after.finished shouldBe false
                    after.lastPlayedAt shouldBe before.lastPlayedAt
                    // Only the max column advanced.
                    after.maxPositionMs shouldBe 200_000L
                    after.revision shouldBeGreaterThan before.revision
                }
            }
        }

        test("recordPosition with an OLDER lastPlayedAt and a LOWER-or-EQUAL maxPositionMs is a total no-op") {
            withSqlDatabase {
                val repo = PlaybackPositionRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    repo.recordPosition(
                        userId = "u1",
                        bookId = "book-1",
                        positionMs = 50_000L,
                        lastPlayedAt = 1_730_000_999_000L,
                        finished = false,
                        playbackSpeed = 1.0f,
                        currentChapterId = null,
                        maxPositionMs = 50_000L,
                    )
                    val before = repo.getPosition("u1", "book-1").shouldNotBeNull()

                    repo.recordPosition(
                        userId = "u1",
                        bookId = "book-1",
                        positionMs = 5_000L,
                        lastPlayedAt = 1_720_000_000_000L, // older
                        finished = true,
                        playbackSpeed = 2.0f,
                        currentChapterId = "chap-stale",
                        maxPositionMs = 10_000L, // lower than stored max
                    )

                    val after = repo.getPosition("u1", "book-1").shouldNotBeNull()
                    after shouldBe before
                }
            }
        }

        test("recordPosition with a NEWER lastPlayedAt but a LOWER maxPositionMs (rewind) updates position, keeps the max") {
            withSqlDatabase {
                val repo = PlaybackPositionRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    repo.recordPosition(
                        userId = "u1",
                        bookId = "book-1",
                        positionMs = 80_000L,
                        lastPlayedAt = 1_730_000_000_000L,
                        finished = false,
                        playbackSpeed = 1.0f,
                        currentChapterId = null,
                        maxPositionMs = 80_000L,
                    )

                    // Newer write (a rewind — e.g. user restarted from earlier) with a lower max.
                    repo.recordPosition(
                        userId = "u1",
                        bookId = "book-1",
                        positionMs = 5_000L,
                        lastPlayedAt = 1_730_000_999_000L, // newer
                        finished = false,
                        playbackSpeed = 1.0f,
                        currentChapterId = null,
                        maxPositionMs = 5_000L, // lower
                    )

                    val stored = repo.getPosition("u1", "book-1").shouldNotBeNull()
                    stored.positionMs shouldBe 5_000L
                    stored.maxPositionMs shouldBe 80_000L
                }
            }
        }

        test("recordAllForImport applies the same order-independent max-merge for a stale-but-higher row") {
            withSqlDatabase {
                val repo = PlaybackPositionRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    repo.recordPosition(
                        userId = "u1",
                        bookId = "book-1",
                        positionMs = 50_000L,
                        lastPlayedAt = 1_730_000_999_000L,
                        finished = false,
                        playbackSpeed = 1.0f,
                        currentChapterId = null,
                        maxPositionMs = 50_000L,
                    )
                    val before = repo.getPosition("u1", "book-1").shouldNotBeNull()

                    repo.recordAllForImport(
                        listOf(
                            ImportPositionWrite(
                                userId = "u1",
                                bookId = "book-1",
                                positionMs = 200_000L, // higher than stored max — backfill = positionMs
                                lastPlayedAt = 1_720_000_000_000L, // older — would normally be a no-op
                                finished = false,
                                playbackSpeed = 1.0f,
                                currentChapterId = null,
                            ),
                        ),
                    )

                    val after = repo.getPosition("u1", "book-1").shouldNotBeNull()
                    after.positionMs shouldBe before.positionMs // untouched — the stale write never applied
                    after.maxPositionMs shouldBe 200_000L // max still advanced
                }
            }
        }

        test("recordAllForImport on a fresh row derives maxPositionMs from positionMs (backfill = positionMs)") {
            withSqlDatabase {
                val repo = PlaybackPositionRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    repo.recordAllForImport(
                        listOf(
                            ImportPositionWrite(
                                userId = "u1",
                                bookId = "book-1",
                                positionMs = 75_000L,
                                lastPlayedAt = 1_730_000_000_000L,
                                finished = false,
                                playbackSpeed = 1.0f,
                                currentChapterId = null,
                            ),
                        ),
                    )

                    val stored = repo.getPosition("u1", "book-1").shouldNotBeNull()
                    stored.maxPositionMs shouldBe 75_000L
                }
            }
        }
    })
