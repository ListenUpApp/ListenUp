package com.calypsan.listenup.client.playback.cast

import com.calypsan.listenup.api.dto.PreparedAudioFile
import com.calypsan.listenup.api.dto.PreparedPlayback
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.repository.PlaybackPrepareRepository
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ServerUrl
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import dev.mokkery.verify.VerifyMode.Companion.exactly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

class CastPreparerTest :
    FunSpec({

        val bookId = BookId("book-1")
        val serverUrl = "http://s"

        fun twoFilePreparedPlayback(coverUrl: String?) =
            PreparedPlayback(
                bookId = bookId.value,
                audioFiles =
                    listOf(
                        PreparedAudioFile(
                            fileId = "f1",
                            index = 0,
                            url = "/api/v1/audio/b/f1?sig=1",
                            format = "mp3",
                            durationMs = 1_000L,
                            sizeBytes = 1_000L,
                        ),
                        PreparedAudioFile(
                            fileId = "f2",
                            index = 1,
                            url = "/api/v1/audio/b/f2?sig=2",
                            format = "m4b",
                            durationMs = 2_000L,
                            sizeBytes = 2_000L,
                        ),
                    ),
                resumePosition = null,
                coverUrl = coverUrl,
            )

        test("success — maps files to absolute URLs and prefixes cover URL") {
            runTest {
                val prepareRepository = mock<PlaybackPrepareRepository>()
                val serverConfig = mock<ServerConfig>()
                everySuspend { serverConfig.getServerUrl() } returns ServerUrl(serverUrl)
                everySuspend { prepareRepository.prepare(any()) } returns
                    AppResult.Success(twoFilePreparedPlayback("/api/v1/cover-cast/b?sig=c"))

                val result = CastPreparer(prepareRepository, serverConfig).prepareForCast(bookId)

                result.shouldNotBeNull()
                result.files.size shouldBe 2
                result.files[0].fileId shouldBe "f1"
                result.files[0].absoluteUrl shouldBe "http://s/api/v1/audio/b/f1?sig=1"
                result.files[0].format shouldBe "mp3"
                result.files[1].fileId shouldBe "f2"
                result.files[1].absoluteUrl shouldBe "http://s/api/v1/audio/b/f2?sig=2"
                result.files[1].format shouldBe "m4b"
                result.coverUrlAbsolute shouldBe "http://s/api/v1/cover-cast/b?sig=c"
            }
        }

        test("null coverUrl in PreparedPlayback yields null coverUrlAbsolute, files still mapped") {
            runTest {
                val prepareRepository = mock<PlaybackPrepareRepository>()
                val serverConfig = mock<ServerConfig>()
                everySuspend { serverConfig.getServerUrl() } returns ServerUrl(serverUrl)
                everySuspend { prepareRepository.prepare(any()) } returns AppResult.Success(twoFilePreparedPlayback(null))

                val result = CastPreparer(prepareRepository, serverConfig).prepareForCast(bookId)

                result.shouldNotBeNull()
                result.files.size shouldBe 2
                result.coverUrlAbsolute.shouldBeNull()
            }
        }

        test("no server URL — returns null without calling the RPC") {
            runTest {
                val prepareRepository = mock<PlaybackPrepareRepository>()
                val serverConfig = mock<ServerConfig>()
                everySuspend { serverConfig.getServerUrl() } returns null

                val result = CastPreparer(prepareRepository, serverConfig).prepareForCast(bookId)

                result.shouldBeNull()
                verifySuspend(exactly(0)) { prepareRepository.prepare(any()) }
            }
        }

        test("RPC failure — returns null") {
            runTest {
                val prepareRepository = mock<PlaybackPrepareRepository>()
                val serverConfig = mock<ServerConfig>()
                everySuspend { serverConfig.getServerUrl() } returns ServerUrl(serverUrl)
                everySuspend { prepareRepository.prepare(any()) } returns AppResult.Failure(InternalError())

                val result = CastPreparer(prepareRepository, serverConfig).prepareForCast(bookId)

                result.shouldBeNull()
            }
        }
    })
