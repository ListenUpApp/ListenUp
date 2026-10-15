package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.repository.PlaybackPositionRepositoryImpl
import com.calypsan.listenup.client.data.sync.testing.withClientSyncEngineAgainstServer
import com.calypsan.listenup.client.domain.repository.PlaybackUpdate
import com.calypsan.listenup.client.test.fake.FakeAuthSession
import com.calypsan.listenup.core.BookId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.withTimeout

private val ROUND_TRIP = 30.seconds
private const val FINISHED_AT = 1_730_000_000_000L

/**
 * Tier 3 (spec S2): a finish card dismissed here lands on the server without moving the position; one dismissed on
 * another device arrives here though its lastPlayedAt is no newer — the only way a dismissal can travel.
 */
class FinishedCardDismissalE2ETest :
    FunSpec({
        test("a dismissal made here reaches the server, and one made elsewhere arrives here, moving no position") {
            withClientSyncEngineAgainstServer {
                engine.start(currentUserId = "u1")
                serverPlaybackPositionRepository.recordPosition(
                    userId = "u1",
                    bookId = "b-done",
                    positionMs = 9_000L,
                    lastPlayedAt = FINISHED_AT,
                    finished = true,
                    playbackSpeed = 1.0f,
                    currentChapterId = null,
                    finishedAt = FINISHED_AT,
                )
                withTimeout(ROUND_TRIP) {
                    while (clientDatabase.playbackPositionDao().get(BookId("b-done"))?.isFinished != true) {
                        // Delivery latency is non-deterministic; poll the real row.
                    }
                }

                val repo =
                    PlaybackPositionRepositoryImpl(
                        dao = clientDatabase.playbackPositionDao(),
                        transactionRunner = RoomTransactionRunner(clientDatabase),
                        pendingQueue = queue,
                        authSession = FakeAuthSession(userId = "u1"),
                    )
                repo
                    .savePlaybackState(BookId("b-done"), PlaybackUpdate.DismissFinishedCard(atMs = FINISHED_AT + 100_000L))
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()
                withTimeout(ROUND_TRIP) {
                    while (serverPlaybackPositionRepository.getPosition("u1", "b-done")?.finishedCardDismissedAt != FINISHED_AT + 100_000L) {
                        // The outbox drains to the server.
                    }
                }
                serverPlaybackPositionRepository.getPosition("u1", "b-done")!!.lastPlayedAt shouldBe FINISHED_AT

                // Wait for the op to be acknowledged, so the next inbound frame is not shielded by it.
                withTimeout(ROUND_TRIP) {
                    while (clientDatabase.pendingOperationV2Dao().nextDispatchable(maxAttempts = 5).isNotEmpty()) {
                        // The op is acknowledged and removed.
                    }
                }
                // Another device dismisses later, with the same lastPlayedAt: only the dismissal rule can carry it here.
                serverPlaybackPositionRepository.recordPositionDetailed(
                    userId = "u1",
                    bookId = "b-done",
                    positionMs = 9_000L,
                    lastPlayedAt = FINISHED_AT,
                    finished = true,
                    playbackSpeed = 1.0f,
                    currentChapterId = null,
                    finishedCardDismissedAt = FINISHED_AT + 200_000L,
                )
                withTimeout(ROUND_TRIP) {
                    while (clientDatabase.playbackPositionDao().get(BookId("b-done"))?.finishedCardDismissedAt != FINISHED_AT + 200_000L) {
                        // The superseded-row rule raises the dismissal.
                    }
                }
                clientDatabase.playbackPositionDao().get(BookId("b-done"))!!.positionMs shouldBe 9_000L
            }
        }
    })
