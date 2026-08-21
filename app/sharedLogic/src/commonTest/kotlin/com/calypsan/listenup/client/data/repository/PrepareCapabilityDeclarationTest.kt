package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.PlaybackService
import com.calypsan.listenup.api.dto.CodecCapability
import com.calypsan.listenup.api.dto.PreparedPlayback
import com.calypsan.listenup.api.result.AppResult
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

        test("forceTranscode reaches the service as true when the caller passes it") {
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

/**
 * Records the capabilities and forceTranscode flag that reach the service via
 * [PlaybackPrepareRepositoryImpl.prepare]. Delegates every other [PlaybackService] member to
 * [NoOpPlaybackService] (`ResumePositionFetchBoundTest.kt`, same package) instead of repeating its
 * no-op overrides here.
 */
private class RecordingPlaybackService : PlaybackService by NoOpPlaybackService {
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
}
