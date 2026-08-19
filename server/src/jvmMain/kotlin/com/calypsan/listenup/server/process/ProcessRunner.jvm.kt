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

/** How long to wait for a stage's stderr to finish draining once every child has exited. */
private const val READER_DRAIN_MILLIS = 2_000L

actual class ProcessRunner {
    private val started = CompletableDeferred<Unit>()
    private val lock = SynchronizedObject()

    /** Guarded by [lock] — see [kill] for why the adoption of fresh children is a critical section. */
    private var processes: List<Process> = emptyList()

    /** Guarded by [lock]. Records a [kill] that arrived before there was a child to kill. */
    private var killRequested = false

    // Named argument, not a trailing lambda into the last slot: both parameters are functional, and
    // naming them is what stops a future one from silently stealing the block.
    actual suspend fun run(
        command: List<String>,
        onStderr: (String) -> Unit,
    ): Int = runKillingChildOnCancellation(killChild = ::kill) { runToCompletion(command, onStderr) }

    actual suspend fun runPipeline(
        commands: List<List<String>>,
        onStderr: (String) -> Unit,
    ): Int = runKillingChildOnCancellation(killChild = ::kill) { pipelineToCompletion(commands, onStderr) }

    actual suspend fun awaitStarted() {
        started.await()
    }

    actual fun kill() {
        synchronized(lock) {
            killRequested = true
            // ⛔ Descendants first. `destroyForcibly()` reaches only the direct child, so a shell
            // that forked rather than exec'd leaves a grandchild holding the stderr pipe open — and
            // the reader threads would then never see EOF, hanging the call that was supposed to be
            // ended by this kill.
            processes.forEach { stage ->
                stage.descendants().forEach { descendant -> descendant.destroyForcibly() }
                stage.destroyForcibly()
            }
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

        adopt(listOf(process))
        started.complete(Unit)

        BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
            reader.lineSequence().forEach(onStderr)
        }
        return process.waitFor()
    }

    private fun pipelineToCompletion(
        commands: List<List<String>>,
        onStderr: (String) -> Unit,
    ): Int {
        require(commands.isNotEmpty()) { "a pipeline needs at least one stage" }
        // Every stage is resolved before any is spawned: a pipeline that starts and then discovers
        // its third binary is missing has already begun writing, which is worse than not starting.
        if (commands.any { resolveExecutable(it.first()) == null }) {
            started.complete(Unit)
            return MISSING_BINARY_EXIT_CODE
        }

        val builders = commands.map { ProcessBuilder(it).redirectErrorStream(false) }
        // Only the LAST stage's stdout is discarded; the others are the pipes startPipeline wires up.
        builders.last().redirectOutput(ProcessBuilder.Redirect.DISCARD)
        val spawned =
            try {
                ProcessBuilder.startPipeline(builders)
            } catch (failure: IOException) {
                log.error(failure) { "Spawning the pipeline ${commands.map { it.first() }} failed" }
                started.complete(Unit)
                return SPAWN_FAILED_EXIT_CODE
            }
        spawned.first().outputStream.close()
        adopt(spawned)
        started.complete(Unit)

        // One reader per stage: a stage whose stderr nobody drains blocks as soon as its pipe fills,
        // which would deadlock the whole chain. onStderr is serialized because these are real threads.
        val emitLock = SynchronizedObject()
        val readers =
            spawned.map { stage ->
                Thread {
                    BufferedReader(InputStreamReader(stage.errorStream)).use { reader ->
                        reader.lineSequence().forEach { line -> synchronized(emitLock) { onStderr(line) } }
                    }
                }.apply {
                    isDaemon = true
                    start()
                }
            }
        // Exit first, then drain. The readers have been running since before this line, so waiting
        // here cannot deadlock on a full stderr pipe; and joining them only AFTER every child is
        // gone means a stray descendant holding a pipe open costs a bounded wait instead of
        // hanging forever. They are daemon threads, so abandoning one is safe.
        val codes = spawned.map { it.waitFor() }
        readers.forEach { it.join(READER_DRAIN_MILLIS) }
        return codes.firstOrNull { it != 0 } ?: 0
    }

    /** Takes ownership of [spawned], honouring a [kill] that arrived while it was being started. */
    private fun adopt(spawned: List<Process>) {
        val alreadyKilled =
            synchronized(lock) {
                processes = spawned
                killRequested
            }
        if (alreadyKilled) spawned.forEach { it.destroyForcibly() }
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
