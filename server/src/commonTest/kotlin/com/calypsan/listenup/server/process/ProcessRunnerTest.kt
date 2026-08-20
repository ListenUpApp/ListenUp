package com.calypsan.listenup.server.process

import com.calypsan.listenup.server.io.readText
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

private const val FLOOD_LINES = 2000

/**
 * Budget for the pipeline lifecycle tests.
 *
 * Deliberately generous, and not a papered-over flake: these assert that a kill or a cancellation
 * *does* end the call, not that it does so within a particular wall-clock slice. The work is
 * scheduled onto `fileIoDispatcher` (`Dispatchers.IO`), which the full server suite saturates with
 * concurrent `testApplication` instances — so a tight budget measures the scheduler's queue depth
 * rather than the thing under test. It failed exactly that way on CI at 10s while passing in
 * isolation, including pinned to two cores.
 */
private val PIPELINE_LIFECYCLE_BUDGET = 45.seconds

/**
 * A pipeline stage that deliberately keeps its work in a *grandchild*: the shell forks `sleep` and
 * waits on it rather than exec'ing it, so the process the runner knows about is not the process
 * holding the pipe.
 *
 * ⛔ Not a contrived shape — this is what CI was running all along. `/bin/sh` is dash on Debian and
 * Ubuntu, and dash FORKS for `sh -c "cmd"` where bash exec's it, so every stage on the Linux runner
 * already had a grandchild while every stage on a bash developer box had none. That is why these
 * two specs passed locally and hung for the full 60s on CI: killing only the direct child leaves
 * the grandchild holding the shared stderr pipe open, so the reader never sees EOF and the call
 * that the kill was supposed to end waits for the sleep to finish by itself.
 *
 * Writing the fork explicitly turns a race against the shell's implementation into a fixed
 * property, and pins the actual promise: when these calls return, the whole tree is dead.
 *
 * ⛔ The marker on stderr is what makes it deterministic, and it is not optional. `awaitStarted()`
 * resolves as soon as the stage has been *forked* — which can be before the shell has even exec'd,
 * let alone forked its own child. Killing there beats the grandchild into existence and the spec
 * passes without proving anything, which is exactly how this hole reached CI: on a bash box the
 * kill won that race every time, and on the Linux runner it lost it. Waiting for one marker per
 * stage means the grandchild demonstrably exists before anything tries to kill it.
 */
private const val FORK_MARKER = "forked"

private val FORKING_STAGE = listOf("/bin/sh", "-c", "sleep 60 & echo $FORK_MARKER >&2; wait")

/** Reports the child's own open descriptors, one per line, without needing `ls` to exist. */
private const val LIST_OWN_FDS = "for f in /proc/self/fd/*; do echo \$f 1>&2; done"

/** Writes [FLOOD_LINES] × 51 bytes ≈ 102KB — comfortably past a 64KB pipe buffer. */
private val FLOOD_SCRIPT =
    "i=0; while [ \$i -lt $FLOOD_LINES ]; do printf '%050d\\n' \$i; i=\$((i+1)); done"

/**
 * Runs against real `/bin/sh` and `sleep` — this is the one primitive that can spawn a process at
 * all, so a fake would prove nothing about whether it actually works on either target. Runs on both
 * the JVM and linuxX64 lanes (commonTest); both must pass since production ships the native binary.
 */
