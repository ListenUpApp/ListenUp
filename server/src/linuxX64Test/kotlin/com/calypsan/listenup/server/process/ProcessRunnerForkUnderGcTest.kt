package com.calypsan.listenup.server.process

import io.kotest.core.spec.style.FunSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

/** Forks to attempt; with the bug, a hang arrived within the first few hundred under this pressure. */
private const val FORKS = 1_000

/** Threads allocating flat out, so a collection is in flight at the instant of many a `fork()`. */
private const val CHURNERS = 3

/**
 * A child forked while the collector was mid-cycle hung forever: its `fork()` returned into Kotlin,
 * the return hit a GC safepoint, and the child waited on a collector thread that `fork()` does not
 * copy. Its parent then waited on it — the `ProcessRunnerTest` time-outs CI kept reporting, and a
 * transcode that never finishes in production. The child now never re-enters Kotlin at all.
 */
class ProcessRunnerForkUnderGcTest :
    FunSpec({
        test("forking while the collector is busy never strands the child") {
            val churners =
                List(CHURNERS) {
                    launch(Dispatchers.Default) {
                        var garbage: List<ByteArray> = emptyList()
                        while (isActive) garbage = List(200) { ByteArray(4_096) }
                        check(garbage.isNotEmpty())
                    }
                }
            try {
                repeat(FORKS) {
                    // A healthy spawn of /bin/true takes milliseconds; a stranded child never returns.
                    withTimeout(10.seconds) { ProcessRunner().run(listOf("/bin/true"), onStderr = {}) }
                }
            } finally {
                churners.forEach { it.cancel() }
            }
        }
    })
