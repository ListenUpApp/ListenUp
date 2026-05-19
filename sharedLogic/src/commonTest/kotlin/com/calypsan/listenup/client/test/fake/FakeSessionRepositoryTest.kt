package com.calypsan.listenup.client.test.fake

import app.cash.turbine.test
import com.calypsan.listenup.client.domain.model.BookReadersResult
import com.calypsan.listenup.client.domain.model.ReaderInfo
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.calypsan.listenup.core.Success

class FakeSessionRepositoryTest {
    private fun reader(userId: String) =
        ReaderInfo(
            userId = userId,
            displayName = userId,
            avatarColor = "#000000",
            isCurrentlyReading = true,
            currentProgress = 0.5,
            startedAt = "2026-04-01T00:00:00Z",
            lastActivityAt = "2026-04-12T00:00:00Z",
            completionCount = 0,
        )

    @Test
    fun setReadersUpdatesObservers() =
        runTest {
            val repo = FakeSessionRepository()

            repo.observeBookReaders("book-1").test {
                val empty = awaitItem()
                assertEquals(0, empty.totalReaders)

                repo.setReaders(
                    "book-1",
                    BookReadersResult(
                        yourSessions = emptyList(),
                        otherReaders = listOf(reader("alice")),
                        totalReaders = 1,
                        totalCompletions = 0,
                    ),
                )

                val updated = awaitItem()
                assertEquals(1, updated.totalReaders)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun refreshBookReadersIncrementsRefreshCount() =
        runTest {
            val repo = FakeSessionRepository()

            repo.refreshBookReaders("book-1")
            repo.refreshBookReaders("book-1")

            assertEquals(2, repo.refreshCounts["book-1"])
        }
}