class ProcessRunnerTest :
    FunSpec({
        test("run returns the child's own exit code") {
            val runner = ProcessRunner()
            val code = runner.run(listOf("/bin/sh", "-c", "exit 7"))
            code shouldBe 7
        }

        test("stderr lines reach the onStderr sink") {
            val runner = ProcessRunner()
            val lines = mutableListOf<String>()
            runner.run(listOf("/bin/sh", "-c", "echo boom 1>&2")) { lines += it }
            lines.joinToString("\n") shouldContain "boom"
        }

        test("kill terminates a running child so run() returns") {
            val runner = ProcessRunner()
            val result =
                withTimeoutOrNull(5.seconds) {
                    coroutineScope {
                        val running = async { runner.run(listOf("sleep", "30")) }
                        runner.awaitStarted()
                        runner.kill()
                        running.await()
                    }
                }
            result.shouldNotBeNull()
        }

        // The regression test for the finding that `invokeOnCompletion { kill() }` never fired:
        // the child outlives the caller by 30 seconds unless cancellation reaches it. Calling
        // kill() directly — as the test above does — cannot catch that, because it never exercises
        // the cancellation path at all.
        //
        // Two things are asserted, and both matter. That `cancelAndJoin` completes at all proves
        // promptness: with the bug it waits out the full `sleep 30` and the timeout returns null.
        // That `/proc/<pid>` is gone proves the child was actually killed and reaped, not merely
        // abandoned by a caller that stopped waiting.
        test("cancelling run() kills the child rather than leaving it running") {
            val runner = ProcessRunner()
            val childPid = CompletableDeferred<String>()

            val settled =
                withTimeoutOrNull(10.seconds) {
                    coroutineScope {
                        // `exec` replaces the shell in place, so the pid it reports IS the sleeping
                        // process — no intermediate shell to confuse the check below.
                        val job =
                            launch {
                                runner.run(listOf("/bin/sh", "-c", "echo \$\$ 1>&2; exec sleep 30")) {
                                    childPid.complete(it.trim())
                                }
                            }
                        childPid.await()
                        job.cancelAndJoin()
                        true
                    }
                }
            settled.shouldNotBeNull()

            // /proc is Linux-only; the native target has nothing else, and a JVM lane on another
            // OS simply does not get to make this assertion.
            if (SystemFileSystem.exists(Path("/proc/self"))) {
                SystemFileSystem.exists(Path("/proc/${childPid.await()}")) shouldBe false
            }
        }

        test("a nonexistent binary returns a nonzero code rather than hanging or throwing") {
            val runner = ProcessRunner()
            val code =
                withTimeoutOrNull(5.seconds) {
                    runner.run(listOf("/no/such/binary-listenup-test"))
                }
            code.shouldNotBeNull()
            (code != 0) shouldBe true
        }

        // FFmpeg emits long lines; the native reader assembles them from 4096-byte reads, so a line
        // that spans a read boundary is the case that would arrive in halves.
        test("a stderr line longer than the read buffer arrives in one piece") {
            val runner = ProcessRunner()
            val lines = mutableListOf<String>()
            val code =
                withTimeoutOrNull(10.seconds) {
                    runner.run(listOf("/bin/sh", "-c", "printf '%05000d\\n' 0 1>&2")) { lines += it }
                }
            code shouldBe 0
            lines shouldHaveSize 1
            lines.first().length shouldBe 5000
        }

        test("a flood of stderr is delivered whole rather than stalling the child") {
            val runner = ProcessRunner()
            val lines = mutableListOf<String>()
            val code =
                withTimeoutOrNull(30.seconds) {
                    runner.run(listOf("/bin/sh", "-c", FLOOD_SCRIPT + " 1>&2")) { lines += it }
                }
            code shouldBe 0
            lines shouldHaveSize FLOOD_LINES
        }

        // stdout is discarded rather than piped: a pipe nobody drains blocks the writer for good
        // once the ~64KB buffer fills, and the child would hang holding a transcode open.
        // ⚠️ This pins the JVM fix (mutation-verified). Native never piped stdout — it inherited
        // fd 1 — so on that lane it asserts an invariant rather than catching the old bug.
        test("a child that floods stdout is not blocked by an undrained pipe") {
            val runner = ProcessRunner()
            val code =
                withTimeoutOrNull(30.seconds) {
                    runner.run(listOf("/bin/sh", "-c", "$FLOOD_SCRIPT; exit 4"))
                }
            code shouldBe 4
        }

        // The child reads /dev/null (native) or a closed pipe (JVM), never the server's own stdin —
        // an ffmpeg prompt must not be able to consume it, and must not wait forever for an answer.
        // ⚠️ Mutation-verified on JVM. On native its strength depends on what stdin the test binary
        // itself inherits under Gradle: if that is already empty, the test passes either way there.
        test("a child reading stdin sees EOF rather than the server's input") {
            val runner = ProcessRunner()
            val code =
                withTimeoutOrNull(10.seconds) {
                    runner.run(listOf("/bin/sh", "-c", "cat; exit 3"))
                }
            code shouldBe 3
        }

        // `fork()` duplicates the whole descriptor table, and nothing in this server sets
        // FD_CLOEXEC — so without the child's sweep, ffmpeg is handed the listening socket, every
        // live client connection and the SQLite handle.
        test("the child inherits none of the server's descriptors") {
            // /proc/self/fd is the only portable way to ask a child what it is holding, and it is
            // Linux-only; the native target has nothing else, and a JVM lane on another OS does not
            // get to make this assertion.
            if (SystemFileSystem.exists(Path("/proc/self"))) {
                // Hold files open so there is something to leak in the first place.
                val held = List(5) { SystemFileSystem.source(Path("/dev/zero")) }
                try {
                    val runner = ProcessRunner()
                    val lines = mutableListOf<String>()
                    val code =
                        withTimeoutOrNull(10.seconds) {
                            runner.run(listOf("/bin/sh", "-c", LIST_OWN_FDS)) { lines += it }
                        }
                    code shouldBe 0
                    // 0, 1 and 2, plus the descriptor the shell's own glob holds while it reads the
                    // directory. Anything beyond that came from us.
                    lines.size shouldBeLessThanOrEqual 5
                } finally {
                    held.forEach { it.close() }
                }
            }
        }

        // ⚠️ A smoke test, NOT a regression pin for the pid-reuse fix — it would pass against the
        // unlocked version too. The child here is nowhere near being reaped, and the real race
        // (kill concurrent with `waitpid`, microseconds wide) cannot be provoked deterministically;
        // that fix is argued structurally in `kill`/`reap` instead of tested.
        // ── Pipelines ───────────────────────────────────────────────────────────────────────
        // Transcoding xHE-AAC needs three processes chained together (FFmpeg demuxes and seeks,
        // GStreamer decodes with Fraunhofer FDK, FFmpeg encodes and segments) because FFmpeg's own
        // USAC decoder silently drops about a quarter of the audio. No shell is involved: these are
        // OS pipes between children this process spawns directly.

        test("a two-stage pipeline feeds the first stage's stdout into the second's stdin") {
            val sink = tempPath("pipeline-through")
            val code =
                ProcessRunner().runPipeline(
                    listOf(
                        listOf("/bin/sh", "-c", "echo hello-from-stage-one"),
                        listOf("/bin/sh", "-c", "cat > $sink"),
                    ),
                )

            code shouldBe 0
            sink.readText().trim() shouldBe "hello-from-stage-one"
            SystemFileSystem.delete(sink, mustExist = false)
        }

        test("a three-stage pipeline carries data end to end") {
            val sink = tempPath("pipeline-three")
            val code =
                ProcessRunner().runPipeline(
                    listOf(
                        listOf("/bin/sh", "-c", "echo abc"),
                        listOf("/bin/sh", "-c", "tr a-z A-Z"),
                        listOf("/bin/sh", "-c", "cat > $sink"),
                    ),
                )

            code shouldBe 0
            sink.readText().trim() shouldBe "ABC"
            SystemFileSystem.delete(sink, mustExist = false)
        }

        // ⛔ Pipefail, deliberately. A shell pipeline reports only its LAST stage, which is exactly
        // how a broken decoder in the middle would ship silently as a success.
        test("a failing stage is reported even when the last stage succeeds") {
            val code =
                ProcessRunner().runPipeline(
                    listOf(
                        listOf("/bin/sh", "-c", "exit 3"),
                        listOf("/bin/sh", "-c", "cat > /dev/null"),
                    ),
                )

            code shouldBe 3
        }

        test("stderr from every stage reaches the sink") {
            val lines = mutableListOf<String>()
            ProcessRunner().runPipeline(
                listOf(
                    listOf("/bin/sh", "-c", "echo from-stage-one 1>&2"),
                    listOf("/bin/sh", "-c", "echo from-stage-two 1>&2; cat > /dev/null"),
                ),
            ) { lines += it }

            val all = lines.joinToString("\n")
            all shouldContain "from-stage-one"
            all shouldContain "from-stage-two"
        }

        test("a pipeline of one behaves exactly like a plain run") {
            ProcessRunner().runPipeline(listOf(listOf("/bin/sh", "-c", "exit 9"))) shouldBe 9
        }

        test("kill terminates every stage of a pipeline") {
            val runner = ProcessRunner()
            val forked = Channel<Unit>(Channel.UNLIMITED)
            val result =
                withTimeoutOrNull(PIPELINE_LIFECYCLE_BUDGET) {
                    coroutineScope {
                        val run =
                            async {
                                runner.runPipeline(listOf(FORKING_STAGE, FORKING_STAGE)) { line ->
                                    if (line == FORK_MARKER) forked.trySend(Unit)
                                }
                            }
                        runner.awaitStarted()
                        repeat(2) { forked.receive() }
                        runner.kill()
                        run.await()
                    }
                }

            result.shouldNotBeNull()
        }

        test("cancelling a pipeline kills every stage rather than leaving them running") {
            val runner = ProcessRunner()
            val forked = Channel<Unit>(Channel.UNLIMITED)
            val survived =
                withTimeoutOrNull(PIPELINE_LIFECYCLE_BUDGET) {
                    coroutineScope {
                        val run =
                            launch {
                                runner.runPipeline(listOf(FORKING_STAGE, FORKING_STAGE)) { line ->
                                    if (line == FORK_MARKER) forked.trySend(Unit)
                                }
                            }
                        runner.awaitStarted()
                        repeat(2) { forked.receive() }
                        run.cancelAndJoin()
                        true
                    }
                }

            survived.shouldNotBeNull()
        }

        test("kill from several coroutines at once is safe") {
            val runner = ProcessRunner()
            val result =
                withTimeoutOrNull(10.seconds) {
                    coroutineScope {
                        val running = async { runner.run(listOf("sleep", "30")) }
                        runner.awaitStarted()
                        repeat(8) { launch { runner.kill() } }
                        running.await()
                    }
                }
            result.shouldNotBeNull()
        }
    })

/** A unique throwaway path for a test that needs a stage to write somewhere observable. */
private fun tempPath(prefix: String): Path = Path(SystemTemporaryDirectory, "$prefix-${Random.nextLong().toString(16)}")
