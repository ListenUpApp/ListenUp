@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.api.dto.auth.RefreshToken
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.client.data.local.db.BookEntity
import com.calypsan.listenup.client.data.local.db.BookGenreCrossRef
import com.calypsan.listenup.client.data.local.db.GenreEntity
import com.calypsan.listenup.client.data.local.db.ListeningEventEntity
import com.calypsan.listenup.client.data.local.db.UserStatsEntity
import com.calypsan.listenup.client.domain.WeeklyStats
import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.PendingRegistration
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.Timestamp
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.TimeZone

/**
 * Integration tests for [StatsRepositoryImpl].
 *
 * Uses an in-memory [ListenUpDatabase] for DAO operations.
 * [AuthSession] is a simple in-test fake backed by a MutableStateFlow.
 * [Clock] is injected deterministically.
 *
 * Uses `flow.first()` as the terminal operator so [runTest] advances the
 * [StandardTestDispatcher] until the Room IO results resume the flow.
 */
class StatsRepositoryImplTest :
    FunSpec({

        /** Fixed "now": 2026-05-23 12:00:00 UTC (Saturday). */
        val nowInstant = Instant.parse("2026-05-23T12:00:00Z")
        val nowMs = nowInstant.toEpochMilliseconds()
        val utc = TimeZone.UTC

        // ---- helpers ----

        fun makeEvent(
            id: String,
            userId: String = "u1",
            bookId: String = "book-$id",
            startedAt: Long,
            endedAt: Long,
        ): ListeningEventEntity =
            ListeningEventEntity(
                id = id,
                userId = userId,
                bookId = bookId,
                startPositionMs = 0L,
                endPositionMs = (endedAt - startedAt),
                startedAt = startedAt,
                endedAt = endedAt,
                playbackSpeed = 1.0f,
                tz = "UTC",
                deviceLabel = null,
            )

        /** Returns a deterministic clock that always returns [nowInstant]. */
        fun fixedClock(): Clock =
            object : Clock {
                override fun now(): Instant = nowInstant
            }

        fun buildRepo(
            db: com.calypsan.listenup.client.data.local.db.ListenUpDatabase,
            authFlow: MutableStateFlow<AuthState>,
        ) = StatsRepositoryImpl(
            listeningEventDao = db.listeningEventDao(),
            userStatsDao = db.userStatsDao(),
            genreDao = db.genreDao(),
            authSession = FakeAuthSession(authFlow),
            clock = fixedClock(),
            timeZone = { utc },
            // Replace the infinite midnightPulse with a single-shot emitter so
            // runTest never sees uncompleted delay coroutines.
            ticker = flowOf(Unit),
        )

        // ---- tests ----

        test("no user signed in emits WeeklyStats.empty") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val authFlow = MutableStateFlow<AuthState>(AuthState.NeedsLogin())
                    val repo = buildRepo(db, authFlow)

                    val stats = repo.observeWeeklyStats().first()
                    stats shouldBe WeeklyStats.empty()
                }
            } finally {
                db.close()
            }
        }

        test("signed in with no events emits WeeklyStats.empty with isEverEmpty true") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val authFlow =
                        MutableStateFlow<AuthState>(
                            AuthState.Authenticated(UserId("u1"), SessionId("s1")),
                        )
                    val repo = buildRepo(db, authFlow)

                    val stats = repo.observeWeeklyStats().first()
                    stats shouldBe WeeklyStats.empty()
                    stats.isEverEmpty shouldBe true
                }
            } finally {
                db.close()
            }
        }

        test("one 30-min event today lands in bucket 0") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    // event: started 1h before now, ended 30min before now → 1800s wall time
                    val startedAt = nowMs - 3600_000L
                    val endedAt = nowMs - 1800_000L

                    db.listeningEventDao().upsert(
                        makeEvent("e1", startedAt = startedAt, endedAt = endedAt),
                    )

                    val authFlow =
                        MutableStateFlow<AuthState>(
                            AuthState.Authenticated(UserId("u1"), SessionId("s1")),
                        )
                    val repo = buildRepo(db, authFlow)

                    val stats = repo.observeWeeklyStats().first()
                    stats.dailyBuckets[0].totalSeconds shouldBe 1800L
                    stats.totalSecondsThisWeek shouldBe 1800L
                    stats.dailyBuckets.drop(1).all { it.totalSeconds == 0L } shouldBe true
                }
            } finally {
                db.close()
            }
        }

        test("events spread across multiple days land in correct buckets") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val msPerDay = 86_400_000L
                    // today: 1800s
                    val e0Start = nowMs - 3600_000L
                    val e0End = nowMs - 1800_000L
                    // 3 days ago: 3600s
                    val e3Start = nowMs - 3 * msPerDay - 3600_000L
                    val e3End = nowMs - 3 * msPerDay

                    db.listeningEventDao().upsert(makeEvent("e0", startedAt = e0Start, endedAt = e0End))
                    db.listeningEventDao().upsert(makeEvent("e3", startedAt = e3Start, endedAt = e3End))

                    val authFlow =
                        MutableStateFlow<AuthState>(
                            AuthState.Authenticated(UserId("u1"), SessionId("s1")),
                        )
                    val repo = buildRepo(db, authFlow)

                    val stats = repo.observeWeeklyStats().first()
                    stats.dailyBuckets[0].totalSeconds shouldBe 1800L
                    stats.dailyBuckets[3].totalSeconds shouldBe 3600L
                    stats.totalSecondsThisWeek shouldBe 5400L
                }
            } finally {
                db.close()
            }
        }

        test("event 10 days ago is excluded from totalSecondsThisWeek") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val msPerDay = 86_400_000L
                    val oldStart = nowMs - 10 * msPerDay - 3600_000L
                    val oldEnd = nowMs - 10 * msPerDay

                    db.listeningEventDao().upsert(makeEvent("old", startedAt = oldStart, endedAt = oldEnd))

                    val authFlow =
                        MutableStateFlow<AuthState>(
                            AuthState.Authenticated(UserId("u1"), SessionId("s1")),
                        )
                    val repo = buildRepo(db, authFlow)

                    val stats = repo.observeWeeklyStats().first()
                    stats.totalSecondsThisWeek shouldBe 0L
                }
            } finally {
                db.close()
            }
        }

        test("streak fields come from UserStatsEntity, not recomputed locally") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    db.userStatsDao().upsert(
                        UserStatsEntity(
                            id = "u1",
                            totalSecondsAllTime = 0L,
                            totalSecondsLast7Days = 0L,
                            totalSecondsLast30Days = 0L,
                            booksStarted = 0,
                            booksFinished = 0,
                            currentStreakDays = 5,
                            longestStreakDays = 14,
                            lastEventDate = null,
                        ),
                    )

                    val authFlow =
                        MutableStateFlow<AuthState>(
                            AuthState.Authenticated(UserId("u1"), SessionId("s1")),
                        )
                    val repo = buildRepo(db, authFlow)

                    val stats = repo.observeWeeklyStats().first()
                    stats.currentStreakDays shouldBe 5
                    stats.longestStreakDays shouldBe 14
                }
            } finally {
                db.close()
            }
        }

        test("genre breakdown orders topGenres by totalSeconds descending") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    // Seed books + genres
                    db.bookDao().upsert(seedBook("book-scifi"))
                    db.bookDao().upsert(seedBook("book-both"))
                    db.bookDao().upsert(seedBook("book-history"))

                    db.genreDao().upsert(GenreEntity(id = "g-scifi", name = "scifi", slug = "scifi", path = "/scifi"))
                    db.genreDao().upsert(GenreEntity(id = "g-fantasy", name = "fantasy", slug = "fantasy", path = "/fantasy"))
                    db.genreDao().upsert(GenreEntity(id = "g-history", name = "history", slug = "history", path = "/history"))

                    db.genreDao().insertAllBookGenres(listOf(BookGenreCrossRef(BookId("book-scifi"), "g-scifi")))
                    db.genreDao().insertAllBookGenres(
                        listOf(
                            BookGenreCrossRef(BookId("book-both"), "g-scifi"),
                            BookGenreCrossRef(BookId("book-both"), "g-fantasy"),
                        ),
                    )
                    db.genreDao().insertAllBookGenres(listOf(BookGenreCrossRef(BookId("book-history"), "g-history")))

                    // Anchor 4h before now so all event endedAt values fall inside the 7-day window
                    val anchorMs = nowMs - 14_400_000L
                    // book-scifi: 2h = 7200s  → scifi gets 7200
                    db.listeningEventDao().upsert(
                        makeEvent("e1", bookId = "book-scifi", startedAt = anchorMs, endedAt = anchorMs + 7200_000L),
                    )
                    // book-both: 1h = 3600s  → scifi += 3600, fantasy += 3600
                    db.listeningEventDao().upsert(
                        makeEvent("e2", bookId = "book-both", startedAt = anchorMs + 7200_000L, endedAt = anchorMs + 10800_000L),
                    )
                    // book-history: 30min = 1800s  → history = 1800
                    db.listeningEventDao().upsert(
                        makeEvent("e3", bookId = "book-history", startedAt = anchorMs + 10800_000L, endedAt = anchorMs + 12600_000L),
                    )

                    val authFlow =
                        MutableStateFlow<AuthState>(
                            AuthState.Authenticated(UserId("u1"), SessionId("s1")),
                        )
                    val repo = buildRepo(db, authFlow)

                    val stats = repo.observeWeeklyStats().first()
                    // scifi: 7200 (book-scifi) + 3600 (book-both) = 10800
                    // fantasy: 3600 (book-both)
                    // history: 1800
                    stats.topGenres[0].genreName shouldBe "scifi"
                    stats.topGenres[0].totalSeconds shouldBe 10800L
                    stats.topGenres[1].genreName shouldBe "fantasy"
                    stats.topGenres[2].genreName shouldBe "history"
                }
            } finally {
                db.close()
            }
        }

        test("book with no genres contributes to totalSeconds but not topGenres") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    db.bookDao().upsert(seedBook("book-nogenre"))

                    // Event ends 30 minutes before now, inside the 7-day window
                    db.listeningEventDao().upsert(
                        makeEvent("e1", bookId = "book-nogenre", startedAt = nowMs - 3600_000L, endedAt = nowMs - 1800_000L),
                    )

                    val authFlow =
                        MutableStateFlow<AuthState>(
                            AuthState.Authenticated(UserId("u1"), SessionId("s1")),
                        )
                    val repo = buildRepo(db, authFlow)

                    val stats = repo.observeWeeklyStats().first()
                    stats.totalSecondsThisWeek shouldBe 1800L
                    stats.topGenres shouldBe emptyList()
                }
            } finally {
                db.close()
            }
        }
    })

