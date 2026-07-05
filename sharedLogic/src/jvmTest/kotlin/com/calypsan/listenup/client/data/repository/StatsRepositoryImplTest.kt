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
import com.calypsan.listenup.client.data.local.db.PlaybackPositionEntity
import com.calypsan.listenup.client.domain.WeeklyStats
import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.PendingRegistration
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
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

        fun makePosition(
            bookId: String,
            lastPlayedAt: Long,
            finishedAt: Long? = null,
        ): PlaybackPositionEntity =
            PlaybackPositionEntity(
                bookId = BookId(bookId),
                positionMs = 0L,
                playbackSpeed = 1.0f,
                updatedAt = lastPlayedAt,
                lastPlayedAt = lastPlayedAt,
                isFinished = finishedAt != null,
                finishedAt = finishedAt,
            )

        fun buildRepo(
            db: com.calypsan.listenup.client.data.local.db.ListenUpDatabase,
            authFlow: MutableStateFlow<AuthState>,
        ) = StatsRepositoryImpl(
            listeningEventDao = db.listeningEventDao(),
            genreDao = db.genreDao(),
            playbackPositionDao = db.playbackPositionDao(),
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

        test("current streak is consecutive-day run ending today; longest spans all history") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    // today, today-1, today-2: 3-day run ending today → current 3, longest 3
                    val msPerDay = 86_400_000L
                    val dayOffset = { days: Int -> nowMs - days * msPerDay - 3_600_000L }
                    db.listeningEventDao().upsert(makeEvent("d0", startedAt = dayOffset(0), endedAt = dayOffset(0) + 1_800_000L))
                    db.listeningEventDao().upsert(makeEvent("d1", startedAt = dayOffset(1), endedAt = dayOffset(1) + 1_800_000L))
                    db.listeningEventDao().upsert(makeEvent("d2", startedAt = dayOffset(2), endedAt = dayOffset(2) + 1_800_000L))

                    val authFlow = MutableStateFlow<AuthState>(AuthState.Authenticated(UserId("u1"), SessionId("s1")))
                    val repo = buildRepo(db, authFlow)

                    val stats = repo.observeWeeklyStats().first()
                    stats.currentStreakDays shouldBe 3
                    stats.longestStreakDays shouldBe 3
                }
            } finally {
                db.close()
            }
        }

        test("current streak is 0 when the last listen is old, but longest is preserved") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    // 3 consecutive days ~90 days ago, nothing recent → current 0, longest 3
                    val msPerDay = 86_400_000L
                    val oldAnchor = nowMs - 90 * msPerDay
                    db.listeningEventDao().upsert(makeEvent("o0", startedAt = oldAnchor, endedAt = oldAnchor + 1_800_000L))
                    db.listeningEventDao().upsert(
                        makeEvent("o1", startedAt = oldAnchor + msPerDay, endedAt = oldAnchor + msPerDay + 1_800_000L),
                    )
                    db.listeningEventDao().upsert(
                        makeEvent("o2", startedAt = oldAnchor + 2 * msPerDay, endedAt = oldAnchor + 2 * msPerDay + 1_800_000L),
                    )

                    val authFlow = MutableStateFlow<AuthState>(AuthState.Authenticated(UserId("u1"), SessionId("s1")))
                    val repo = buildRepo(db, authFlow)

                    val stats = repo.observeWeeklyStats().first()
                    stats.currentStreakDays shouldBe 0
                    stats.longestStreakDays shouldBe 3
                }
            } finally {
                db.close()
            }
        }

        test("longest streak is the max run across history regardless of the week window") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    // 5-day run last month + 2-day run ending today → longest 5, current 2
                    val msPerDay = 86_400_000L
                    val oldAnchor = nowMs - 30 * msPerDay
                    for (i in 0..4) {
                        db.listeningEventDao().upsert(
                            makeEvent("long-$i", startedAt = oldAnchor + i * msPerDay, endedAt = oldAnchor + i * msPerDay + 1_800_000L),
                        )
                    }
                    // 2-day run today and yesterday
                    db.listeningEventDao().upsert(makeEvent("r0", startedAt = nowMs - 3_600_000L, endedAt = nowMs - 1_800_000L))
                    db.listeningEventDao().upsert(
                        makeEvent("r1", startedAt = nowMs - msPerDay - 3_600_000L, endedAt = nowMs - msPerDay - 1_800_000L),
                    )

                    val authFlow = MutableStateFlow<AuthState>(AuthState.Authenticated(UserId("u1"), SessionId("s1")))
                    val repo = buildRepo(db, authFlow)

                    val stats = repo.observeWeeklyStats().first()
                    stats.currentStreakDays shouldBe 2
                    stats.longestStreakDays shouldBe 5
                }
            } finally {
                db.close()
            }
        }

        test("same-day multiple sessions count as one streak day") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    // two events same calendar day + the prior day → current 2 (not 3)
                    val msPerDay = 86_400_000L
                    // today: two events 1 hour apart
                    db.listeningEventDao().upsert(makeEvent("t0", startedAt = nowMs - 3_600_000L, endedAt = nowMs - 2_700_000L))
                    db.listeningEventDao().upsert(makeEvent("t1", startedAt = nowMs - 2_400_000L, endedAt = nowMs - 1_800_000L))
                    // yesterday: one event
                    db.listeningEventDao().upsert(
                        makeEvent("y0", startedAt = nowMs - msPerDay - 3_600_000L, endedAt = nowMs - msPerDay - 1_800_000L),
                    )

                    val authFlow = MutableStateFlow<AuthState>(AuthState.Authenticated(UserId("u1"), SessionId("s1")))
                    val repo = buildRepo(db, authFlow)

                    val stats = repo.observeWeeklyStats().first()
                    stats.currentStreakDays shouldBe 2
                }
            } finally {
                db.close()
            }
        }

        test("days with only playback progress (no listening events) count toward the streak") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    // ABS import lands as playback_positions with the original last-played time — no
                    // listening_events. Yesterday (progress) → today (finished) must count → current 2.
                    val msPerDay = 86_400_000L
                    db.playbackPositionDao().save(makePosition("book-a", lastPlayedAt = nowMs - msPerDay))
                    db.playbackPositionDao().save(makePosition("book-b", lastPlayedAt = nowMs, finishedAt = nowMs))

                    val authFlow = MutableStateFlow<AuthState>(AuthState.Authenticated(UserId("u1"), SessionId("s1")))
                    val repo = buildRepo(db, authFlow)

                    val stats = repo.observeWeeklyStats().first()
                    stats.currentStreakDays shouldBe 2
                    stats.longestStreakDays shouldBe 2
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
        libraryId = LibraryId("test-library"),
        folderId = FolderId("test-folder"),
        title = "Book $id",
        sortTitle = null,
        subtitle = null,
        coverHash = null,
        coverBlurHash = null,
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
