package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.sync.ListeningEventSyncPayload
import com.calypsan.listenup.client.data.repository.LeaderboardRepositoryImpl
import com.calypsan.listenup.client.data.sync.testing.withClientSyncEngineAgainstServer
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardPeriod
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

private const val ROUND_TRIP_TIMEOUT_SECONDS = 30

/**
 * Tier 3 e2e: one listening event on the server triggers [UserStatsUpdater] which calls
 * [com.calypsan.listenup.server.services.PublicProfileMaintainer.refresh], which publishes a
 * `public_profiles` SSE event. The client's [PublicProfileSyncDomainHandler] applies it into
 * Room, and [LeaderboardRepositoryImpl.observeSnapshot] surfaces it in the leaderboard.
 *
 * Tests the real chain end-to-end in a single process. The user must exist in the server
 * `users` table because [PublicProfileMaintainer.refresh] queries `users` to build the
 * payload (returns null / no-op for unknown users).
 */
class PublicProfileSyncE2ETest :
    FunSpec({

        val nowMs = 1_779_451_200_000L
        val spanMs = 30_000L

        test("listening event → public_profiles SSE → client Room row appears in leaderboard") {
            withClientSyncEngineAgainstServer {
                // Seed the user "u1" so PublicProfileMaintainer.refresh finds a UserTable row.
                // Raw SQL matches the pattern established in ProfileE2ETest — the UserEntity DAO
                // lives in :server's test source set and is not available from :sharedLogic:jvmTest.
                val seedNow = System.currentTimeMillis()
                serverDriver.execute(
                    null,
                    "INSERT INTO users(id, email, email_normalized, password_hash, role, " +
                        "display_name, status, created_at, updated_at) VALUES " +
                        "('u1', 'u1@example.com', 'u1@example.com', 'phc', " +
                        "'MEMBER', 'Test User', 'ACTIVE', $seedNow, $seedNow)",
                    0,
                )

                engine.start(currentUserId = "u1")

                // Trigger a listening event server-side: UserStatsUpdater fires and calls
                // PublicProfileMaintainer.refresh("u1"), which publishes a public_profiles SSE.
                serverListeningEventRepository.upsert(
                    ListeningEventSyncPayload(
                        id = "pp-e2e-evt-1",
                        bookId = "book-pp-1",
                        startPositionMs = 0L,
                        endPositionMs = spanMs,
                        startedAt = nowMs - spanMs,
                        endedAt = nowMs,
                        playbackSpeed = 1.0f,
                        tz = "UTC",
                        deviceLabel = null,
                        revision = 0L,
                        updatedAt = nowMs,
                        createdAt = nowMs,
                        deletedAt = null,
                    ),
                    clientOpId = null,
                    userId = "u1",
                )

                // Poll until the public_profiles row arrives in client Room via SSE.
                val profileRow =
                    withTimeout(ROUND_TRIP_TIMEOUT_SECONDS.seconds) {
                        var rows = clientDatabase.publicProfileDao().observeAll().first()
                        while (rows.none { it.id == "u1" }) {
                            rows = clientDatabase.publicProfileDao().observeAll().first()
                        }
                        rows.first { it.id == "u1" }
                    }

                profileRow shouldNotBe null
                profileRow.displayName shouldBe "Test User"
                // stats reflect the 30-second listening event
                profileRow.totalSecondsAllTime shouldBe spanMs / 1_000L

                // Assert LeaderboardRepositoryImpl surfaces the row with rank 1
                val snapshot =
                    LeaderboardRepositoryImpl(clientDatabase.publicProfileDao())
                        .observeSnapshot(LeaderboardPeriod.AllTime)
                        .first()
                snapshot.time.size shouldBe 1
                snapshot.time.first().userId shouldBe "u1"
                snapshot.time.first().rank shouldBe 1
            }
        }
    })