// ---- helpers ----

private fun seedBook(id: String): BookEntity =
    BookEntity(
        id = BookId(id),
        title = "Book $id",
        sortTitle = null,
        subtitle = null,
        coverHash = null,
        coverBlurHash = null,
        dominantColor = null,
        darkMutedColor = null,
        vibrantColor = null,
        totalDuration = 0L,
        description = null,
        publishYear = null,
        publisher = null,
        language = null,
        isbn = null,
        asin = null,
        abridged = false,
        createdAt = Timestamp(1L),
        updatedAt = Timestamp(1L),
    )

/**
 * Minimal [AuthSession] fake backed by a [MutableStateFlow].
 *
 * Only [authState] is consumed by [StatsRepositoryImpl]; all other methods are no-ops.
 */
private class FakeAuthSession(
    override val authState: StateFlow<AuthState>,
) : AuthSession {
    override suspend fun saveAuthTokens(
        access: AccessToken,
        refresh: RefreshToken,
        sessionId: String,
        userId: String,
    ) = Unit

    override suspend fun getAccessToken(): AccessToken? = null

    override suspend fun getRefreshToken(): RefreshToken? = null

    override suspend fun getSessionId(): String? = null

    override suspend fun getUserId(): String? = null

    override suspend fun updateAccessToken(token: AccessToken) = Unit

    override suspend fun clearAuthTokens() = Unit

    override suspend fun isAuthenticated(): Boolean = false

    override suspend fun initializeAuthState() = Unit

    override suspend fun checkServerStatus(): AuthState = authState.value

    override suspend fun refreshOpenRegistration() = Unit

    override suspend fun savePendingRegistration(
        userId: String,
        email: String,
    ) = Unit

    override suspend fun getPendingRegistration(): PendingRegistration? = null

    override suspend fun clearPendingRegistration() = Unit
}
