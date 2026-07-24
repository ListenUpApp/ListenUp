@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.calypsan.listenup.client.listening

import com.calypsan.listenup.api.dto.auth.DeviceInfo
import com.calypsan.listenup.api.sync.ListeningEventSyncPayload
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.local.db.TentativeSpanEntity
import com.calypsan.listenup.client.data.sync.domains.OpKind
import com.calypsan.listenup.client.data.sync.domains.OutboxChannels
import com.calypsan.listenup.client.data.sync.testing.withClientSyncEngineAgainstServer
import com.calypsan.listenup.client.device.DeviceInfoProvider
import com.calypsan.listenup.client.playback.ListeningEventRecorder
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.withTimeout

private const val ROUND_TRIP_TIMEOUT_SECONDS = 30

/**
 * Tier 3 e2e tests proving the `listening_events` and `user_stats` domains sync in
 * both directions across the full in-process server + client stack.
 *
 * Boots the full harness via [withClientSyncEngineAgainstServer] and exercises:
 *
 * 1. **Server→client sync.** [serverListeningEventRepository.upsert] publishes per-user
 *    SSE events for both `listening_events` (the new row) and `user_stats` (the
 *    [com.calypsan.listenup.server.services.UserStatsUpdater]-derived row). Both events
 *    cross the real SSE firehose and land in the client's Room tables.
 *
 * 2. **Client→server sync.** [ListeningEventRecorder.onPlay] + [ListeningEventRecorder.onPause]
 *    finalize a span into a [com.calypsan.listenup.client.data.local.db.ListeningEventEntity],
 *    enqueue a pending op, and the engine's reactive drain dispatches it through the
 *    [com.calypsan.listenup.client.data.sync.testing.DirectListeningEventSender] to the
 *    server's [com.calypsan.listenup.server.services.ListeningEventRepository]. The row
 *    is then verifiable via [serverListeningEventRepository.pullSince] and the derived
 *    [com.calypsan.listenup.server.services.UserStatsRepository.getForUser] call.
 */
class ListeningEventEndToEndTest :
    FunSpec({

        /** Wall-clock milliseconds used to construct a deterministic 30-second span. */
        val nowMs = 1_779_451_200_000L
        val spanMs = 30_000L

        test("server upsert → SSE → client Room has event with non-zero revision and stats row") {
            withClientSyncEngineAgainstServer {
                engine.start(currentUserId = "u1")

                val eventId = "e2e-server-evt-1"
                val payload =
                    ListeningEventSyncPayload(
                        id = eventId,
                        bookId = "book-e2e-1",
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
                    )

                serverListeningEventRepository.upsert(payload, clientOpId = null, userId = "u1")

                // ── Assert 1: event lands in client Room `listening_events` with non-zero revision
                val clientEvent =
                    withTimeout(ROUND_TRIP_TIMEOUT_SECONDS.seconds) {
                        var row = clientDatabase.listeningEventDao().getById(eventId)
                        while (row == null) {
                            row = clientDatabase.listeningEventDao().getById(eventId)
                        }
                        row
                    }

                clientEvent shouldNotBe null
                clientEvent.bookId shouldBe "book-e2e-1"
                clientEvent.playbackSpeed shouldBe 1.0f
                // Non-zero revision proves the row arrived via the domain handler, not as a stub.
                clientEvent.revision shouldBeGreaterThan 0L

                // ── Assert 2: stats row populates in client Room `user_stats` via UserStatsUpdater
                // The server's UserStatsUpdater fires atomically on the event upsert and publishes
                // a user_stats SSE event in the same transaction. Poll until it lands.
                val clientStats =
                    withTimeout(ROUND_TRIP_TIMEOUT_SECONDS.seconds) {
                        var row = clientDatabase.userStatsDao().getForUser("u1")
                        while (row == null) {
                            row = clientDatabase.userStatsDao().getForUser("u1")
                        }
                        row
                    }

                clientStats shouldNotBe null
                // wallSeconds = (endedAt - startedAt) / 1000 = spanMs / 1000
                clientStats.totalSecondsAllTime shouldBe spanMs / 1_000L
                clientStats.revision shouldBeGreaterThan 0L
            }
        }

        test("client recorder onPlay+onPause → queue drain → event and stats on server") {
            withClientSyncEngineAgainstServer {
                engine.start(currentUserId = "u1")

                val spanId = Uuid.random().toString() // stable id so we can assert by id if needed

                // Build a recorder wired directly to the client DB + harness queue.
                // The clock always returns `nowMs` so onPause's endedAt = nowMs.
                // The seeded tentative span has startedAt = nowMs - spanMs, so the span
                // covers 30 seconds and is NOT zero-duration (which would be silently dropped).
                val recorder =
                    ListeningEventRecorder(
                        listeningEventDao = clientDatabase.listeningEventDao(),
                        tentativeSpanDao = clientDatabase.tentativeSpanDao(),
                        transactionRunner = RoomTransactionRunner(clientDatabase),
                        enqueue = { entityId, payloadJson, ownerUserId ->
                            queue.enqueue(
                                channel = OutboxChannels.ListeningEvents,
                                entityId = entityId,
                                op = OpKind.Upsert,
                                payload = payloadJson,
                                ownerUserId = ownerUserId,
                            )
                        },
                        currentUserId = { "u1" },
                        deviceInfo = DeviceInfoProvider { DeviceInfo() },
                        clock =
                            object : kotlin.time.Clock {
                                override fun now(): kotlin.time.Instant = kotlin.time.Instant.fromEpochMilliseconds(nowMs)
                            },
                    )

                // Seed the tentative span so the recorder finds an open span to finalize.
                clientDatabase.tentativeSpanDao().upsertSingleton(
                    TentativeSpanEntity(
                        id = spanId,
                        userId = "u1",
                        bookId = "book-e2e-2",
                        startPositionMs = 0L,
                        currentPositionMs = 0L,
                        startedAt = nowMs - spanMs,
                        lastHeartbeatAt = nowMs - spanMs,
                        playbackSpeed = 1.0f,
                        tz = "UTC",
                        deviceLabel = null,
                    ),
                )
                // onPause finalizes the seeded span: endedAt=nowMs, startedAt=nowMs-spanMs → 30s.
                recorder.onPause(positionMs = spanMs)

                // Poll server until the event arrives via the engine's reactive queue drain.
                val serverEvent =
                    withTimeout(ROUND_TRIP_TIMEOUT_SECONDS.seconds) {
                        var page = serverListeningEventRepository.pullSince("u1", 0, 50)
                        while (page.items.isEmpty()) {
                            page = serverListeningEventRepository.pullSince("u1", 0, 50)
                        }
                        page.items.first()
                    }

                serverEvent.bookId shouldBe "book-e2e-2"
                serverEvent.revision shouldBeGreaterThan 0L

                // Stats must reflect the 30-second span via UserStatsUpdater.
                val serverStats =
                    withTimeout(ROUND_TRIP_TIMEOUT_SECONDS.seconds) {
                        var stats = serverUserStatsRepository.getForUser("u1")
                        while (stats == null) {
                            stats = serverUserStatsRepository.getForUser("u1")
                        }
                        stats
                    }

                // wallSeconds = (endedAt - startedAt) / 1000 = spanMs / 1000
                serverStats.totalSecondsAllTime shouldBe spanMs / 1_000L
            }
        }
    })
