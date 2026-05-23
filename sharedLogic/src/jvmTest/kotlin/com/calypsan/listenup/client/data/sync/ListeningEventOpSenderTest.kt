package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.PlaybackService
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.RecordListeningEventRequest
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.result.AppResult as WireAppResult
import com.calypsan.listenup.api.sync.ListeningEventSyncPayload
import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.client.data.remote.PlaybackRpcFactory
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verify.VerifyMode.Companion.exactly
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

class ListeningEventOpSenderTest :
    FunSpec({

        test("decodes the payload and calls recordListeningEvent; returns Success on RPC success") {
            runTest {
                val request =
                    RecordListeningEventRequest(
                        id = "evt-1",
                        bookId = "book-1",
                        startPositionMs = 0L,
                        endPositionMs = 60_000L,
                        startedAt = 1_700_000_000_000L,
                        endedAt = 1_700_000_060_000L,
                        playbackSpeed = 1.0f,
                        tz = "UTC",
                        deviceLabel = null,
                    )
                val payload = contractJson.encodeToString(RecordListeningEventRequest.serializer(), request)

                val service: PlaybackService = mock()
                val syncPayload = fakeSyncPayload(request)
                everySuspend { service.recordListeningEvent(any()) } returns WireAppResult.Success(syncPayload)

                val rpcFactory: PlaybackRpcFactory = mock()
                everySuspend { rpcFactory.playbackService() } returns service

                val sender = ListeningEventOpSender(rpcFactory)
                val op = fakeOp(payload)

                val result = sender.send(op)

                result shouldBe AppResult.Success(Unit)
                verifySuspend(exactly(1)) { service.recordListeningEvent(request) }
            }
        }

        test("propagates a WireAppResult.Failure as AppResult.Failure") {
            runTest {
                val request =
                    RecordListeningEventRequest(
                        id = "evt-2",
                        bookId = "book-2",
                        startPositionMs = 1_000L,
                        endPositionMs = 30_000L,
                        startedAt = 1_700_000_000_000L,
                        endedAt = 1_700_000_030_000L,
                        playbackSpeed = 1.5f,
                        tz = "Europe/London",
                        deviceLabel = "iPhone",
                    )
                val payload = contractJson.encodeToString(RecordListeningEventRequest.serializer(), request)

                val expectedError = SyncError.PushFailed()
                val service: PlaybackService = mock()
                everySuspend { service.recordListeningEvent(any()) } returns WireAppResult.Failure(expectedError)

                val rpcFactory: PlaybackRpcFactory = mock()
                everySuspend { rpcFactory.playbackService() } returns service

                val sender = ListeningEventOpSender(rpcFactory)
                val op = fakeOp(payload)

                val result = sender.send(op)

                val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                failure.error shouldBe expectedError
            }
        }
    })

private fun fakeOp(payload: String): PendingOperation =
    PendingOperation(
        clientOpId = "op-1",
        domainName = "listening_events",
        entityId = "evt-1",
        opType = "upsert",
        payload = payload,
        enqueuedAt = 1_000L,
        failureCount = 0,
        ownerUserId = "user-1",
    )

private fun fakeSyncPayload(req: RecordListeningEventRequest): ListeningEventSyncPayload =
    ListeningEventSyncPayload(
        id = req.id,
        bookId = req.bookId,
        startPositionMs = req.startPositionMs,
        endPositionMs = req.endPositionMs,
        startedAt = req.startedAt,
        endedAt = req.endedAt,
        playbackSpeed = req.playbackSpeed,
        tz = req.tz,
        deviceLabel = req.deviceLabel,
        revision = 1L,
        updatedAt = req.endedAt,
        createdAt = req.startedAt,
        deletedAt = null,
    )
