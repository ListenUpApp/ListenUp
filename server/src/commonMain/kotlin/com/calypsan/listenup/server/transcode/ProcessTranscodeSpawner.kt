package com.calypsan.listenup.server.transcode

import com.calypsan.listenup.server.process.ProcessRunner
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile

private val log = KotlinLogging.logger("com.calypsan.listenup.server.transcode.ProcessTranscodeSpawner")

/**
 * The real [TranscodeSpawner]: one FFmpeg child, run on [scope] so the caller is not held for the
 * length of the encode.
 *
 * [start] returns as soon as the child exists rather than when it exits — a segment request must be
 * answerable while the encoder is still working, which is the entire point of chasing the playhead.
 * `awaitStarted()` also completes when the spawn *fails*, so a missing or unexecutable binary
 * surfaces as "no segments ever appear" and the route's wait times out, rather than hanging a
 * request forever.
 *
 * One instance owns exactly one child. The engine makes a fresh spawner per encoder run, so
 * [stop] can never reach a process belonging to a later run.
 */
class ProcessTranscodeSpawner(
    private val scope: CoroutineScope,
    private val runner: ProcessRunner = ProcessRunner(),
) : TranscodeSpawner {
    @Volatile
    private var exited = false

    override val hasExited: Boolean get() = exited

    private val tailLock = SynchronizedObject()
    private val tail = ArrayDeque<String>(STDERR_TAIL_LINES)

    override val stderrTail: List<String> get() = synchronized(tailLock) { tail.toList() }

    override suspend fun start(commands: List<List<String>>) {
        scope.launch {
            try {
                // Every line goes to debug; only the last few are kept, because only they explain a
                // segment that came out wrong — and the engine hands them to whoever reports one.
                // Every stage writes here, possibly from its own reader thread, hence the lock.
                val exit = runner.runPipeline(commands) { line -> remember(line) }
                if (exit != 0) {
                    log.warn {
                        "transcode pipeline exited $exit: ${commands.map { it.firstOrNull() }}; stderr tail: $stderrTail"
                    }
                }
            } finally {
                // A failed spawn and a cancelled scope are exits too: the engine must never wait on a
                // child that is not there.
                exited = true
            }
        }
        runner.awaitStarted()
    }

    override fun stop() {
        runner.kill()
    }

    private fun remember(line: String) {
        log.debug { line }
        synchronized(tailLock) {
            if (tail.size == STDERR_TAIL_LINES) tail.removeFirst()
            tail.addLast(line)
        }
    }

    internal companion object {
        /** Enough for FFmpeg's or GStreamer's closing complaint, not a log's worth of progress. */
        const val STDERR_TAIL_LINES = 20
    }
}
