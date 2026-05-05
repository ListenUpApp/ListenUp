package com.calypsan.listenup.client.data.repository

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.turbineScope
import com.calypsan.listenup.client.core.BookId
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.core.Success
import com.calypsan.listenup.client.core.Timestamp
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.client.data.local.db.BookEntity
import com.calypsan.listenup.client.data.local.db.BookReadersSummaryDao
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.local.db.SyncState
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.remote.BookReadersResponse
import com.calypsan.listenup.client.data.remote.ReaderSummary
import com.calypsan.listenup.client.data.remote.SessionApiContract
import com.calypsan.listenup.client.data.remote.SessionSummary as RemoteSessionSummary
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import dev.mokkery.answering.returns
import dev.mokkery.answering.throws
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verify.VerifyMode
import dev.mokkery.verifySuspend
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Awaits emissions from this turbine until [predicate] returns true, or fails after [maxAttempts].
 * Protects against indefinite hangs when Room invalidation timing diverges from expectations.
 */
private suspend fun <T> ReceiveTurbine<T>.awaitUntil(
    maxAttempts: Int = 10,
    predicate: (T) -> Boolean,
): T {
    repeat(maxAttempts) {
        val item = awaitItem()
        if (predicate(item)) return item
    }
    error("Predicate never satisfied within $maxAttempts emissions")
}

/**
 * Seam-level tests for [SessionRepositoryImpl]. Uses real in-memory [ListenUpDatabase]
 * with real DAOs; mocks [SessionApiContract] and [AuthSession] so the repository's
 * transaction and flow-composition behaviour is exercised end-to-end.
 *
 * Covers Bug 1's fix (authoritative totalReaders from the summary DAO) + the
 * refactored `observeBookReaders` shape (combine + onStart, no channelFlow).
 */
class SessionRepositoryImplTest {
    private val db: ListenUpDatabase = createInMemoryTestDatabase()
    private val transactionRunner = RoomTransactionRunner(db)
    private val sessionApi: SessionApiContract = mock()
    private val authSession: AuthSession = mock()

    private val repo =
        SessionRepositoryImpl(
            sessionApi = sessionApi,
            userReadingSessionDao = db.userReadingSessionDao(),
            readerSessionCacheDao = db.readerSessionCacheDao(),
            bookReadersSummaryDao = db.bookReadersSummaryDao(),
            transactionRunner = transactionRunner,
            authSession = authSession,
        )

    private val userId = "user-1"
    private val bookId = "book-1"

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private suspend fun seedBook() {
        db.bookDao().upsertAll(
            listOf(
                BookEntity(
                    id = BookId(bookId),
                    title = "Test Book",
                    subtitle = null,
                    coverUrl = null,
                    totalDuration = 3_600_000L,
                    description = null,
                    publishYear = null,
                    dominantColor = null,
                    darkMutedColor = null,
                    vibrantColor = null,
                    createdAt = Timestamp(1_700_000_000_000L),
                    updatedAt = Timestamp(1_700_000_000_000L),
                    syncState = SyncState.SYNCED,
                    lastModified = Timestamp(1_700_000_000_000L),
                    serverVersion = Timestamp(1_700_000_000_000L),
                ),
            ),
        )
    }

    private fun mockApiResponse(
        totalReaders: Int,
        totalCompletions: Int,
        yourSessions: List<RemoteSessionSummary> = emptyList(),
        otherReaders: List<ReaderSummary> = emptyList(),
    ) {
        everySuspend { sessionApi.getBookReaders(bookId) } returns
            Success(
                BookReadersResponse(
                    yourSessions = yourSessions,
                    otherReaders = otherReaders,
                    totalReaders = totalReaders,
                    totalCompletions = totalCompletions,
                ),
            )
    }

