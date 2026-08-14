package com.calypsan.listenup.server.process

import com.calypsan.listenup.server.io.fileIoDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader

/** A missing/unresolvable binary is a normal outcome (the provisioner probes for one), not a crash. */
private const val MISSING_BINARY_EXIT_CODE = 127

actual class ProcessRunner {
    private val started = CompletableDeferred<Unit>()

    @Volatile
    private var process: Process? = null

    actual suspend fun run(
        command: List<String>,
        onStderr: (String) -> Unit,
    ): Int =
        withContext(fileIoDispatcher) {
            // If the surrounding coroutine is cancelled while the child is still running, kill it —
            // an abandoned transcode must not leak a running ffmpeg process.
            currentCoroutineContext().job.invokeOnCompletion { kill() }

            val proc =
                try {
                    ProcessBuilder(command).redirectErrorStream(false).start()
                } catch (expected: IOException) {
                    // A missing/unresolvable binary is a normal outcome here, not an error to log.
                    started.complete(Unit)
                    return@withContext MISSING_BINARY_EXIT_CODE
                }
            process = proc
            started.complete(Unit)

            BufferedReader(InputStreamReader(proc.errorStream)).use { reader ->
                reader.lineSequence().forEach(onStderr)
            }
            proc.waitFor()
        }

    actual suspend fun awaitStarted() {
        started.await()
    }

    actual fun kill() {
        process?.destroyForcibly()
    }
}
