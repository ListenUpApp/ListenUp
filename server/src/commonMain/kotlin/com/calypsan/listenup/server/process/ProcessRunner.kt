package com.calypsan.listenup.server.process

import com.calypsan.listenup.server.io.fileIoDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** A missing/unresolvable binary is a normal outcome (the provisioner probes for one), not a crash. */
internal const val MISSING_BINARY_EXIT_CODE = 127

/**
 * Reported when the spawn itself failed — a `pipe`/`fork` syscall error, or a binary that resolved
 * but could not be executed. Deliberately outside the 0..255 range a real child can exit with, so a
 * caller can never mistake an operational failure for the child's own status. Always logged: fd or
 * process exhaustion is an operational emergency, and it must not read as "FFmpeg isn't installed".
 *
 * ⚠️ The paths that return this — a failed `pipe`, `open` or `fork`, and `ProcessBuilder`'s
 * `IOException` — are **review-verified, not test-pinned**: provoking those syscalls into failing
 * needs rlimit surgery no portable spec can do.
 */
internal const val SPAWN_FAILED_EXIT_CODE = -1

/**
 * Runs [blockingWork] off the caller's thread and returns its exit code, calling [killChild] if the
 * caller is cancelled first.
 *
 * ⛔ **`currentCoroutineContext().job.invokeOnCompletion { kill() }` cannot do this job**, which is
 * the whole reason this exists. Its single-argument form is `onCancelling = false`, so it fires only
 * once the job reaches a *final* state — and [blockingWork] is a blocking call with no suspension
 * point, so the job cannot get there until that work has already finished by itself. Measured on the
 * real thing: cancelling 100ms into a 2s body fired the handler at ~2005ms. If FFmpeg hangs, the
 * kill never happens at all.
 *
 * `await()` resumes the instant the caller is cancelled, so the kill lands mid-blocking-call, and
 * `coroutineScope` still waits for [blockingWork] to unwind — which is what makes the child reliably
 * *reaped*, not merely signalled, before the cancellation propagates.
 *
 * Lives in commonMain because both actuals need exactly this and the reasoning above is too subtle
 * to keep two copies of.
 */
internal suspend fun runKillingChildOnCancellation(
    killChild: () -> Unit,
    blockingWork: () -> Int,
): Int =
    coroutineScope {
        val child = async(fileIoDispatcher) { blockingWork() }
        try {
            child.await()
        } catch (cancellation: CancellationException) {
            killChild()
            throw cancellation
        }
    }

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

    /**
     * Runs [commands] as a pipeline — each stage's stdout becomes the next stage's stdin — and
     * returns the **first non-zero** exit code, or 0 when every stage succeeded.
     *
     * ⛔ **Pipefail semantics, deliberately.** A shell pipeline reports only its last stage, which
     * is precisely how a broken stage in the middle ships as a success. Transcoding learned this the
     * hard way: FFmpeg's xHE-AAC decoder drops about a quarter of the audio and still exits 0.
     *
     * **No shell is involved.** These are OS pipes between children this process spawns directly,
     * so the "no shell in a distroless image" rule the class docs describe still holds. The pipeline
     * exists because decoding xHE-AAC needs FFmpeg to demux and seek, Fraunhofer FDK to decode, and
     * FFmpeg again to encode and segment — and chunking those into separate runs breaks segment
     * alignment, because each chunk's re-encode adds its own priming.
     *
     * stderr from **every** stage is forwarded to [onStderr]; [kill] and cancellation terminate
     * every stage, not just one.
     */
    suspend fun runPipeline(
        commands: List<List<String>>,
        onStderr: (String) -> Unit = {},
    ): Int

    /** Suspends until the child has actually been spawned. Test seam; also used by the engine's gate. */
    suspend fun awaitStarted()

    /** Terminates the child if one is running. Idempotent, and safe to call from any thread. */
    fun kill()
}
