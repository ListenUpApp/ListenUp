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
 * - [PlaybackPositionDao.observeRecentPositions] — Continue Listening shelf (W4.3)
 */
class PlaybackPositionDaoTest :
    FunSpec({
        val db: ListenUpDatabase = createInMemoryTestDatabase()
        val dao: PlaybackPositionDao = db.playbackPositionDao()

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
    })
