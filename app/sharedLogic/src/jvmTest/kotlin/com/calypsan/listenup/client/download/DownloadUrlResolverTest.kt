package com.calypsan.listenup.client.download

import com.calypsan.listenup.api.dto.PreparedAudioFile
import com.calypsan.listenup.api.dto.PreparedPlayback
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Unit tests for [resolveSignedDownloadUrl] — the shared resolver that both Android
 * ([downloadAudioFile]) and iOS ([com.calypsan.listenup.client.download.AppleDownloadService]) use to
 * obtain the signed `GET /api/v1/audio/{bookId}/{fileId}?u=&exp=&sig=` URL from
 * [com.calypsan.listenup.api.PlaybackService.prepare].
 *
 * Reuses `FakePlaybackPrepareRepository` (internal, same package — defined in `DownloadWorkerLogicTest.kt`).
 */
class DownloadUrlResolverTest :
    FunSpec({

        test("returns the signed relative URL for the matching fileId on success") {
            val bookId = "book-1"
            val audioFileId = "file-1"
            val signedUrl = "/api/v1/audio/$bookId/$audioFileId?u=user&exp=123&sig=abc"

            val factory =
                FakePlaybackPrepareRepository(
                    AppResult.Success(
                        PreparedPlayback(
                            bookId = bookId,
                            audioFiles =
                                listOf(
                                    PreparedAudioFile(
                                        fileId = audioFileId,
                                        index = 0,
                                        url = signedUrl,
                                        format = "mp3",
                                        durationMs = 1000L,
                                        sizeBytes = 1000L,
                                    ),
                                ),
                            resumePosition = null,
                        ),
                    ),
                )

            val result = resolveSignedDownloadUrl(bookId, audioFileId, factory)

            result.shouldBeInstanceOf<AppResult.Success<String>>()
            result.data shouldBe signedUrl
            // The old, broken hardcoded route must never be produced.
            result.data.shouldNotContain("/api/v1/books/")
        }

        test("returns Failure when prepare() fails") {
            val factory =
                FakePlaybackPrepareRepository(
                    AppResult.Failure(TransportError.NetworkUnavailable(debugInfo = "simulated")),
                )

            val result = resolveSignedDownloadUrl("book-2", "file-2", factory)

            result.shouldBeInstanceOf<AppResult.Failure>()
        }

        test("returns Failure when the requested fileId is absent from the prepare() response") {
            val factory =
                FakePlaybackPrepareRepository(
                    AppResult.Success(
                        PreparedPlayback(
                            bookId = "book-3",
                            audioFiles =
                                listOf(
                                    PreparedAudioFile(
                                        fileId = "a-different-file",
                                        index = 0,
                                        url = "/api/v1/audio/book-3/a-different-file?sig=x",
                                        format = "mp3",
                                        durationMs = 1000L,
                                        sizeBytes = 1000L,
                                    ),
                                ),
                            resumePosition = null,
                        ),
                    ),
                )

            val result = resolveSignedDownloadUrl("book-3", "missing-file", factory)

            result.shouldBeInstanceOf<AppResult.Failure>()
        }
    })