    @Test
    fun `observeBookReaders emits empty-cache Result on first subscription before refresh lands`() =
        runTest {
            seedBook()
            everySuspend { authSession.getUserId() } returns userId
            mockApiResponse(totalReaders = 0, totalCompletions = 0)

            turbineScope {
                val turbine = repo.observeBookReaders(bookId).testIn(backgroundScope)
                val first = turbine.awaitItem()
                assertEquals(0, first.totalReaders)
                assertEquals(0, first.totalCompletions)
                assertTrue(first.yourSessions.isEmpty())
                assertTrue(first.otherReaders.isEmpty())
                turbine.cancel()
            }
        }

    @Test
    fun `onStart triggers refresh which populates all three tables`() =
        runTest {
            seedBook()
            everySuspend { authSession.getUserId() } returns userId
            mockApiResponse(totalReaders = 42, totalCompletions = 7)

            turbineScope {
                val turbine = repo.observeBookReaders(bookId).testIn(backgroundScope)
                val result = turbine.awaitUntil { it.totalReaders > 0 }
                assertEquals(42, result.totalReaders)
                assertEquals(7, result.totalCompletions)
                turbine.cancel()
            }
        }

    @Test
    fun `refresh network failure logs and continues emitting from cache`() =
        runTest {
            seedBook()
            everySuspend { authSession.getUserId() } returns userId
            everySuspend { sessionApi.getBookReaders(bookId) } throws RuntimeException("network boom")

            turbineScope {
                val turbine = repo.observeBookReaders(bookId).testIn(backgroundScope)
                val first = turbine.awaitItem()
                assertEquals(0, first.totalReaders)
                turbine.cancel()
            }
        }

    @Test
    fun `refresh returning AppResult Failure logs and continues emitting from cache`() =
        runTest {
            seedBook()
            everySuspend { authSession.getUserId() } returns userId
            // API returns a typed Failure instead of throwing — exercises the `is Failure ->` branch
            // at lines 163-165 of SessionRepositoryImpl (logs, returns without writing to DB).
            everySuspend { sessionApi.getBookReaders(bookId) } returns
                Failure(TransportError.NetworkUnavailable(debugInfo = "simulated failure"))

            turbineScope {
                val turbine = repo.observeBookReaders(bookId).testIn(backgroundScope)
                val first = turbine.awaitItem()
                assertEquals(0, first.totalReaders)
                // No exception surfaces; no summary row created
                turbine.cancel()
            }

            // Assert no summary row was written
            val summary =
                db
                    .bookReadersSummaryDao()
                    .observeFor(BookId(bookId))
                    .first()
            assertNull(summary, "Failure branch must not write a summary row")
        }

    @Test
    fun `totalReaders reflects API response value not derived cache count`() =
        runTest {
            // Canonical Bug 1 regression test.
            seedBook()
            everySuspend { authSession.getUserId() } returns userId
            // API says totalReaders=42 but only 1 other reader in the response payload.
            // Result must show 42, not 2 (derived = 1 cache + 1 user).
            val otherReader =
                ReaderSummary(
                    userId = "other-1",
                    displayName = "Other 1",
                    avatarType = "initials",
                    avatarValue = "O1",
                    avatarColor = "#fff",
                    isCurrentlyReading = true,
                    currentProgress = 0.5,
                    startedAt = "2026-04-01T00:00:00Z",
                    finishedAt = null,
                    lastActivityAt = "2026-04-20T00:00:00Z",
                    completionCount = 0,
                )
            mockApiResponse(totalReaders = 42, totalCompletions = 15, otherReaders = listOf(otherReader))

            turbineScope {
                val turbine = repo.observeBookReaders(bookId).testIn(backgroundScope)
                val result = turbine.awaitUntil { it.totalReaders > 0 }
                assertEquals(42, result.totalReaders, "must use API's authoritative count, not derived from cache")
                assertEquals(15, result.totalCompletions)
                turbine.cancel()
            }
        }

