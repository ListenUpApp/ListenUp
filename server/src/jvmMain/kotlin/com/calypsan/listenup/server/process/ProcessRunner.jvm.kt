package com.calypsan.listenup.server.process

import com.calypsan.listenup.server.logging.loggerFor
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CompletableDeferred
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader

private val log = loggerFor<ProcessRunner>()

actual class ProcessRunner {
    private val started = CompletableDeferred<Unit>()
    private val lock = SynchronizedObject()

    /** Guarded by [lock] — see [kill] for why the adoption of a fresh child is a critical section. */
    private var process: Process? = null

    /** Guarded by [lock]. Records a [kill] that arrived before there was a child to kill. */
    private var killRequested = false

    // Named argument, not a trailing lambda into the last slot: both parameters are functional, and
    // naming them is what stops a future one from silently stealing the block.
    actual suspend fun run(
        command: List<String>,
        onStderr: (String) -> Unit,
    ): Int = runKillingChildOnCancellation(killChild = ::kill) { runToCompletion(command, onStderr) }

    actual suspend fun awaitStarted() {
        started.await()
    }

    actual fun kill() {
        synchronized(lock) {
            killRequested = true
            process?.destroyForcibly()
        }
    }

    private fun runToCompletion(
        command: List<String>,
        onStderr: (String) -> Unit,
    ): Int {
        // Resolved here rather than left to `start()`: an IOException from ProcessBuilder folds a
        // missing binary together with permission denied, E2BIG and fd exhaustion, and the three
        // want very different reactions. Mirrors the native actual, which resolves in the parent
        // anyway so the forked child can call the async-signal-safe `execv`.
        if (resolveExecutable(command.first()) == null) {
            started.complete(Unit)
            return MISSING_BINARY_EXIT_CODE
        }

        val process =
            try {
                ProcessBuilder(command)
                    // stdout goes nowhere rather than into a pipe nobody drains: past ~64KB an
                    // undrained pipe blocks the child forever. FFmpeg's diagnostics are on stderr,
                    // which we do read.
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectErrorStream(false)
                    .start()
            } catch (failure: IOException) {
                log.error(failure) { "Spawning ${command.first()} failed" }
                started.complete(Unit)
                return SPAWN_FAILED_EXIT_CODE
            }
        // Closing the pipe hands the child EOF on stdin. Without it an FFmpeg prompt
        // ("overwrite? [y/n]") would sit there waiting for an answer that never comes.
        process.outputStream.close()

        adopt(process)
        started.complete(Unit)

        BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
            reader.lineSequence().forEach(onStderr)
        }
        return process.waitFor()
    }

    /** Takes ownership of [spawned], honouring a [kill] that arrived while it was being started. */
    private fun adopt(spawned: Process) {
        val alreadyKilled =
            synchronized(lock) {
                process = spawned
                killRequested
            }
        if (alreadyKilled) spawned.destroyForcibly()
    }

    /**
     * Resolves [name] the way `execvp` would: a name containing a separator is taken as a path,
     * anything else is searched for on `$PATH`. `null` means nothing executable was found, which is
     * a normal answer — the provisioner asks exactly this question at boot.
     */
    private fun resolveExecutable(name: String): File? {
        if (name.contains('/') || name.contains(File.separatorChar)) {
            return File(name).takeIf { it.canExecute() }
        }
        val searchPath = System.getenv("PATH") ?: return null
        return searchPath
            .split(File.pathSeparatorChar)
            .asSequence()
            .filter { it.isNotEmpty() }
            .map { File(it, name) }
            .firstOrNull { it.canExecute() }
    }
}
