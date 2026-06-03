package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.PlaybackService
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.RecordPositionRequest
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.result.AppResult as WireAppResult
import com.calypsan.listenup.api.sync.PlaybackPositionSyncPayload
import com.calypsan.listenup.api.result.AppResult
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

class PlaybackPositionOpSenderTest :
    FunSpec({

        test("decodes the payload and calls recordPosition; returns Success on RPC success") {
            runTest {
                val request =
                    RecordPositionRequest(
                        bookId = "book-1",
                        positionMs = 60_000L,
                        lastPlayedAt = 1_700_000_000_000L,
                        finished = false,
                        playbackSpeed = 1.25f,
                        currentChapterId = null,
                    )
                val payload = contractJson.encodeToString(RecordPositionRequest.serializer(), request)

                val service: PlaybackService = mock()
                val syncPayload = fakeSyncPayload(request)
                everySuspend { service.recordPosition(any()) } returns WireAppResult.Success(syncPayload)

                val rpcFactory: PlaybackRpcFactory = mock()
                everySuspend { rpcFactory.playbackService() } returns service

                val sender = PlaybackPositionOpSender(rpcFactory)
                val op = fakeOp(payload)

                val result = sender.send(op)

                result shouldBe AppResult.Success(Unit)
                verifySuspend(exactly(1)) { service.recordPosition(request) }
            }
        }

        test("propagates a WireAppResult.Failure as AppResult.Failure") {
            runTest {
                val request =
                    RecordPositionRequest(
                        bookId = "book-2",
                        positionMs = 0L,
                        lastPlayedAt = 1_700_000_000_000L,
                        finished = true,
                        playbackSpeed = 1.0f,
                        currentChapterId = "ch-1",
                    )
                val payload = contractJson.encodeToString(RecordPositionRequest.serializer(), request)

                val expectedError = SyncError.PushFailed()
                val service: PlaybackService = mock()
                everySuspend { service.recordPosition(any()) } returns WireAppResult.Failure(expectedError)

                val rpcFactory: PlaybackRpcFactory = mock()
                everySuspend { rpcFactory.playbackService() } returns service

                val sender = PlaybackPositionOpSender(rpcFactory)
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
        domainName = "playback_positions",
        entityId = "book-1",
        opType = "upsert",
        payload = payload,
        enqueuedAt = 1_000L,
        failureCount = 0,
        ownerUserId = "user-1",
    )

private fun fakeSyncPayload(req: RecordPositionRequest): PlaybackPositionSyncPayload =
    PlaybackPositionSyncPayload(
        id = "pos-1",
        bookId = req.bookId,
        positionMs = req.positionMs,
        lastPlayedAt = req.lastPlayedAt,
        finished = req.finished,
        playbackSpeed = req.playbackSpeed,
        currentChapterId = req.currentChapterId,
        revision = 1L,
        updatedAt = req.lastPlayedAt,
        createdAt = req.lastPlayedAt,
        deletedAt = null,
    )
