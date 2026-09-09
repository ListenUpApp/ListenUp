package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

class PlaybackPositionBoostRoundTripTest :
    FunSpec({

        test("recordPosition persists volumeBoostDb and measuredGainDb and reads them back") {
            withSqlDatabase {
                val repo = PlaybackPositionRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    val result =
                        repo.recordPosition(
                            userId = "u1",
                            bookId = "book-1",
                            positionMs = 42_000L,
                            lastPlayedAt = 1_730_000_000_000L,
                            finished = false,
                            playbackSpeed = 1.0f,
                            currentChapterId = null,
                            volumeBoostDb = 6f,
                            measuredGainDb = -2f,
                        )
                    result.shouldBeInstanceOf<AppResult.Success<*>>()

                    val stored = repo.getPosition("u1", "book-1").shouldNotBeNull()
                    stored.volumeBoostDb shouldBe 6f
                    stored.measuredGainDb shouldBe -2f
                }
            }
        }

        test("null measuredGainDb on an update preserves the stored measurement") {
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
                        volumeBoostDb = 6f,
                        measuredGainDb = -2.5f,
                    )

                    // A device with offline-queued ops replays a payload frozen with
                    // measuredGainDb = null — it must not erase another device's measurement.
                    val result =
                        repo.recordPosition(
                            userId = "u1",
                            bookId = "book-1",
                            positionMs = 99_000L,
                            lastPlayedAt = 1_730_000_999_000L,
                            finished = false,
                            playbackSpeed = 1.0f,
                            currentChapterId = null,
                            volumeBoostDb = 3f,
                            measuredGainDb = null,
                        )
                    result.shouldBeInstanceOf<AppResult.Success<*>>()

                    val stored = repo.getPosition("u1", "book-1").shouldNotBeNull()
                    stored.positionMs shouldBe 99_000L
                    stored.volumeBoostDb shouldBe 3f
                    stored.measuredGainDb shouldBe -2.5f
                }
            }
        }

        test("update path overwrites volumeBoostDb with the new value, not a placeholder") {
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
                        volumeBoostDb = 6f,
                        measuredGainDb = -2f,
                    )

                    val result =
                        repo.recordPosition(
                            userId = "u1",
                            bookId = "book-1",
                            positionMs = 99_000L,
                            lastPlayedAt = 1_730_000_999_000L,
                            finished = false,
                            playbackSpeed = 1.0f,
                            currentChapterId = null,
                            volumeBoostDb = 3f,
                            measuredGainDb = -2f,
                        )
                    result.shouldBeInstanceOf<AppResult.Success<*>>()

                    val stored = repo.getPosition("u1", "book-1").shouldNotBeNull()
                    stored.volumeBoostDb shouldBe 3f
                    stored.measuredGainDb shouldBe -2f
                }
            }
        }

        test("recordPosition persists finishedAt, hasCustomSpeed and hasCustomBoost and reads them back") {
            withSqlDatabase {
                val repo = PlaybackPositionRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    val result =
                        repo.recordPosition(
                            userId = "u1",
                            bookId = "book-1",
                            positionMs = 42_000L,
                            lastPlayedAt = 1_730_000_000_000L,
                            finished = true,
                            playbackSpeed = 1.5f,
                            currentChapterId = null,
                            volumeBoostDb = 6f,
                            measuredGainDb = -2f,
                            finishedAt = 1_730_000_000_000L,
                            hasCustomSpeed = true,
                            hasCustomBoost = true,
                        )
                    result.shouldBeInstanceOf<AppResult.Success<*>>()

                    val stored = repo.getPosition("u1", "book-1").shouldNotBeNull()
                    stored.finishedAt shouldBe 1_730_000_000_000L
                    stored.hasCustomSpeed shouldBe true
                    stored.hasCustomBoost shouldBe true
                }
            }
        }

        test("a null finishedAt on an update preserves the stored finish date") {
            withSqlDatabase {
                val repo = PlaybackPositionRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    repo.recordPosition(
                        userId = "u1",
                        bookId = "book-1",
                        positionMs = 42_000L,
                        lastPlayedAt = 1_730_000_000_000L,
                        finished = true,
                        playbackSpeed = 1.0f,
                        currentChapterId = null,
                        finishedAt = 1_730_000_000_000L,
                    )

                    // An offline-queued payload frozen before the book was finished replays with a
                    // null finish date — it must not erase the one another device recorded.
                    val result =
                        repo.recordPosition(
                            userId = "u1",
                            bookId = "book-1",
                            positionMs = 99_000L,
                            lastPlayedAt = 1_730_000_999_000L,
                            finished = true,
                            playbackSpeed = 1.0f,
                            currentChapterId = null,
                            finishedAt = null,
                        )
                    result.shouldBeInstanceOf<AppResult.Success<*>>()

                    val stored = repo.getPosition("u1", "book-1").shouldNotBeNull()
                    stored.positionMs shouldBe 99_000L
                    stored.finishedAt shouldBe 1_730_000_000_000L
                }
            }
        }

        test("hasCustomSpeed is overwritten by the incoming value, not preserved") {
            withSqlDatabase {
                val repo = PlaybackPositionRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    repo.recordPosition(
                        userId = "u1",
                        bookId = "book-1",
                        positionMs = 42_000L,
                        lastPlayedAt = 1_730_000_000_000L,
                        finished = false,
                        playbackSpeed = 1.5f,
                        currentChapterId = null,
                        hasCustomSpeed = true,
                        hasCustomBoost = true,
                    )

                    // "Reset to default" un-sets the flag — these booleans must NOT COALESCE, or a
                    // reset would be impossible.
                    val result =
                        repo.recordPosition(
                            userId = "u1",
                            bookId = "book-1",
                            positionMs = 99_000L,
                            lastPlayedAt = 1_730_000_999_000L,
                            finished = false,
                            playbackSpeed = 1.0f,
                            currentChapterId = null,
                            hasCustomSpeed = false,
                            hasCustomBoost = false,
                        )
                    result.shouldBeInstanceOf<AppResult.Success<*>>()

                    val stored = repo.getPosition("u1", "book-1").shouldNotBeNull()
                    stored.hasCustomSpeed shouldBe false
                    stored.hasCustomBoost shouldBe false
                }
            }
        }
    })
