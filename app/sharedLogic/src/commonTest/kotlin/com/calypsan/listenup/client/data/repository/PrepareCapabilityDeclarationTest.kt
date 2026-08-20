package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.PlaybackService
import com.calypsan.listenup.api.dto.CodecCapability
import com.calypsan.listenup.api.dto.PreparedPlayback
import com.calypsan.listenup.api.dto.RecordListeningEventRequest
import com.calypsan.listenup.api.dto.RecordPositionRequest
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.ListeningEventSyncPayload
import com.calypsan.listenup.api.sync.PlaybackPositionSyncPayload
import com.calypsan.listenup.api.sync.UserStatsSyncPayload
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.core.BookId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/**
 * The capability set is a property of the DEVICE, so it is injected once and travels on every
 * call — a per-call parameter would let one of the three `prepare` call sites forget, which is
 * the failure `platformCodecCapabilities`'s KDoc exists to rule out.
 */
class PrepareCapabilityDeclarationTest :
    FunSpec({
        test("prepare declares the device's codec capabilities without the caller passing them") {
            runTest {
                val recorder = RecordingPlaybackService()
                val repo =
                    PlaybackPrepareRepositoryImpl(
                        channel = RpcChannel.forTest(recorder),
                        codecCapabilities = setOf(CodecCapability.MP3, CodecCapability.AAC_LC),
                    )

                repo.prepare(BookId("book-1"))

                recorder.lastCapabilities shouldBe setOf(CodecCapability.MP3, CodecCapability.AAC_LC)
                recorder.lastForceTranscode shouldBe false
            }
        }

        test("forceTranscode is per-call and defaults to false") {
            runTest {
                val recorder = RecordingPlaybackService()
                val repo =
                    PlaybackPrepareRepositoryImpl(
                        channel = RpcChannel.forTest(recorder),
                        codecCapabilities = setOf(CodecCapability.MP3),
                    )

                repo.prepare(BookId("book-1"), forceTranscode = true)

                recorder.lastForceTranscode shouldBe true
            }
        }
    })

/** Records the capabilities and forceTranscode flag that reach the service via [PlaybackPrepareRepositoryImpl.prepare]. */
private class RecordingPlaybackService : PlaybackService {
    var lastCapabilities: Set<CodecCapability>? = null
    var lastForceTranscode: Boolean = false

    override suspend fun prepare(
        bookId: BookId,
        capabilities: Set<CodecCapability>?,
        forceTranscode: Boolean,
    ): AppResult<PreparedPlayback> {
        lastCapabilities = capabilities
        lastForceTranscode = forceTranscode
        return AppResult.Success(PreparedPlayback(bookId = bookId.value, audioFiles = emptyList(), resumePosition = null))
    }

    override suspend fun getPosition(bookId: BookId): AppResult<PlaybackPositionSyncPayload?> = AppResult.Success(null)

    override suspend fun recordPosition(request: RecordPositionRequest): AppResult<PlaybackPositionSyncPayload> = throw NotImplementedError()

    override suspend fun getStats(): AppResult<UserStatsSyncPayload?> = throw NotImplementedError()

    override suspend fun recordListeningEvent(
        request: RecordListeningEventRequest,
    ): AppResult<ListeningEventSyncPayload> = throw NotImplementedError()
}
