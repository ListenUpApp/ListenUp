@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.api.dto.auth.RefreshToken
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.client.data.local.db.ListeningEventEntity
import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.PendingRegistration
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/**
 * Integration tests for [BookListeningHistoryRepositoryImpl].
 *
 * Uses an in-memory [ListenUpDatabase] for DAO operations.
 * [AuthSession] is the same [FakeAuthSession] pattern used in [StatsRepositoryImplTest].
 * [Clock] and [viewerTimeZone] are injected deterministically.
 *
 * "viewer TZ" = the TZ the device is in when the label is rendered.
 * "event TZ" = the TZ recorded at the time of the event (drives day bucketing).
 */
class BookListeningHistoryRepositoryImplTest :
    FunSpec({

        /**
         * Fixed "now": 2026-05-23 09:00:00 UTC.
         * In UTC that is Saturday 2026-05-23.
         * In "America/New_York" (UTC-4 in summer) that is 2026-05-23 05:00 → also 2026-05-23.
         * In "Europe/London" (UTC+1 in summer BST) that is 2026-05-23 10:00 → also 2026-05-23.
         */
        val nowInstant = Instant.parse("2026-05-23T09:00:00Z")
        val nowMs = nowInstant.toEpochMilliseconds()
        val utc = TimeZone.UTC

        fun fixedClock(): Clock =
            object : Clock {
                override fun now(): Instant = nowInstant
            }

        fun buildRepo(
            db: com.calypsan.listenup.client.data.local.db.ListenUpDatabase,
            authFlow: MutableStateFlow<AuthState>,
            viewerTz: TimeZone = utc,
        ) = BookListeningHistoryRepositoryImpl(
            listeningEventDao = db.listeningEventDao(),
            authSession = FakeHistoryAuthSession(authFlow),
            clock = fixedClock(),
            viewerTimeZone = { viewerTz },
        )

        fun makeEvent(
            id: String,
            userId: String = "u1",
            bookId: String = "bookA",
            startedAt: Long,
            endedAt: Long,
            tz: String = "UTC",
            deviceLabel: String? = null,
            deletedAt: Long? = null,
        ) = ListeningEventEntity(
            id = id,
            userId = userId,
            bookId = bookId,
            startPositionMs = 0L,
            endPositionMs = endedAt - startedAt,
            startedAt = startedAt,
            endedAt = endedAt,
            playbackSpeed = 1.0f,
            tz = tz,
            deviceLabel = deviceLabel,
            revision = 0,
            deletedAt = deletedAt,
        )

        // ---- Case 1: empty book ----

        test("empty book emits BookListeningHistory with no daily buckets") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val authFlow = MutableStateFlow<AuthState>(
                        AuthState.Authenticated(UserId("u1"), SessionId("s1")),
                    )
                    val repo = buildRepo(db, authFlow)

                    val history = repo.observeFor("bookA").first()
                    history.daily.shouldBeEmpty()
                }
            } finally {
                db.close()
            }
        }

        // ---- Case 2: single event today ----

        test("single event today produces one bucket with relativeLabel Today") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    // endedAt is 1 hour before now → still today in UTC
                    val endedAt = nowMs - 3_600_000L
                    db.listeningEventDao().upsert(makeEvent("e1", startedAt = endedAt - 1_800_000L, endedAt = endedAt))

                    val authFlow = MutableStateFlow<AuthState>(
                        AuthState.Authenticated(UserId("u1"), SessionId("s1")),
                    )
                    val repo = buildRepo(db, authFlow)

                    val history = repo.observeFor("bookA").first()
                    history.daily.size shouldBe 1
                    history.daily[0].relativeLabel shouldBe "Today"
                    history.daily[0].events.size shouldBe 1
                    history.daily[0].events[0].id shouldBe "e1"
                }
            } finally {
                db.close()
            }
        }

        // ---- Case 3: multiple events same day ----

        test("two events on the same day produce one bucket with events newest-first and summed totalSeconds") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    // Both end today; e2 ends later than e1
                    val e1EndedAt = nowMs - 7_200_000L   // 2h ago
                    val e2EndedAt = nowMs - 3_600_000L   // 1h ago
                    db.listeningEventDao().upsert(makeEvent("e1", startedAt = e1EndedAt - 600_000L, endedAt = e1EndedAt))
                    db.listeningEventDao().upsert(makeEvent("e2", startedAt = e2EndedAt - 1_200_000L, endedAt = e2EndedAt))

                    val authFlow = MutableStateFlow<AuthState>(
                        AuthState.Authenticated(UserId("u1"), SessionId("s1")),
                    )
                    val repo = buildRepo(db, authFlow)

                    val history = repo.observeFor("bookA").first()
                    history.daily.size shouldBe 1
                    val bucket = history.daily[0]
                    bucket.events.size shouldBe 2
                    // newest-first: e2 then e1
                    bucket.events[0].id shouldBe "e2"
                    bucket.events[1].id shouldBe "e1"
                    // totalSeconds = wall-clock durations: (e1EndedAt - (e1EndedAt - 600_000)) / 1000
                    //              + (e2EndedAt - (e2EndedAt - 1_200_000)) / 1000
                    //              = 600 + 1200 = 1800
                    bucket.totalSeconds shouldBe 1800L
                }
            } finally {
                db.close()
            }
        }

        // ---- Case 4: day grouping across 4 distinct days ----

        test("events across 4 distinct days produce 4 buckets sorted newest-first") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val dao = db.listeningEventDao()
                    val msPerDay = 86_400_000L
                    // today, yesterday, 2 days ago, 3 days ago
                    for (daysAgo in 0..3) {
                        val endedAt = nowMs - daysAgo * msPerDay
                        dao.upsert(makeEvent("e-$daysAgo", startedAt = endedAt - 60_000L, endedAt = endedAt))
                    }

                    val authFlow = MutableStateFlow<AuthState>(
                        AuthState.Authenticated(UserId("u1"), SessionId("s1")),
                    )
                    val repo = buildRepo(db, authFlow)

                    val history = repo.observeFor("bookA").first()
                    history.daily.size shouldBe 4
                    // Verify descending order
                    val dates = history.daily.map { it.date }
                    dates shouldBe dates.sortedDescending()
                }
            } finally {
                db.close()
            }
        }

        // ---- Case 5: TZ awareness — event TZ drives bucket date ----

        test("event TZ drives day bucket date, not viewer TZ") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val dao = db.listeningEventDao()
                    // nowInstant = 2026-05-23T09:00:00Z
                    // In "Europe/London" (BST = UTC+1): 2026-05-23 10:00 → date 2026-05-23
                    // In "America/New_York" (EDT = UTC-4): 2026-05-23 05:00 → date 2026-05-23
                    // Use a timestamp that is firmly 2026-05-22 in New York but 2026-05-23 in London:
                    // 2026-05-23T01:00:00Z → London: 02:00 → 2026-05-23; New York: 21:00 prev day → 2026-05-22
                    val eventEndedAt = Instant.parse("2026-05-23T01:00:00Z").toEpochMilliseconds()
                    dao.upsert(
                        makeEvent(
                            "e1",
                            startedAt = eventEndedAt - 60_000L,
                            endedAt = eventEndedAt,
                            tz = "Europe/London",
                        ),
                    )

                    val authFlow = MutableStateFlow<AuthState>(
                        AuthState.Authenticated(UserId("u1"), SessionId("s1")),
                    )
                    // Viewer is in New York but the event was recorded in London
                    val repo = buildRepo(db, authFlow, viewerTz = TimeZone.of("America/New_York"))

                    val history = repo.observeFor("bookA").first()
                    history.daily.size shouldBe 1
                    // Bucket date must use event's London TZ → 2026-05-23
                    history.daily[0].date shouldBe LocalDate(2026, 5, 23)
                }
            } finally {
                db.close()
            }
        }

        // ---- Case 6: relative labels use viewer TZ ----

        test("Today and Yesterday labels use the viewer's current local date") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val dao = db.listeningEventDao()
                    val msPerDay = 86_400_000L
                    // nowInstant = 2026-05-23T09:00:00Z, viewer TZ = UTC
                    // today event
                    val todayEndedAt = nowMs - 3_600_000L
                    dao.upsert(makeEvent("today", startedAt = todayEndedAt - 60_000L, endedAt = todayEndedAt, tz = "UTC"))
                    // yesterday event
                    val yestEndedAt = nowMs - msPerDay
                    dao.upsert(makeEvent("yesterday", startedAt = yestEndedAt - 60_000L, endedAt = yestEndedAt, tz = "UTC"))
                    // 10 days ago
                    val oldEndedAt = nowMs - 10 * msPerDay
                    dao.upsert(makeEvent("old", startedAt = oldEndedAt - 60_000L, endedAt = oldEndedAt, tz = "UTC"))

                    val authFlow = MutableStateFlow<AuthState>(
                        AuthState.Authenticated(UserId("u1"), SessionId("s1")),
                    )
                    val repo = buildRepo(db, authFlow, viewerTz = utc)

                    val history = repo.observeFor("bookA").first()
                    history.daily.size shouldBe 3
                    // Newest first: today, yesterday, old
                    history.daily[0].relativeLabel shouldBe "Today"
                    history.daily[1].relativeLabel shouldBe "Yesterday"
                    // 10 days ago = 2026-05-13 — same year as now → "May 13"
                    history.daily[2].relativeLabel shouldBe "May 13"
                }
            } finally {
                db.close()
            }
        }

        // ---- Case 7: defensive against unparseable TZ ----

        test("event with unparseable tz falls back to viewer TZ without crashing") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val dao = db.listeningEventDao()
                    val endedAt = nowMs - 3_600_000L
                    dao.upsert(
                        makeEvent("e1", startedAt = endedAt - 60_000L, endedAt = endedAt, tz = "Not/A/Real/TZ"),
                    )

                    val authFlow = MutableStateFlow<AuthState>(
                        AuthState.Authenticated(UserId("u1"), SessionId("s1")),
                    )
                    val repo = buildRepo(db, authFlow, viewerTz = utc)

                    val history = repo.observeFor("bookA").first()
                    // Should not throw; event falls into today's bucket in UTC
                    history.daily.size shouldBe 1
                    history.daily[0].relativeLabel shouldBe "Today"
                }
            } finally {
                db.close()
            }
        }

        // ---- Case 8: filters by user ----

        test("events from a different user for the same book are excluded") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val dao = db.listeningEventDao()
                    val endedAt = nowMs - 3_600_000L
                    // u1 event for bookA
                    dao.upsert(makeEvent("e1", userId = "u1", startedAt = endedAt - 60_000L, endedAt = endedAt))
                    // u2 event for bookA — must be excluded
                    dao.upsert(makeEvent("e2", userId = "u2", startedAt = endedAt - 120_000L, endedAt = endedAt - 60_000L))

                    val authFlow = MutableStateFlow<AuthState>(
                        AuthState.Authenticated(UserId("u1"), SessionId("s1")),
                    )
                    val repo = buildRepo(db, authFlow)

                    val history = repo.observeFor("bookA").first()
                    history.daily.size shouldBe 1
                    history.daily[0].events.size shouldBe 1
                    history.daily[0].events[0].id shouldBe "e1"
                }
            } finally {
                db.close()
            }
        }

        // ---- Case 9: no user signed in ----

        test("no user signed in emits empty history") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val dao = db.listeningEventDao()
                    val endedAt = nowMs - 3_600_000L
                    dao.upsert(makeEvent("e1", startedAt = endedAt - 60_000L, endedAt = endedAt))

                    val authFlow = MutableStateFlow<AuthState>(AuthState.NeedsLogin())
                    val repo = buildRepo(db, authFlow)

                    val history = repo.observeFor("bookA").first()
                    history.daily.shouldBeEmpty()
                }
            } finally {
                db.close()
            }
        }
    })

/**
 * Minimal [AuthSession] fake backed by a [MutableStateFlow].
 * Only [authState] is consumed by [BookListeningHistoryRepositoryImpl]; all other methods are no-ops.
 */
private class FakeHistoryAuthSession(
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
