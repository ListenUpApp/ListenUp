package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.CodecCapability
import com.calypsan.listenup.api.dto.PreparePurpose
import com.calypsan.listenup.api.dto.PreparedAudioFile
import com.calypsan.listenup.api.dto.PreparedPlayback
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import com.calypsan.listenup.server.transcode.TranscoderAvailability
import com.calypsan.listenup.server.transcode.TranscoderStatus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/**
 * What `prepare()` mints for each [PreparePurpose].
 *
 * A download can wait for Wi-Fi overnight or resume days later from the URL it started with, so its
 * URLs must outlive that wait; a stream keeps the listening-session lifetime it always had. A call
 * with no purpose at all is what every client older than the parameter sends, and it must be a
 * stream, byte for byte.
 */
class PreparePurposeTest :
    FunSpec({

        suspend fun prepared(
            purpose: PreparePurpose?,
            capabilities: Set<CodecCapability>? = null,
        ): PreparedAudioFile {
            var file: PreparedAudioFile? = null
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("u1")
                val deps = buildDeps(sql, driver)
                runTest {
                    deps.bookRepo.upsert(bookWithThreeFiles("b1"))
                    deps.makeReachable("b1", "u1")
                    file =
                        deps
                            .service(sql, "u1", availability = availableEncoder())
                            .prepare(BookId("b1"), capabilities, purpose = purpose)
                            .shouldBeInstanceOf<AppResult.Success<PreparedPlayback>>()
                            .data.audioFiles
                            .first()
                    val query = file.shouldNotBeNull().url.substringAfter("?")
                    val params = query.split("&").associate { it.substringBefore("=") to it.substringAfter("=") }
                    deps.signer.verify(
                        userId = params.getValue("u"),
                        bookId = "b1",
                        fileId = "af-0",
                        exp = params.getValue("exp").toLong(),
                        sig = params.getValue("sig"),
                    ) shouldBe true
                }
            }
            return file.shouldNotBeNull()
        }

        fun PreparedAudioFile.secondsToExpiry(): Long =
            url.substringAfter("exp=").substringBefore("&").toLong() - Clock.System.now().epochSeconds

        test("download URLs live for seven days") {
            val lifetime = prepared(PreparePurpose.Download).secondsToExpiry()

            lifetime shouldBeGreaterThan (7.days - 1.hours).inWholeSeconds
            lifetime shouldBeLessThan (7.days + 1.hours).inWholeSeconds
        }

        test("stream URLs keep the twelve-hour lifetime") {
            val lifetime = prepared(PreparePurpose.Stream).secondsToExpiry()

            lifetime shouldBeGreaterThan 11.hours.inWholeSeconds
            lifetime shouldBeLessThan 13.hours.inWholeSeconds
        }

        test("a call that names no purpose, as an older client's does, is a stream") {
            val lifetime = prepared(purpose = null).secondsToExpiry()

            lifetime shouldBeLessThan 13.hours.inWholeSeconds
        }

        test("a download is never offered a transcoded stream, even for a codec the client cannot decode") {
            // Without the purpose this client is offered HLS for this book (PrepareWithCapabilitiesTest);
            // a download is the original file, and a stream URL it would never fetch is noise.
            prepared(PreparePurpose.Download, capabilities = setOf(CodecCapability.MP3))
                .hlsUrl
                .shouldBeNull()
            prepared(PreparePurpose.Stream, capabilities = setOf(CodecCapability.MP3))
                .hlsUrl
                .shouldNotBeNull()
        }
    })

private fun availableEncoder(): TranscoderAvailability =
    TranscoderAvailability().apply {
        publish(TranscoderStatus.Available(path = "/nonexistent/ffmpeg", version = "test"))
    }
