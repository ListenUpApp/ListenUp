package com.calypsan.listenup.server.process

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.time.Duration.Companion.seconds

private const val FLOOD_LINES = 2000

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
