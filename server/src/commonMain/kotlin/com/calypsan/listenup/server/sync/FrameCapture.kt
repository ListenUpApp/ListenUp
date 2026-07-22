package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.sync.SyncFrame
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
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
