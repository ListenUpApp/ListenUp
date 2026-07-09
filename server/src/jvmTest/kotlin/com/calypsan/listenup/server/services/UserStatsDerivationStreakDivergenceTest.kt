@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/**
 * Pins the bounded divergence between the two streak engines (Plan 117). The server's
 * [deriveUserStats] unions the days from its append-only `book_reads` completion log, so a re-read
 * contributes every historical finish day. The client's `StatsRepositoryImpl` sees only the
 * last-write-wins `playback_positions.finishedAt`/`last_played_at` — a single latest finish — so it
 * cannot reconstruct earlier re-read finish days. That divergence is accepted (Home derives locally,
 * leaderboards from this derivation); this test keeps it a known, named property rather than a bug
 * the next maintainer chases toward a false parity.
 */
class UserStatsDerivationStreakDivergenceTest :
    FunSpec({

        // 2026-05-22 12:00:00 UTC, and the day after.
        val day0Ms = 1_779_451_200_000L
        val dayMs = 86_400_000L
        val day1Ms = day0Ms + dayMs

        test(
            "streak counts every book_reads finish day — the client's position-only view cannot see " +
                "earlier re-read finishes (bounded divergence, see KDoc)",
        ) {
            withSqlDatabase {
                sql.seedTestUser("u1") // UTC home timezone
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("b1")

                // The position is last-write-wins: it holds ONLY the latest finish (day 1). This is
                // exactly the primitive the client can see locally.
                sql.transaction {
                    sql.playbackPositionsQueries.insert(
                        id = "pos-u1-b1",
                        user_id = "u1",
                        book_id = "b1",
                        position_ms = 3_600_000L,
                        last_played_at = day1Ms,
                        finished = 1L,
                        playback_speed = 1.0,
                        current_chapter_id = null,
                        revision = 0L,
                        created_at = 0L,
                        updated_at = 0L,
                        deleted_at = null,
                        client_op_id = null,
                    )
                    // The append-only completion log: the same book finished on TWO different days.
                    // The earlier finish (day 0) survives here but nowhere the client can reach.
                    sql.bookReadsQueries.insert(
                        id = "read-day0",
                        user_id = "u1",
                        book_id = "b1",
                        finished_at = day0Ms,
                        source = "test",
                        created_at = 0L,
                    )
                    sql.bookReadsQueries.insert(
                        id = "read-day1",
                        user_id = "u1",
                        book_id = "b1",
                        finished_at = day1Ms,
                        source = "test",
                        created_at = 0L,
                    )
                }

                runTest {
                    val stats = deriveUserStats(sql = sql, userId = "u1", nowMs = day1Ms)

                    // Both finish days (day 0 and day 1) are in the streak day-set, so a consecutive
                    // 2-day streak resolves as-of day 1. The only source of day 0 is `book_reads`:
                    // the position's last-played/finished timestamp is day 1 alone. A client deriving
                    // from positions would see just {day 1} → a 1-day streak. That is the divergence.
                    stats.currentStreakDays shouldBe 2
                    stats.longestStreakDays shouldBe 2
                }
            }
        }
    })
