package com.calypsan.listenup.server.transcode

import com.calypsan.listenup.server.io.deleteRecursively
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random

/**
 * The engine is the only stateful unit here, so its tests use a fake spawner and assert on the
 * commands it would have run — the real FFmpeg path is covered by the integration test in Task 13.
 *
 * The idle watchdog is tested through [TranscodeSessionEngine.sweepIdle] against an injected clock
 * rather than through its timer loop: `commonTest` has no `kotlinx-coroutines-test`, so there is no
 * virtual time on this source set, and a test that really slept for the idle timeout would be both
 * slow and flaky. The loop that calls it is three lines and is started by DI wiring.
 */
class TranscodeSessionEngineTest :
    FunSpec({

        test("the first segment request starts an encode at that timestamp") {
            val fake = FakeSpawner()
            val fixture = engineWith(fake)

            fixture.engine.ensureRunning(SESSION, fromSegment = 0)

            fake.commands.single().shouldContainFlag("-ss", "0.000000")
            fixture.cleanUp()
        }

        // Sequential listening is the audiobook 99% — a request just ahead of the encoder must ride
        // the process already running, not start a second one.
        test("a later segment in the same run does not respawn") {
            val fake = FakeSpawner()
            val fixture = engineWith(fake)

            fixture.engine.ensureRunning(SESSION, fromSegment = 0)
            fixture.engine.ensureRunning(SESSION, fromSegment = 3)

            fake.commands.size shouldBe 1
            fixture.cleanUp()
        }

        // FFmpeg reports a segment finished by appending to a list file. If two runs shared one
        // list, the second would truncate the first's record and every segment the earlier run
        // completed would look unfinished forever.
        test("each run writes its own completion list, so a respawn cannot erase the last one") {
            val fake = FakeSpawner()
            val fixture = engineWith(fake)

            fixture.engine.ensureRunning(SESSION, fromSegment = 0)
            fixture.engine.ensureRunning(SESSION, fromSegment = 500)

            val lists = fake.commands.map { it[it.indexOf("-segment_list") + 1] }
            lists.toSet().size shouldBe 2
            fixture.cleanUp()
        }

        // A real seek, backwards or far ahead, has to abandon and restart.
        test("an out-of-window seek kills and respawns at the new timestamp") {
            val fake = FakeSpawner()
            val fixture = engineWith(fake)

            fixture.engine.ensureRunning(SESSION, fromSegment = 0)
            fixture.engine.ensureRunning(SESSION, fromSegment = 500)

            fake.killed shouldBe 1
            fake.commands.size shouldBe 2
            // 500 * (431 * 1024 / 44100) = 5003.900226...
            fake.commands.last().shouldContainFlag("-ss", "5003.900226")
            fixture.cleanUp()
        }

        // The encoder only ever moves forward, so a backward seek can never be ridden.
        test("a backward seek respawns even though it is inside the lookahead window") {
            val fake = FakeSpawner()
            val fixture = engineWith(fake)

            fixture.engine.ensureRunning(SESSION, fromSegment = 10)
            fixture.engine.ensureRunning(SESSION, fromSegment = 9)

            fake.killed shouldBe 1
            fake.commands.size shouldBe 2
            fixture.cleanUp()
        }

        // ⛔ Admission gate, not a queue: over-cap must fail fast and retryably rather than pile up.
        test("over the concurrency cap the caller gets a retryable busy") {
            val fake = FakeSpawner()
            val fixture = engineWith(fake, maxConcurrent = 1)
            fixture.engine.ensureRunning(SESSION, fromSegment = 0)

            val result = fixture.engine.ensureRunning(SESSION.copy(bookId = "b2"), fromSegment = 0)

            result.shouldBeInstanceOf<SessionAdmission.Busy>()
            fixture.cleanUp()
        }

        // A listener who walks away must not leave FFmpeg running behind them.
        test("a session untouched for longer than the idle timeout is stopped") {
            val fake = FakeSpawner()
            val fixture = engineWith(fake)
            fixture.engine.ensureRunning(SESSION, fromSegment = 0)

            fixture.clock += TranscodeSessionEngine.IDLE_KILL_MILLIS + 1
            fixture.engine.sweepIdle()

            fake.killed shouldBe 1
            fixture.cleanUp()
        }

        test("a session still being requested survives the sweep") {
            val fake = FakeSpawner()
            val fixture = engineWith(fake)
            fixture.engine.ensureRunning(SESSION, fromSegment = 0)

            fixture.clock += TranscodeSessionEngine.IDLE_KILL_MILLIS - 1
            fixture.engine.sweepIdle()

            fake.killed shouldBe 0
            fixture.cleanUp()
        }
    })

private val SESSION =
    TranscodeSession(
        bookId = "b1",
        fileId = "f1",
        sourcePath = "/x/a.m4b",
        sampleRate = 44_100,
        durationMs = 3_600_000,
    )

/** Records what the engine would have run instead of spawning anything. */
private class FakeSpawner {
    val pipelines = mutableListOf<List<List<String>>>()
    var killed = 0

    /** The first stage of each run — for a source FFmpeg can decode, that is the whole pipeline. */
    val commands: List<List<String>> get() = pipelines.map { it.first() }

    fun spawner(): TranscodeSpawner =
        object : TranscodeSpawner {
            override suspend fun start(commands: List<List<String>>) {
                pipelines += commands
            }

            override fun stop() {
                killed++
            }
        }
}

/** An engine plus the mutable clock driving it and the temp dir it writes into. */
private class Fixture(
    val engine: TranscodeSessionEngine,
    val dir: Path,
) {
    var clock = 0L

    fun cleanUp() = deleteRecursively(dir)
}

private fun engineWith(
    fake: FakeSpawner,
    maxConcurrent: Int = 2,
): Fixture {
    val dir = Path(SystemTemporaryDirectory, "engine-${Random.nextLong().toString(16)}")
    lateinit var fixture: Fixture
    val engine =
        TranscodeSessionEngine(
            ffmpegPath = { "/usr/bin/ffmpeg" },
            cache = SegmentCache(dir),
            settings = TranscodeSettings(maxConcurrentSessions = maxConcurrent),
            newSpawner = { fake.spawner() },
            elapsedMillis = { fixture.clock },
        )
    fixture = Fixture(engine, dir)
    return fixture
}

private fun List<String>.shouldContainFlag(
    flag: String,
    value: String,
) {
    val i = indexOf(flag)
    check(i >= 0 && this[i + 1] == value) { "expected $flag $value in $this" }
}
