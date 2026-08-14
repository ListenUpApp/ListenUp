package com.calypsan.listenup.server.process

/** A missing/unresolvable binary is a normal outcome (the provisioner probes for one), not a crash. */
internal const val MISSING_BINARY_EXIT_CODE = 127

/**
 * Reported when the spawn itself failed — a `pipe`/`fork` syscall error, or a binary that resolved
 * but could not be executed. Deliberately outside the 0..255 range a real child can exit with, so a
 * caller can never mistake an operational failure for the child's own status. Always logged: fd or
 * process exhaustion is an operational emergency, and it must not read as "FFmpeg isn't installed".
 */
internal const val SPAWN_FAILED_EXIT_CODE = -1

/**
 * Spawns one external process, streams its stderr, and can kill it.
 *
 * ⛔ **The only process primitive in this server**, and deliberately minimal: no shell, no pipes,
 * no stdin, no stdout capture. FFmpeg writes its output to files and its diagnostics to stderr, so
 * that is the entire surface transcoding needs. Anything more is a new attack surface in a
 * distroless image that deliberately has no shell.
 *
 * The child starts with stdin at EOF and stdout discarded, and inherits **no** descriptor the
 * server holds — not the listening socket, not a client connection, not the SQLite handle.
 *
 * One instance drives one child. [kill] is safe to call before, during, or after [run].
 */
expect class ProcessRunner() {
    /**
     * Runs [command] to completion and returns its exit code, forwarding each stderr line to
     * [onStderr].
     *
     * [kill] is a normal outcome rather than an error: the call returns the child's signalled exit
     * code (`128 + SIGKILL`), because an abandoned transcode is an ordinary thing to happen.
     *
     * **Coroutine cancellation behaves differently, and deliberately so.** It kills the child, waits
     * for it to be reaped, and then rethrows `CancellationException` — a cancelled caller is not
     * waiting for an answer, and swallowing the cancellation would lie to the coroutine machinery.
     * What both paths guarantee is the part that matters: when this function returns *or* throws,
     * the child is dead. A listener who walks away never leaves an FFmpeg running behind them.
     */
    suspend fun run(
        command: List<String>,
        onStderr: (String) -> Unit = {},
    ): Int

    /** Suspends until the child has actually been spawned. Test seam; also used by the engine's gate. */
    suspend fun awaitStarted()

    /** Terminates the child if one is running. Idempotent, and safe to call from any thread. */
    fun kill()
}
