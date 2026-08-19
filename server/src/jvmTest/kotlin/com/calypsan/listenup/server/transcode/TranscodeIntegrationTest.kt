package com.calypsan.listenup.server.transcode

import com.calypsan.listenup.server.testing.FfmpegTestSupport
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.io.files.Path
import java.nio.file.Files
import kotlin.io.path.absolutePathString
import kotlin.math.abs

/**
 * The gate that proves the arc: a real FFmpeg, a real file, and segments measured rather than
 * assumed. Everything else under `transcode/` is tested against fakes, so this is the only place the
 * declared timeline is checked against bytes that were actually written.
 *
 * ⛔ **The drift tolerance must never be widened.** A segment declared 10s but encoded 10.0078s
 * drifts about four minutes across a 92-hour book, silently corrupting every position a listener
 * saves. If one of these fails, the segment math in [HlsPlaylist] is wrong — relaxing the tolerance
 * would hide precisely the bug this file exists to catch. Falsified once on purpose: replacing the
 * frame-aligned length with a round `targetSeconds` turns the encoded segments into an alternating
 * `[431, 431, 430, …]` pattern and both drift tests go red.
 *
 * Skipped when no FFmpeg is on `PATH`; CI installs one and asserts it is there.
 */
class TranscodeIntegrationTest :
    FunSpec({
        val sourceDir = lazy { Files.createTempDirectory("listenup-transcode-src-") }
        val shortSource = lazy { FfmpegTestSupport.generateSine(sourceDir.value.resolve("short.m4a"), SOURCE_SECONDS) }
        val longSource = lazy { FfmpegTestSupport.generateSine(sourceDir.value.resolve("long.m4a"), LONG_SOURCE_SECONDS) }

        afterSpec {
            if (sourceDir.isInitialized()) sourceDir.value.toFile().deleteRecursively()
        }

        test("a transcoded segment is real AAC of exactly the declared length")
            .config(enabled = FfmpegTestSupport.isAvailable) {
                withEngine { engine, cache ->
                    engine.ensureRunning(session(shortSource.value), fromSegment = 0) shouldBe SessionAdmission.Admitted

                    // Segment 0 is only *complete* once FFmpeg has moved on to segment 1 — the file
                    // appears the moment the muxer opens it, half-written.
                    awaitSegment(cache, 1) shouldBe true

                    val frames = FfmpegTestSupport.frameCount(cache.segmentPath(BOOK_ID, FILE_ID, 0).toString())
                    val declared = shortPlan().segmentSeconds
                    withClue("declared ${declared}s, encoded $frames frames") {
                        frames shouldBe FRAMES_PER_SEGMENT
                        abs(secondsOf(frames) - declared) shouldBeLessThan DRIFT_TOLERANCE_SECONDS
                    }
                }
            }

        test("segment lengths do not drift across a long file")
            .config(enabled = FfmpegTestSupport.isAvailable) {
                withEngine { engine, cache ->
                    engine.ensureRunning(session(shortSource.value), fromSegment = 0)
                    awaitSegment(cache, DRIFT_SEGMENTS) shouldBe true

                    val frames =
                        (0 until DRIFT_SEGMENTS).map {
                            FfmpegTestSupport.frameCount(cache.segmentPath(BOOK_ID, FILE_ID, it).toString())
                        }
                    val declaredElapsed = shortPlan().segmentSeconds * DRIFT_SEGMENTS

                    // The cumulative offset is what a seek to segment N actually lands on. Declaring
                    // a round 10s would put this ~78ms out by here, and minutes out by the end.
                    withClue("per-segment frames $frames, declared elapsed ${declaredElapsed}s") {
                        frames.all { it == FRAMES_PER_SEGMENT } shouldBe true
                        abs(secondsOf(frames.sum()) - declaredElapsed) shouldBeLessThan DRIFT_TOLERANCE_SECONDS
                    }
                }
            }

        test("a run started mid-file numbers its segments from the seek point and stays aligned")
            .config(enabled = FfmpegTestSupport.isAvailable) {
                withEngine { engine, cache ->
                    engine.ensureRunning(session(longSource.value, LONG_SOURCE_SECONDS), fromSegment = MID_FILE_SEGMENT)
                    awaitSegment(cache, MID_FILE_SEGMENT + 1) shouldBe true

                    withClue("a seeked run must not renumber from zero") {
                        cache.has(BOOK_ID, FILE_ID, 0) shouldBe false
                    }
                    // Alignment survives the seek: `-ss` before `-i` lands on a frame boundary, so
                    // the segment a listener jumps to is the one the playlist promised.
                    FfmpegTestSupport.frameCount(
                        cache.segmentPath(BOOK_ID, FILE_ID, MID_FILE_SEGMENT).toString(),
                    ) shouldBe FRAMES_PER_SEGMENT
                }
            }
    })

