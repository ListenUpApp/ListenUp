package com.calypsan.listenup.server.scanner.watcher

import com.calypsan.listenup.server.io.statFile
import kotlinx.coroutines.delay
import kotlinx.io.files.Path
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Uniform "done writing" detector across Linux/macOS/Windows.
 *
 * The recursive watcher translates platform events into a uniform
 * Create/Modify/Delete model, but the *semantics* of those events differ:
 *
 *  - **Linux** (inotify): `IN_CLOSE_WRITE` fires once after the writer's
 *    fd is closed — already a "done writing" signal.
 *  - **macOS** (FSEvents) / **Windows** (ReadDirectoryChangesW): events
 *    fire mid-write; consumers must poll for the file to settle.
 *
 * Rather than special-case per OS, we layer this debouncer on every
 * platform: the file is "stable" when its size and mtime don't change
 * for [settle] consecutive duration. A 2 s settle with 500 ms polling is
 * the default — fast enough for human latency, slow enough to handle
 * an SMB share's chattier events.
 *
 * Returns `false` if the file is deleted (or moved away) during the wait,
 * so callers can treat the await as a signal: stable-and-present (true)
 * or gone (false). Either outcome lets the watcher decide whether to
 * trigger an analyze or a removal.
 *
 * The [SettleWindow] state machine is the testable core; [awaitStable] is
 * the I/O-driven wrapper. Size + mtime come from [statFile] (kotlinx-io's
 * `FileMetadata` exposes neither mtime).
 */
internal class StableSizeDebouncer(
    private val settle: Duration = 2.seconds,
    private val poll: Duration = 500.milliseconds,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    suspend fun awaitStable(path: Path): Boolean {
        var window = SettleWindow.initial()
        while (true) {
            val attrs = statFile(path) ?: return false
            val tick =
                window.tick(
                    size = attrs.size,
                    mtime = attrs.mtimeMs,
                    now = clock(),
                    settleMs = settle.inWholeMilliseconds,
                )
            when (tick) {
                is TickResult.Stable -> return true
                is TickResult.Continue -> window = tick.next
            }
            delay(poll)
        }
    }
}

/**
 * Pure state for the settle-window check. One [tick] per observation;
 * each tick produces either a continuation state (with updated bookkeeping)
 * or a [TickResult.Stable] signal when the file has been unchanged for
 * the configured window.
 */
internal data class SettleWindow(
    val lastSize: Long,
    val lastMtime: Long,
    val stableSince: Long,
) {
    fun tick(
        size: Long,
        mtime: Long,
        now: Long,
        settleMs: Long,
    ): TickResult {
        if (lastSize == UNINITIALIZED) {
            // First observation — capture, but we have nothing to compare yet.
            return TickResult.Continue(SettleWindow(size, mtime, now))
        }
        if (size != lastSize || mtime != lastMtime) {
            // Changed — reset the settle window to "now."
            return TickResult.Continue(SettleWindow(size, mtime, now))
        }
        // Unchanged — has the settle window elapsed?
        return if (now - stableSince >= settleMs) {
            TickResult.Stable
        } else {
            TickResult.Continue(this)
        }
    }

    companion object {
        const val UNINITIALIZED: Long = -1

        fun initial(): SettleWindow = SettleWindow(UNINITIALIZED, UNINITIALIZED, 0)
    }
}

internal sealed interface TickResult {
    /** The file is still settling; [next] carries the updated window for the following observation. */
    data class Continue(
        val next: SettleWindow,
    ) : TickResult

    /** The file has been unchanged for the full settle window — it is done writing. */
    data object Stable : TickResult
}
