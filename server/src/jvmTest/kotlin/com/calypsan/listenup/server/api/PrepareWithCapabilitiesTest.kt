package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.CodecCapability
import com.calypsan.listenup.api.dto.PreparedPlayback
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import com.calypsan.listenup.server.transcode.TranscoderAvailability
import com.calypsan.listenup.server.transcode.TranscoderStatus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/**
 * What `prepare()` decides for one client and one book — the capability decision table, end to end
 * through a real database rather than against [com.calypsan.listenup.server.transcode.TranscodePolicy]
 * in isolation.
 *
 * ⛔ The legacy-client case is the load-bearing one: a client that sends no capabilities must get
 * byte-identical behaviour to before transcoding existed, on every book, forever.
 */
class PrepareWithCapabilitiesTest :
    FunSpec({

        test("a legacy client sending no capabilities gets no hlsUrl even for an xHE book") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("u1")
                val deps = buildDeps(sql, driver)
                runTest {
                    deps.bookRepo.upsert(bookWithCodec("b1", codec = "aac", profile = "xhe"))
                    deps.makeReachable("b1", "u1")

                    val pb =
                        deps
                            .service(sql, "u1", availability = availableEncoder())
                            .prepare(BookId("b1"))
                            .shouldBeInstanceOf<AppResult.Success<PreparedPlayback>>()
                            .data

                    pb.audioFiles
                        .single()
                        .hlsUrl
                        .shouldBeNull()
                    pb.transcodeUnavailable shouldBe false
                }
            }
        }

        test("a client that declares AAC-LC gets direct URLs for an LC book") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("u1")
                val deps = buildDeps(sql, driver)
                runTest {
                    deps.bookRepo.upsert(bookWithCodec("b1", codec = "aac", profile = "lc"))
                    deps.makeReachable("b1", "u1")

                    val pb =
                        deps
                            .service(sql, "u1", availability = availableEncoder())
                            .prepare(BookId("b1"), capabilities = setOf(CodecCapability.AAC_LC))
                            .shouldBeInstanceOf<AppResult.Success<PreparedPlayback>>()
                            .data

                    pb.audioFiles
                        .single()
                        .hlsUrl
                        .shouldBeNull()
                    pb.transcodeUnavailable shouldBe false
                }
            }
        }

        test("a client without xHE gets an hlsUrl for an xHE book, and still a direct url") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("u1")
                val deps = buildDeps(sql, driver)
                runTest {
                    deps.bookRepo.upsert(bookWithCodec("b1", codec = "aac", profile = "xhe"))
                    deps.makeReachable("b1", "u1")

                    val pb =
                        deps
                            .service(sql, "u1", availability = availableEncoder())
                            .prepare(BookId("b1"), capabilities = setOf(CodecCapability.AAC_LC))
                            .shouldBeInstanceOf<AppResult.Success<PreparedPlayback>>()
                            .data

                    val file = pb.audioFiles.single()
                    file.hlsUrl.shouldNotBeNull().shouldContain("/api/v1/hls/b1/af-0/master.m3u8")
                    // Never stranded: the original is still offered alongside the stream.
                    file.url.shouldContain("/api/v1/audio/b1/af-0")
                    pb.transcodeUnavailable shouldBe false
                }
            }
        }

        test("with no encoder, an xHE book direct-plays and says so") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("u1")
                val deps = buildDeps(sql, driver)
                runTest {
                    deps.bookRepo.upsert(bookWithCodec("b1", codec = "aac", profile = "xhe"))
                    deps.makeReachable("b1", "u1")

                    // Default TranscoderAvailability has not been published to: no encoder.
                    val pb =
                        deps
                            .service(sql, "u1")
                            .prepare(BookId("b1"), capabilities = setOf(CodecCapability.AAC_LC))
                            .shouldBeInstanceOf<AppResult.Success<PreparedPlayback>>()
                            .data

                    pb.audioFiles
                        .single()
                        .hlsUrl
                        .shouldBeNull()
                    pb.transcodeUnavailable shouldBe true
                }
            }
        }
    })

/** A [TranscoderAvailability] that reports a usable encoder. No process is ever spawned here. */
private fun availableEncoder(): TranscoderAvailability =
    TranscoderAvailability().apply {
        publish(TranscoderStatus.Available(path = "/nonexistent/ffmpeg", version = "test"))
    }

/** A one-file book whose audio stream carries an explicit [codec]/[profile] pair for the policy. */
private fun bookWithCodec(
    bookId: String,
    codec: String,
    profile: String?,
): BookSyncPayload =
    bookWithThreeFiles(bookId).let { base ->
        base.copy(
            audioFiles =
                listOf(
                    base.audioFiles.first().copy(codec = codec, codecProfile = profile, sampleRate = 44_100),
                ),
        )
    }
