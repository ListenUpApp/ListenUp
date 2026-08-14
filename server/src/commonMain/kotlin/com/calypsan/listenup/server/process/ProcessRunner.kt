package com.calypsan.listenup.server.process

/**
 * Spawns one external process, streams its stderr, and can kill it.
 *
 * ⛔ **The only process primitive in this server**, and deliberately minimal: no shell, no pipes,
 * no stdin, no stdout capture. FFmpeg writes its output to files and its diagnostics to stderr, so
 * that is the entire surface transcoding needs. Anything more is a new attack surface in a
 * distroless image that deliberately has no shell.
 *
 * One instance drives one child. [kill] is safe to call before, during, or after [run].
 */
expect class ProcessRunner() {
    /**
     * Runs [command] to completion and returns its exit code, forwarding each stderr line to
     * [onStderr]. Killing the child (via [kill] or coroutine cancellation) returns its exit code
     * rather than throwing — an abandoned transcode is a normal outcome, not an error.
     */
    suspend fun run(
        command: List<String>,
        onStderr: (String) -> Unit = {},
    ): Int

    /** Suspends until the child has actually been spawned. Test seam; also used by the engine's gate. */
    suspend fun awaitStarted()

    /** Terminates the child if one is running. Idempotent. */
    fun kill()
}
