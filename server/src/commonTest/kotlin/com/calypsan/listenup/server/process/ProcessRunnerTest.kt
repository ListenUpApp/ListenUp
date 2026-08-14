package com.calypsan.listenup.server.process

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

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

        test("a nonexistent binary returns a nonzero code rather than hanging or throwing") {
            val runner = ProcessRunner()
            val code =
                withTimeoutOrNull(5.seconds) {
                    runner.run(listOf("/no/such/binary-listenup-test"))
                }
            code.shouldNotBeNull()
            (code != 0) shouldBe true
        }
    })
