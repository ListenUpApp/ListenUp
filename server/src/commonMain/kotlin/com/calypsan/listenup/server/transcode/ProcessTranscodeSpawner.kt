package com.calypsan.listenup.server.transcode

import com.calypsan.listenup.server.process.ProcessRunner
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

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
    override suspend fun start(commands: List<List<String>>) {
        scope.launch {
            // FFmpeg writes progress and warnings to stderr; only the tail matters, and only when
            // something went wrong, so it goes to debug rather than being accumulated in memory.
            val exit = runner.runPipeline(commands) { line -> log.debug { line } }
            if (exit != 0) log.warn { "transcode pipeline exited $exit: ${commands.map { it.firstOrNull() }}" }
        }
        runner.awaitStarted()
    }

    override fun stop() = runner.kill()
}
