package com.calypsan.listenup.client.playback

import com.calypsan.listenup.core.BookId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How long an engine may buffer a start before the session says the book couldn't be started.
 *
 * Every platform hands the book to its own player and then waits for it to speak, and some of those waits have no
 * end of their own: an AVPlayer that never finishes a seek, a browser element that never hears back from the server.
 * This is the one bound over all of them. It sits above the longest wait any platform's start path already bounds,
 * so a platform that can say why a book failed always says it first: 30 s is that longest wait (a socket connect or
 * read on Android, iOS's 20 s readiness wait plus its 10 s resume seek, hls.js's retries on the web), and 15 s more
 * covers the audio session, the queue and the first bytes after it. The prepare itself is not counted: it is bounded
 * by its own RPC timeout before the engine is handed anything.
 */
internal val ENGINE_START_BOUND: Duration = 45.seconds

/**
 * Report each start that is still buffering [within] after it began, through [onNeverStarted]. A start is the engine
 * buffering a book it was not just playing: a load, a retry, a fresh prepare, a play from a pause. A stall that
 * interrupts audio already moving is not a start; the engine's own recovery and the drop grace own it.
 */
internal suspend fun reportStartsThatNeverSettle(
    state: Flow<PlayerSessionState>,
    within: Duration = ENGINE_START_BOUND,
    onNeverStarted: (BookId) -> Unit,
) {
    var before: PlayerPhase? = null
    state
        .map { (it as? PlayerSessionState.Loaded)?.takeIf { loaded -> loaded.isEngineBound } }
        .distinctUntilChanged()
        .collectLatest { engineBound ->
            val phaseBefore = before
            before = engineBound?.phase
            if (engineBound?.phase != PlayerPhase.Buffering || phaseBefore.isListening()) return@collectLatest
            delay(within)
            onNeverStarted(engineBound.bookId)
        }
}

/** Audio was moving, or a drop that interrupted it is being recovered: a stall after this is not a start. */
private fun PlayerPhase?.isListening(): Boolean =
    this == PlayerPhase.Playing || this == PlayerPhase.SleepFading || this == PlayerPhase.Reconnecting
