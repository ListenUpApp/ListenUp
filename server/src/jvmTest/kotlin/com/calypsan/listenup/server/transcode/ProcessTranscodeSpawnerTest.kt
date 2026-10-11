package com.calypsan.listenup.server.transcode

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

/** The engine trusts [ProcessTranscodeSpawner.hasExited] to replace a dead encoder, so it must flip on a real exit. */
class ProcessTranscodeSpawnerTest :
    FunSpec({

        test("a pipeline that exits is reported as exited") {
            withSpawner { spawner ->
                spawner.start(listOf(listOf("/bin/sh", "-c", "exit 3")))

                spawner.awaitExit()

                spawner.hasExited shouldBe true
            }
        }

        test("a pipeline still running is not reported as exited") {
            withSpawner { spawner ->
                spawner.start(listOf(listOf("/bin/sh", "-c", "sleep 30")))

                spawner.hasExited shouldBe false

                spawner.stop()
                spawner.awaitExit()
                spawner.hasExited shouldBe true
            }
        }
    })

private suspend fun withSpawner(block: suspend (ProcessTranscodeSpawner) -> Unit) {
    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    try {
        block(ProcessTranscodeSpawner(scope))
    } finally {
        scope.cancel()
    }
}

private suspend fun ProcessTranscodeSpawner.awaitExit() =
    withTimeout(10.seconds) {
        while (!hasExited) delay(EXIT_POLL_MILLIS)
    }

private const val EXIT_POLL_MILLIS = 20L
