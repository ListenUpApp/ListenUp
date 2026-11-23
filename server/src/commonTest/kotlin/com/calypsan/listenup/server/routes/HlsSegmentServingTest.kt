package com.calypsan.listenup.server.routes

import com.calypsan.listenup.server.io.deleteRecursively
import com.calypsan.listenup.server.io.writeBytes
import com.calypsan.listenup.server.transcode.AdtsFixtures
import com.calypsan.listenup.server.transcode.HlsPlaylist
import com.calypsan.listenup.server.transcode.SegmentCache
import com.calypsan.listenup.server.transcode.TranscodeSessionEngine
import com.calypsan.listenup.server.transcode.TranscodeSettings
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random

/** 25 s at 44.1 kHz plans three segments; the first is a whole one of this many frames. */
private const val FRAMES_PER_SEGMENT = 431
private const val BOOK_MILLIS = 25_000L
private const val SAMPLE_RATE = 44_100

/**
 * Serving a cached segment reads it from disk exactly once.
 *
 * Every segment is verified before it is served — its ADTS frames counted against what the playlist
 * promised — and that needs the whole file in memory. Streaming it from disk again afterwards
 * doubled the I/O of the hottest path in HLS playback, and on a NAS that is a second trip to a
 * spinning disk for every ten seconds of audio. The bytes that were verified are the bytes served.
 * Runs on JVM and linuxX64.
 */
class HlsSegmentServingTest :
    FunSpec({
        test("a verified cache hit is served from the one read that verified it") {
            val dir = Path(SystemTemporaryDirectory, "hls-serve-${Random.nextLong().toString(HEX_RADIX)}")
            val segment = AdtsFixtures.stream(FRAMES_PER_SEGMENT)
            // The reader hands back the verified segment while the file on disk holds other bytes of
            // the same length: a response that went back to disk would serve those instead.
            var reads = 0
            val cache =
                SegmentCache(dir) { _ ->
                    reads++
                    segment
                }
            val engine =
                TranscodeSessionEngine(
                    ffmpegPath = { "/nonexistent/ffmpeg" },
                    cache = cache,
                    settings = TranscodeSettings(),
                    newSpawner = { error("serving a cached segment must never start an encoder") },
                )
            val plan = HlsPlaylist.plan(BOOK_MILLIS, SAMPLE_RATE, TranscodeSettings.DEFAULT_SEGMENT_SECONDS)
            try {
                cache.prepareDir("b1", "f1")
                cache.segmentPath("b1", "f1", 0).writeBytes(ByteArray(segment.size))
                testApplication {
                    application {
                        install(PartialContent)
                        routing {
                            get("/seg") {
                                respondVerifiedSegment(
                                    call = call,
                                    engine = engine,
                                    cache = cache,
                                    target = HlsTarget("b1", "f1"),
                                    index = 0,
                                    plan = plan,
                                )
                            }
                        }
                    }

                    val whole = client.get("/seg")
                    whole.status shouldBe HttpStatusCode.OK
                    whole.headers[HttpHeaders.ContentLength] shouldBe segment.size.toString()
                    whole.readRawBytes().toList() shouldBe segment.toList()
                    reads shouldBe 1

                    // Still seekable: the range is cut from the same in-memory bytes.
                    val range = client.get("/seg") { header(HttpHeaders.Range, "bytes=7-13") }
                    range.status shouldBe HttpStatusCode.PartialContent
                    range.readRawBytes().toList() shouldBe segment.copyOfRange(7, 14).toList()
                    reads shouldBe 2
                }
            } finally {
                deleteRecursively(dir)
            }
        }
    })

private const val HEX_RADIX = 16
