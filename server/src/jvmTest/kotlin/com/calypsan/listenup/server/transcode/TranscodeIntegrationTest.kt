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
        val shortTailSource =
            lazy { FfmpegTestSupport.generateSine(sourceDir.value.resolve("tail.m4a"), SHORT_TAIL_MS / 1000.0) }

        afterSpec {
            if (sourceDir.isInitialized()) sourceDir.value.toFile().deleteRecursively()
        }

        test("a transcoded segment is real AAC of exactly the declared length")
            .config(enabled = FfmpegTestSupport.isAvailable) {
                withEngine { engine, cache ->
                    engine.ensureRunning(session(shortSource.value), fromSegment = 0) shouldBe SessionAdmission.Admitted

                    // Segment 0 reaches the cache only once its run has closed it — the file the muxer
                    // opens is half-written for as long as the encode of it takes.
                    awaitSegment(engine, cache, 0) shouldBe true

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
                    awaitSegment(engine, cache, DRIFT_SEGMENTS - 1) shouldBe true

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
                    awaitSegment(engine, cache, MID_FILE_SEGMENT) shouldBe true

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

        // #1742: a book whose last segment is a fraction of a second. The route refuses any segment
        // whose frame count differs from the playlist's promise, so every one — the short tail most
        // of all — must come out exactly as declared, from a run at the start and one seeked to the end.
        test("an AAC-LC book's sub-second final segment holds exactly the remainder the playlist declares")
            .config(enabled = FfmpegTestSupport.isAvailable) {
                assertEverySegmentAsDeclared(
                    session = shortTailSession(shortTailSource.value),
                    decoderPath = null,
                )
            }

        // The same book as xHE-AAC, which decodes through FDK — the path that failed on every
        // request for the last segment. Fixture: exhale 1.2.2 preset `b` (USAC + eSBR, 44.1 kHz
        // stereo) over 3.465 s of 440 Hz tone; FDK decodes it as 2048-sample frames behind 2048
        // samples of priming and a 220-sample limiter delay, all of which used to reach the tail.
        test("an xHE-AAC book's sub-second final segment holds exactly the remainder the playlist declares")
            .config(enabled = FfmpegTestSupport.isFdkAvailable) {
                assertEverySegmentAsDeclared(
                    session = shortTailSession(xheFixture(sourceDir.value), codecProfile = "xhe"),
                    decoderPath = FfmpegTestSupport.fdkDecoder,
                )
            }
    })

/** Encodes [session] from the start and from its second-to-last segment, checking every segment. */
private suspend fun assertEverySegmentAsDeclared(
    session: TranscodeSession,
    decoderPath: String?,
) {
    val plan = HlsPlaylist.plan(session.durationMs, session.sampleRate, SHORT_TAIL_TARGET_SECONDS)
    val last = plan.segmentDurations.lastIndex
    withClue("the fixture must end in a sub-second segment for this test to mean anything") {
        plan.segmentDurations.last() shouldBeLessThan 1.0
    }
    for (fromSegment in listOf(0, last - 1)) {
        val spawners = mutableListOf<ProcessTranscodeSpawner>()
        withEngine(targetSeconds = SHORT_TAIL_TARGET_SECONDS, decoderPath = decoderPath, onSpawn = spawners::add) { engine, cache ->
            engine.ensureRunning(session, fromSegment) shouldBe SessionAdmission.Admitted
            // A pipeline that never finishes says why in its stderr; without it a timeout explains nothing.
            withClue({
                "run from segment $fromSegment never finished; exited=${spawners.map { it.hasExited }}, stderr:\n" +
                    spawners.flatMap { it.stderrTail }.joinToString("\n")
            }) {
                awaitComplete(engine, cache, last, SHORT_TAIL_AWAIT_MILLIS) shouldBe true
            }

            val frames =
                (fromSegment..last).associateWith { index ->
                    AdtsFrames.countFrames(java.io.File(cache.segmentPath(BOOK_ID, FILE_ID, index).toString()).readBytes())
                }
            withClue("run from segment $fromSegment: frames $frames, expected ${(fromSegment..last).map(plan::expectedFrames)}") {
                frames.all { (index, count) -> count != null && count in plan.expectedFrames(index) } shouldBe true
            }
        }
    }
}

private fun shortTailSession(
    source: java.nio.file.Path,
    codecProfile: String? = null,
): TranscodeSession =
    TranscodeSession(
        bookId = BOOK_ID,
        fileId = FILE_ID,
        sourcePath = source.absolutePathString(),
        sampleRate = FfmpegTestSupport.SAMPLE_RATE,
        durationMs = SHORT_TAIL_MS,
        codec = "aac",
        codecProfile = codecProfile,
    )

/** Copies the committed xHE-AAC fixture out of the test classpath, where FFmpeg can open it by path. */
private fun xheFixture(dir: java.nio.file.Path): java.nio.file.Path {
    val dest = dir.resolve("xhe.m4a")
    if (!Files.exists(dest)) {
        val bytes = checkNotNull(TranscodeIntegrationTest::class.java.getResource(XHE_FIXTURE)) { "missing $XHE_FIXTURE" }.readBytes()
        Files.write(dest, bytes)
    }
    return dest
}

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

/**
 * One-second segments are 44 frames (1.021678 s) at 44.1 kHz, so 3.465 s is three whole segments and
 * a 0.400 s tail: the shape of the book in #1742, whose last segment held 0.40 s.
 */
private const val SHORT_TAIL_TARGET_SECONDS = 1
private const val SHORT_TAIL_MS = 3_465L
private const val XHE_FIXTURE = "/transcode/xhe-aac-3465ms.m4a"

/** Each of the two runs a short-tail case makes must fit, with room to spare, inside the project's per-test timeout. */
private const val SHORT_TAIL_AWAIT_MILLIS = 40_000L

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
private suspend fun withEngine(
    targetSeconds: Int = TARGET_SEGMENT_SECONDS,
    decoderPath: String? = null,
    onSpawn: (ProcessTranscodeSpawner) -> Unit = {},
    block: suspend (TranscodeSessionEngine, SegmentCache) -> Unit,
) {
    val home = Files.createTempDirectory("listenup-transcode-cache-")
    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val cache = SegmentCache(Path(home.absolutePathString()))
    val engine =
        TranscodeSessionEngine(
            ffmpegPath = { FfmpegTestSupport.ffmpeg!! },
            decoderPath = { decoderPath },
            cache = cache,
            settings = TranscodeSettings(targetSegmentSeconds = targetSeconds),
            newSpawner = { ProcessTranscodeSpawner(scope).also(onSpawn) },
        )
    try {
        block(engine, cache)
    } finally {
        engine.stopAll()
        scope.cancel()
        home.toFile().deleteRecursively()
    }
}

/** Waits until segment [index] has been finished and promoted into the cache, promoting as the route does. */
private suspend fun awaitSegment(
    engine: TranscodeSessionEngine,
    cache: SegmentCache,
    index: Int,
): Boolean = awaitComplete(engine, cache, index, AWAIT_TIMEOUT_MILLIS)

/** Waits until segment [index] is finished — promoted out of its run into the cache. */
private suspend fun awaitComplete(
    engine: TranscodeSessionEngine,
    cache: SegmentCache,
    index: Int,
    timeoutMillis: Long,
): Boolean {
    var waited = 0L
    while (waited < timeoutMillis) {
        engine.promoteFinished(BOOK_ID, FILE_ID)
        if (cache.isComplete(BOOK_ID, FILE_ID, index)) return true
        delay(POLL_MILLIS)
        waited += POLL_MILLIS
    }
    return false
}
