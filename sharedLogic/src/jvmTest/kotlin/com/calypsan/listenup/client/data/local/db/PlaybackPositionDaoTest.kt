package com.calypsan.listenup.client.data.local.db

import app.cash.turbine.test
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/**
 * Covers the reactive query surface of [PlaybackPositionDao]:
 * - [PlaybackPositionDao.observeRecentPositions] — Continue Listening shelf
 */
class PlaybackPositionDaoTest :
    FunSpec({
        val db: ListenUpDatabase = createInMemoryTestDatabase()
        val dao: PlaybackPositionDao = db.playbackPositionDao()

        beforeEach { dao.deleteAll() }
        afterSpec { db.close() }

        fun position(
            id: String,
            positionMs: Long,
            lastPlayedAt: Long?,
            updatedAt: Long,
            isFinished: Boolean = false,
            deletedAt: Long? = null,
        ): PlaybackPositionEntity =
            PlaybackPositionEntity(
                bookId = BookId(id),
                positionMs = positionMs,
                playbackSpeed = 1.0f,
                updatedAt = updatedAt,
                lastPlayedAt = lastPlayedAt,
                isFinished = isFinished,
                deletedAt = deletedAt,
            )

        // ──────────────────────────────────────────────────────────────────────
        // observeRecentPositions — migrated from kotlin-test
        // ──────────────────────────────────────────────────────────────────────

        test("observeRecentPositions returns started positions sorted by recency, limited") {
            runTest {
                dao.saveAll(
                    listOf(
                        position(id = "a", positionMs = 100L, lastPlayedAt = 3_000L, updatedAt = 3_000L),
                        position(id = "b", positionMs = 50L, lastPlayedAt = 2_000L, updatedAt = 2_000L),
                        position(id = "c", positionMs = 75L, lastPlayedAt = 1_000L, updatedAt = 1_000L),
                        // Unstarted: must be excluded
                        position(id = "d", positionMs = 0L, lastPlayedAt = 9_000L, updatedAt = 9_000L),
                        // Legacy null lastPlayedAt — must fall back to updatedAt
                        position(id = "e", positionMs = 25L, lastPlayedAt = null, updatedAt = 4_000L),
                    ),
                )

                dao.observeRecentPositions(limit = 3).test {
                    awaitItem().map { it.bookId.value } shouldContainExactly listOf("e", "a", "b")
                    cancelAndIgnoreRemainingEvents()
                }

                dao.deleteAll()
            }
        }

        test("observeRecentPositions emits new list when a position updates") {
            runTest {
                dao.save(position(id = "a", positionMs = 100L, lastPlayedAt = 1_000L, updatedAt = 1_000L))

                dao.observeRecentPositions(limit = 5).test {
                    awaitItem().map { it.bookId.value } shouldContainExactly listOf("a")

                    dao.save(position(id = "b", positionMs = 200L, lastPlayedAt = 2_000L, updatedAt = 2_000L))
                    awaitItem().map { it.bookId.value } shouldContainExactly listOf("b", "a")

                    cancelAndIgnoreRemainingEvents()
                }

                dao.deleteAll()
            }
        }

        // ──────────────────────────────────────────────────────────────────────
        // Fix B: SQL-side isFinished = 0 filter (Continue Listening reliability)
        // ──────────────────────────────────────────────────────────────────────

        test("observeRecentPositions excludes isFinished=true positions") {
            runTest {
                dao.saveAll(
                    listOf(
                        // p1: started, not finished → INCLUDED
                        position(id = "p1", positionMs = 1_000L, lastPlayedAt = 2_000L, updatedAt = 2_000L, isFinished = false),
                        // p2: started, finished → EXCLUDED by SQL
                        position(id = "p2", positionMs = 1_000L, lastPlayedAt = 1_000L, updatedAt = 1_000L, isFinished = true),
                    ),
                )

                dao.observeRecentPositions(limit = 10).test {
                    awaitItem().map { it.bookId.value } shouldContainExactly listOf("p1")
                    cancelAndIgnoreRemainingEvents()
                }

                dao.deleteAll()
            }
        }

        test("observeRecentPositions still excludes positionMs=0 (unstarted) positions") {
            runTest {
                dao.saveAll(
                    listOf(
                        // unstarted → EXCLUDED by positionMs > 0 filter
                        position(id = "unstarted", positionMs = 0L, lastPlayedAt = 9_000L, updatedAt = 9_000L),
                        // started, not finished → INCLUDED
                        position(id = "started", positionMs = 500L, lastPlayedAt = 1_000L, updatedAt = 1_000L),
                    ),
                )

                dao.observeRecentPositions(limit = 10).test {
                    awaitItem().map { it.bookId.value } shouldContainExactly listOf("started")
                    cancelAndIgnoreRemainingEvents()
                }

                dao.deleteAll()
            }
        }

        test("observeRecentPositions ordering: COALESCE(lastPlayedAt, updatedAt) DESC") {
            runTest {
                dao.saveAll(
                    listOf(
                        // null lastPlayedAt falls back to updatedAt=3000 → most recent
                        position(id = "legacy", positionMs = 100L, lastPlayedAt = null, updatedAt = 3_000L),
                        // explicit lastPlayedAt=2000 → second
                        position(id = "mid", positionMs = 100L, lastPlayedAt = 2_000L, updatedAt = 500L),
                        // lastPlayedAt=1000 → third
                        position(id = "old", positionMs = 100L, lastPlayedAt = 1_000L, updatedAt = 100L),
                    ),
                )

                dao.observeRecentPositions(limit = 10).test {
                    awaitItem().map { it.bookId.value } shouldContainExactly listOf("legacy", "mid", "old")
                    cancelAndIgnoreRemainingEvents()
                }

                dao.deleteAll()
            }
        }

        test("observeRecentPositions respects LIMIT") {
            runTest {
                val positions =
                    (1..15).map { i ->
                        position(id = "p$i", positionMs = 100L, lastPlayedAt = i.toLong() * 1_000L, updatedAt = i.toLong() * 1_000L)
                    }
                dao.saveAll(positions)

                dao.observeRecentPositions(limit = 5).test {
                    awaitItem().size shouldBe 5
                    cancelAndIgnoreRemainingEvents()
                }

                dao.deleteAll()
            }
        }
    })
