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
 * `public_profiles` SSE event. The client's [com.calypsan.listenup.client.data.sync.domains.publicProfilesDomain]
 * handler applies it into
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
                // lives in :server's test source set and is not available from :app:sharedLogic:jvmTest.
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

        test("editing display name + tagline on the server propagates to the client public_profiles row") {
            withClientSyncEngineAgainstServer {
                // Seed user "u1" with the original display name; tagline is NULL initially.
                val seedNow = System.currentTimeMillis()
                serverDriver.execute(
                    null,
                    "INSERT INTO users(id, email, email_normalized, password_hash, role, " +
                        "display_name, status, created_at, updated_at) VALUES " +
                        "('u1', 'u1@example.com', 'u1@example.com', 'phc', " +
                        "'MEMBER', 'Original Name', 'ACTIVE', $seedNow, $seedNow)",
                    0,
                )

                engine.start(currentUserId = "u1")

                // Phase 1: fire a listening event so UserStatsUpdater calls
                // PublicProfileMaintainer.refresh("u1"), publishing the initial row.
                serverListeningEventRepository.upsert(
                    ListeningEventSyncPayload(
                        id = "pp-edit-evt-1",
                        bookId = "book-pp-edit-1",
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

                // Wait for the initial row to arrive before editing.
                withTimeout(ROUND_TRIP_TIMEOUT_SECONDS.seconds) {
                    var rows = clientDatabase.publicProfileDao().observeAll().first()
                    while (rows.none { it.id == "u1" && it.displayName == "Original Name" }) {
                        rows = clientDatabase.publicProfileDao().observeAll().first()
                    }
                }

                // EDIT the identity on the server via raw SQL — simulates ProfileServiceImpl
                // updating display_name and tagline in the users table.
                serverDriver.execute(
                    null,
                    "UPDATE users SET display_name = 'Edited Name', tagline = 'Edited bio' WHERE id = 'u1'",
                    0,
                )

                // Phase 2: fire a second listening event (distinct id) to re-trigger
                // UserStatsUpdater → PublicProfileMaintainer.refresh("u1"). The maintainer
                // re-reads display_name + tagline from the now-updated users row and publishes
                // a fresh public_profiles SSE event that the client applies into Room.
                serverListeningEventRepository.upsert(
                    ListeningEventSyncPayload(
                        id = "pp-edit-evt-2",
                        bookId = "book-pp-edit-1",
                        startPositionMs = spanMs,
                        endPositionMs = spanMs * 2,
                        startedAt = nowMs,
                        endedAt = nowMs + spanMs,
                        playbackSpeed = 1.0f,
                        tz = "UTC",
                        deviceLabel = null,
                        revision = 0L,
                        updatedAt = nowMs + spanMs,
                        createdAt = nowMs + spanMs,
                        deletedAt = null,
                    ),
                    clientOpId = null,
                    userId = "u1",
                )

                // Poll until the edited values propagate through SSE into the client Room row.
                val editedRow =
                    withTimeout(ROUND_TRIP_TIMEOUT_SECONDS.seconds) {
                        var rows = clientDatabase.publicProfileDao().observeAll().first()
                        while (rows.none { it.id == "u1" && it.displayName == "Edited Name" }) {
                            rows = clientDatabase.publicProfileDao().observeAll().first()
                        }
                        rows.first { it.id == "u1" }
                    }

                editedRow.displayName shouldBe "Edited Name"
                editedRow.tagline shouldBe "Edited bio"
            }
        }
    })