    @Test
    fun `self-emission path reads from user_reading_sessions not from cache`() =
        runTest {
            // The API returns one yourSessions entry. After onStart refresh, the
            // user_reading_sessions table is populated from the API response and
            // combine emits it via the userFlow path (not via readerSessionCacheDao).
            seedBook()
            everySuspend { authSession.getUserId() } returns userId

            val apiSession =
                RemoteSessionSummary(
                    id = "user-session-marker",
                    startedAt = "2026-04-01T00:00:00Z",
                    finishedAt = null,
                    isCompleted = false,
                    listenTimeMs = 300_000L,
                )
            mockApiResponse(
                totalReaders = 1,
                totalCompletions = 0,
                yourSessions = listOf(apiSession),
            )

            turbineScope {
                val turbine = repo.observeBookReaders(bookId).testIn(backgroundScope)
                val result = turbine.awaitUntil { it.yourSessions.isNotEmpty() }
                assertEquals(1, result.yourSessions.size)
                assertEquals("user-session-marker", result.yourSessions.first().id)
                turbine.cancel()
            }
        }

    @Test
    fun `refresh DB transaction rollback on late-step throw`() =
        runTest {
            seedBook()
            everySuspend { authSession.getUserId() } returns userId
            mockApiResponse(totalReaders = 5, totalCompletions = 2)

            val failingSummaryDao: BookReadersSummaryDao = mock()
            everySuspend { failingSummaryDao.upsert(any()) } throws RuntimeException("simulated mid-transaction failure")

            val repoWithFailingDao =
                SessionRepositoryImpl(
                    sessionApi = sessionApi,
                    userReadingSessionDao = db.userReadingSessionDao(),
                    readerSessionCacheDao = db.readerSessionCacheDao(),
                    bookReadersSummaryDao = failingSummaryDao,
                    transactionRunner = transactionRunner,
                    authSession = authSession,
                )

            assertFailsWith<RuntimeException> {
                repoWithFailingDao.refreshBookReaders(bookId)
            }

            assertTrue(db.userReadingSessionDao().getForBook(bookId, userId).isEmpty(), "user-session rows must roll back")
            assertTrue(
                db.readerSessionCacheDao().getForBook(bookId, excludingUserId = userId).isEmpty(),
                "reader-cache rows must roll back",
            )
        }

    @Test
    fun `10 concurrent refreshBookReaders calls complete without deadlock and final state matches last response`() =
        runTest {
            seedBook()
            everySuspend { authSession.getUserId() } returns userId
            mockApiResponse(totalReaders = 99, totalCompletions = 42)

            (1..10)
                .map {
                    async { repo.refreshBookReaders(bookId) }
                }.awaitAll()

            verifySuspend(VerifyMode.exactly(10)) {
                sessionApi.getBookReaders(bookId)
            }

            val summaryFlow = db.bookReadersSummaryDao().observeFor(BookId(bookId))
            turbineScope {
                val t = summaryFlow.testIn(backgroundScope)
                val summary = t.awaitUntil { it != null }!!
                assertEquals(99, summary.totalReaders)
                t.cancel()
            }
        }

    @Test
    fun `refresh writes all three tables in one transaction`() =
        runTest {
            seedBook()
            everySuspend { authSession.getUserId() } returns userId
            mockApiResponse(totalReaders = 3, totalCompletions = 1)

            var atomicallyCallCount = 0
            val countingRunner =
                object : TransactionRunner {
                    override suspend fun <R> atomically(block: suspend () -> R): R {
                        atomicallyCallCount++
                        return transactionRunner.atomically(block)
                    }
                }

            val repoWithCounter =
                SessionRepositoryImpl(
                    sessionApi = sessionApi,
                    userReadingSessionDao = db.userReadingSessionDao(),
                    readerSessionCacheDao = db.readerSessionCacheDao(),
                    bookReadersSummaryDao = db.bookReadersSummaryDao(),
                    transactionRunner = countingRunner,
                    authSession = authSession,
                )

            repoWithCounter.refreshBookReaders(bookId)

            assertEquals(1, atomicallyCallCount, "refreshBookReaders must use exactly one atomically block")
        }
}
