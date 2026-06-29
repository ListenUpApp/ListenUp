package com.calypsan.listenup.client.playback

import com.calypsan.listenup.client.domain.repository.DownloadRepository
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.core.BookId
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * Pins the contract that [ProgressTracker] does **not** record listening events.
 *
 * Listening history is owned exclusively by the canonical recording path
 * ([ListeningEventRecorder]). [ProgressTracker]'s sole responsibilities are
 * position persistence, full-playback-session tracking, and finished-marking —
 * never listening-event writes. The pre-removal tracker queued an
 * account-orphaned `evt-` row on every pause longer than 10s that never synced;
 * this test fails the moment any such write path is reintroduced.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProgressTrackerDoesNotRecordEventsTest :
    FunSpec({

        test("a play→pause session longer than 10s writes no listening events") {
            runTest {
                val queryContext = StandardTestDispatcher(testScheduler)
                val db = createInMemoryTestDatabase(queryContext = queryContext)
                try {
                    val dao = db.listeningEventDao()

                    val tracker =
                        ProgressTracker(
                            downloadRepository = mock<DownloadRepository>(),
                            positionRepository = defaultPositionRepository(),
                            scope = CoroutineScope(queryContext),
                        )

                    val bookId = BookId("book-1")
                    // Position-driven chunk threshold: 20s of audio elapses between start and pause,
                    // comfortably over the pre-removal 10s MIN_LISTENING_CHUNK_MS gate.
                    tracker.onPlaybackStarted(bookId = bookId, positionMs = 0L, speed = 1.0f)
                    tracker.onPlaybackPaused(bookId = bookId, positionMs = 20_000L, speed = 1.0f)

                    advanceUntilIdle()

                    // No listening event may have been written.
                    dao.getLatestEventTimestamp() shouldBe null
                    dao.getTotalDurationSince(0L) shouldBe 0L
                } finally {
                    db.close()
                }
            }
        }
    })
