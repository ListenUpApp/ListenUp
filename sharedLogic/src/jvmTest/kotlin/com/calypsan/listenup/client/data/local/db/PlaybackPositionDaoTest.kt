package com.calypsan.listenup.client.data.local.db

import app.cash.turbine.test
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers the reactive query surface of [PlaybackPositionDao] — specifically the
 * `observeRecentPositions(limit)` query added in W4.3 to back the Home screen's
 * Continue Listening shelf without pulling every position to the client (Finding 09).
 */
class PlaybackPositionDaoTest {
    private val db: ListenUpDatabase = createInMemoryTestDatabase()
    private val dao: PlaybackPositionDao = db.playbackPositionDao()

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun position(
        id: String,
        positionMs: Long,
        lastPlayedAt: Long?,
        updatedAt: Long,
    ): PlaybackPositionEntity =
        PlaybackPositionEntity(
            bookId = BookId(id),
            positionMs = positionMs,
            playbackSpeed = 1.0f,
            updatedAt = updatedAt,
            lastPlayedAt = lastPlayedAt,
        )

    @Test
    fun `observeRecentPositions returns started positions sorted by recency, limited`() =
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
                val emitted = awaitItem()
                assertEquals(
                    listOf("e", "a", "b"),
                    emitted.map { it.bookId.value },
                    "must return started positions in DESC lastPlayedAt/updatedAt order, limit applied",
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `observeRecentPositions emits new list when a position updates`() =
        runTest {
            dao.save(position(id = "a", positionMs = 100L, lastPlayedAt = 1_000L, updatedAt = 1_000L))

            dao.observeRecentPositions(limit = 5).test {
                assertEquals(listOf("a"), awaitItem().map { it.bookId.value })

                dao.save(position(id = "b", positionMs = 200L, lastPlayedAt = 2_000L, updatedAt = 2_000L))
                assertEquals(listOf("b", "a"), awaitItem().map { it.bookId.value })

                cancelAndIgnoreRemainingEvents()
            }
        }
}
