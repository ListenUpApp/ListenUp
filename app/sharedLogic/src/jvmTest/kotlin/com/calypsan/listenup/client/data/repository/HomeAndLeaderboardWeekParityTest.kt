@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.data.local.db.ListeningEventEntity
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.client.test.fake.FakeAuthSession
import com.calypsan.listenup.server.db.DatabaseConfig
import com.calypsan.listenup.server.db.DatabaseFactory
import com.calypsan.listenup.server.services.deriveUserStats
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase as ServerSqlDatabase

/**
 * Home's "This week" and the Leaderboard's Week figure are the same number: for the same listening
 * events, home timezone and moment, the client's Room-derived [StatsRepositoryImpl] total equals the
 * server's [deriveUserStats] `totalSecondsLast7Days` (which the leaderboard projection copies).
 *
 * Drives the real client repository over in-memory Room and the real server derivation over a migrated
 * server SQLite, so a change to either side's window or counting rule breaks this test.
 */
class HomeAndLeaderboardWeekParityTest :
    FunSpec({

        data class Span(
            val startedAt: String,
            val endedAt: String,
        )

        data class Scenario(
            val name: String,
            val timeZone: String,
            val now: String,
            val spans: List<Span>,
        )

        val scenarios =
            listOf(
                // Simon's account on 2026-10-02: Home showed 6h 8m, the Leaderboard 6h 35m.
                Scenario(
                    name = "Edmonton, the reported week",
                    timeZone = "America/Edmonton",
                    now = "2026-10-02T16:20:00Z",
                    spans =
                        listOf(
                            Span("2026-09-25T19:33:00Z", "2026-09-25T20:00:00Z"), // Sep 25 MDT: outside the week
                            Span("2026-09-26T05:40:00Z", "2026-09-26T06:20:00Z"), // straddles local midnight Sep 26
                            Span("2026-10-02T09:52:00Z", "2026-10-02T15:20:00Z"),
                        ),
                ),
                Scenario(
                    name = "UTC listener",
                    timeZone = "UTC",
                    now = "2026-10-02T16:20:00Z",
                    spans =
                        listOf(
                            Span("2026-09-25T23:00:00Z", "2026-09-25T23:30:00Z"),
                            Span("2026-09-26T00:10:00Z", "2026-09-26T00:30:00Z"),
                            Span("2026-10-01T12:00:00Z", "2026-10-01T13:00:00Z"),
                        ),
                ),
                Scenario(
                    name = "Edmonton across spring-forward",
                    timeZone = "America/Edmonton",
                    now = "2026-03-10T18:00:00Z",
                    spans =
                        listOf(
                            Span("2026-03-04T06:00:00Z", "2026-03-04T06:30:00Z"), // Mar 3 23:30 MST: outside
                            Span("2026-03-04T07:15:00Z", "2026-03-04T07:30:00Z"), // Mar 4 00:30 MST: inside
                            Span("2026-03-08T08:30:00Z", "2026-03-08T09:30:00Z"), // spans the 02:00 jump
                        ),
                ),
                Scenario(
                    name = "Edmonton across fall-back",
                    timeZone = "America/Edmonton",
                    now = "2026-11-03T18:00:00Z",
                    spans =
                        listOf(
                            Span("2026-10-28T05:00:00Z", "2026-10-28T05:45:00Z"), // Oct 27 23:45 MDT: outside
                            Span("2026-10-28T06:05:00Z", "2026-10-28T06:25:00Z"), // Oct 28 00:25 MDT: inside
                            Span("2026-11-01T07:30:00Z", "2026-11-01T08:30:00Z"), // spans the repeated hour
                        ),
                ),
            )

        for (scenario in scenarios) {
            test("Home and the server count the same week: ${scenario.name}") {
                val now = Instant.parse(scenario.now)
                val tz = TimeZone.of(scenario.timeZone)

                val serverFile = Files.createTempFile("listenup-week-parity-", ".db").toFile().apply { deleteOnExit() }
                val serverHandle = DatabaseFactory.init(DatabaseConfig(jdbcUrl = "jdbc:sqlite:${serverFile.absolutePath}"))
                val server = ServerSqlDatabase(serverHandle.sqlDriver)
                val client = createInMemoryTestDatabase()
                try {
                    server.seedUser("u1", scenario.timeZone)

                    runTest {
                        scenario.spans.forEachIndexed { i, span ->
                            val startedAt = Instant.parse(span.startedAt).toEpochMilliseconds()
                            val endedAt = Instant.parse(span.endedAt).toEpochMilliseconds()
                            server.insertSpan("e$i", startedAt, endedAt)
                            client.listeningEventDao().upsert(clientSpan("e$i", startedAt, endedAt))
                        }

                        val home =
                            StatsRepositoryImpl(
                                listeningEventDao = client.listeningEventDao(),
                                genreDao = client.genreDao(),
                                playbackPositionDao = client.playbackPositionDao(),
                                authSession = FakeAuthSession(userId = "u1"),
                                clock = fixedAt(now),
                                timeZone = { tz },
                                ticker = flowOf(Unit),
                            ).observeWeeklyStats().first()

                        val leaderboard = deriveUserStats(server, "u1", now.toEpochMilliseconds())

                        leaderboard.totalSecondsLast7Days shouldBe home.totalSecondsThisWeek
                    }
                } finally {
                    client.close()
                    serverHandle.close()
                }
            }
        }
    })

private fun fixedAt(instant: Instant): Clock =
    object : Clock {
        override fun now(): Instant = instant
    }

private fun clientSpan(
    id: String,
    startedAt: Long,
    endedAt: Long,
): ListeningEventEntity =
    ListeningEventEntity(
        id = id,
        userId = "u1",
        bookId = "b1",
        startPositionMs = 0L,
        endPositionMs = endedAt - startedAt,
        startedAt = startedAt,
        endedAt = endedAt,
        playbackSpeed = 1.0f,
        tz = "UTC",
        deviceLabel = null,
    )

private fun ServerSqlDatabase.seedUser(
    id: String,
    timeZone: String,
) {
    transaction {
        usersQueries.insert(
            id = id,
            email = "$id@example.com",
            email_normalized = "$id@example.com",
            password_hash = "phc",
            role = "MEMBER",
            display_name = id,
            status = "ACTIVE",
            created_at = 1L,
            updated_at = 1L,
            last_login_at = null,
            can_edit = 1L,
            approved_by = null,
            approved_at = null,
            deleted_at = null,
            invited_by = null,
            tagline = null,
            avatar_type = "auto",
            timezone = timeZone,
        )
    }
}

private fun ServerSqlDatabase.insertSpan(
    id: String,
    startedAt: Long,
    endedAt: Long,
) {
    transaction {
        listeningEventsQueries.insert(
            id = id,
            user_id = "u1",
            book_id = "b1",
            start_position_ms = 0L,
            end_position_ms = endedAt - startedAt,
            started_at = startedAt,
            ended_at = endedAt,
            playback_speed = 1.0,
            tz = "UTC",
            device_label = null,
            revision = 0L,
            created_at = 0L,
            updated_at = 0L,
            deleted_at = null,
            client_op_id = null,
        )
    }
}