private const val BOOK_ID = "book-under-test"
private const val FILE_ID = "file-under-test"

/** AAC-LC frame size — the unit FFmpeg can cut on, and the reason segments are not round numbers. */
private const val SAMPLES_PER_FRAME = 1024

private const val TARGET_SEGMENT_SECONDS = 10

/** `ceil(10 * 44100 / 1024)` — pinned literally so a change to the planner's math is visible here. */
private const val FRAMES_PER_SEGMENT = 431

/** Long enough for the drift run to have whole segments to spare. */
private const val SOURCE_SECONDS = 125

/** Long enough to contain [MID_FILE_SEGMENT] plus a successor. */
private const val LONG_SOURCE_SECONDS = 420

/** Well beyond `SEGMENT_LOOKAHEAD`, so this is a genuine mid-file start rather than a nudge. */
private const val MID_FILE_SEGMENT = 40

private const val DRIFT_SEGMENTS = 10

/** Two milliseconds across ten segments. Widening this defeats the purpose of the file. */
private const val DRIFT_TOLERANCE_SECONDS = 0.002

private const val AWAIT_TIMEOUT_MILLIS = 120_000L
private const val POLL_MILLIS = 100L

private fun shortPlan(): HlsPlaylist.Plan = HlsPlaylist.plan(SOURCE_SECONDS * 1000L, FfmpegTestSupport.SAMPLE_RATE, TARGET_SEGMENT_SECONDS)

private fun secondsOf(frames: Int): Double = frames.toDouble() * SAMPLES_PER_FRAME / FfmpegTestSupport.SAMPLE_RATE

private fun session(
    source: java.nio.file.Path,
    seconds: Int = SOURCE_SECONDS,
): TranscodeSession =
    TranscodeSession(
        bookId = BOOK_ID,
        fileId = FILE_ID,
        sourcePath = source.absolutePathString(),
        sampleRate = FfmpegTestSupport.SAMPLE_RATE,
        durationMs = seconds * 1000L,
    )

/**
 * Runs [block] against a real engine writing into a throwaway cache, then kills whatever it started.
 *
 * The engine is built exactly as DI builds it — a real [ProcessTranscodeSpawner] over a real
 * `ProcessRunner` — because a fake anywhere in this chain would defeat the test.
 */
private suspend fun withEngine(block: suspend (TranscodeSessionEngine, SegmentCache) -> Unit) {
    val home = Files.createTempDirectory("listenup-transcode-cache-")
    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val cache = SegmentCache(Path(home.absolutePathString()))
    val engine =
        TranscodeSessionEngine(
            ffmpegPath = { FfmpegTestSupport.ffmpeg!! },
            cache = cache,
            settings = TranscodeSettings(targetSegmentSeconds = TARGET_SEGMENT_SECONDS),
            newSpawner = { ProcessTranscodeSpawner(scope) },
        )
    try {
        block(engine, cache)
    } finally {
        engine.stopAll()
        scope.cancel()
        home.toFile().deleteRecursively()
    }
}

private suspend fun awaitSegment(
    cache: SegmentCache,
    index: Int,
): Boolean {
    var waited = 0L
    while (waited < AWAIT_TIMEOUT_MILLIS) {
        if (cache.has(BOOK_ID, FILE_ID, index)) return true
        delay(POLL_MILLIS)
        waited += POLL_MILLIS
    }
    return false
}
