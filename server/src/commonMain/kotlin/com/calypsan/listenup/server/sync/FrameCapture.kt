package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.map
import com.calypsan.listenup.api.sync.Mutated
import com.calypsan.listenup.api.sync.SyncFrame
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Coroutine-context collector that captures the [SyncFrame] of every syncable write performed while
 * it is in scope — the server half of echo-in-response's automatic path.
 *
 * A mutation wrapped in `withContext(FrameCapture()) { ... }` accumulates one frame per committed
 * [SqlSyncableRepository] write it triggers, however deep the fan-out: every helper reaches the DB
 * through the same [SqlSyncableRepository.upsertReturningEvent], which appends here. The frames are
 * the SAME ones the firehose broadcasts — projected via [SyncableRepo.toSyncFrame], never
 * re-derived — so a mutation can hand the originating device its own change back for read-your-writes
 * with no per-operation frame plumbing.
 *
 * Mirror of [FirehoseSuppressed]: both are coroutine-context markers the suspend write path reads in
 * its own scope. A [FirehoseSuppressed] write appends nothing here, exactly as it publishes nothing
 * to the firehose — capture and live-tail emission stay in lockstep.
 */
internal class FrameCapture : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<FrameCapture>

    private val lock = SynchronizedObject()
    private val frames = mutableListOf<SyncFrame>()

    /** Append [frame] to the captured set. Called by the write path; thread-safe. */
    fun add(frame: SyncFrame): Unit = synchronized(lock) { frames += frame }

    /** A snapshot of the frames captured so far, in write (commit) order. */
    fun collected(): List<SyncFrame> = synchronized(lock) { frames.toList() }
}

/**
 * Runs [block] inside a fresh [FrameCapture] scope and folds every sync frame its writes produced
 * into a [Mutated] envelope alongside the block's value — the one-line server seam that makes a
 * mutation read-your-writes.
 *
 * On success the returned [Mutated] carries the value plus the frames of every committed
 * [SqlSyncableRepository] write [block] performed, however deep the fan-out. A [AppResult.Failure]
 * passes through untouched (no envelope): any writes that DID commit before the failure still reach
 * the originating device via the firehose, so nothing is lost — the response simply carries no
 * read-your-writes optimisation on the failure path.
 */
internal suspend fun <T> withCapturedFrames(block: suspend () -> AppResult<T>): AppResult<Mutated<T>> {
    val capture = FrameCapture()
    return withContext(capture) { block() }.map { Mutated(it, capture.collected()) }
}
