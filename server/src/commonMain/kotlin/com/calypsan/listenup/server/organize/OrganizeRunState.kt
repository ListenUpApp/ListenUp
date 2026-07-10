package com.calypsan.listenup.server.organize

import com.calypsan.listenup.api.dto.organize.OrganizeRunEvent
import com.calypsan.listenup.api.dto.organize.OrganizeRunId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.Uuid

/** Upper bound on replayed events per run — covers a full-library run's per-book events in memory. */
private const val EVENT_REPLAY_CAPACITY = 16_384

/**
 * In-memory status of the (at most one) organize run in flight. Holds the run's full event
 * history as a replaying [MutableSharedFlow], so an observer attaching mid-run — or right after
 * completion — still sees the whole story including the terminal
 * [OrganizeRunEvent.Completed] report. State is process-local by design: interrupted *file*
 * moves are recovered by the write journal at boot, and a lost progress view costs nothing
 * (a fresh `saveAndExecute` re-plans against current reality).
 */
class OrganizeRunState {
    private val mutex = Mutex()
    private var runId: OrganizeRunId? = null
    private var events: MutableSharedFlow<OrganizeRunEvent>? = null
    private var terminal = false

    /**
     * Registers a new run and returns its id, or `null` when a run is already in flight —
     * the caller must surface that as a typed failure rather than start a second concurrent run.
     */
    suspend fun begin(): OrganizeRunId? =
        mutex.withLock {
            if (runId != null && !terminal) return@withLock null
            val id = OrganizeRunId(Uuid.random().toString())
            runId = id
            events = MutableSharedFlow(replay = EVENT_REPLAY_CAPACITY)
            terminal = false
            id
        }

    /** Appends [event] to [id]'s history; marks the run finished when it's the terminal [OrganizeRunEvent.Completed]. */
    suspend fun emit(
        id: OrganizeRunId,
        event: OrganizeRunEvent,
    ) {
        val flow = mutex.withLock { if (runId == id) events else null } ?: return
        flow.emit(event)
        if (event is OrganizeRunEvent.Completed) {
            mutex.withLock { if (runId == id) terminal = true }
        }
    }

    /**
     * [id]'s events from the start of the run, completing after the terminal
     * [OrganizeRunEvent.Completed]. An unknown/superseded id yields an empty flow.
     */
    suspend fun eventsFor(id: OrganizeRunId): Flow<OrganizeRunEvent> {
        val flow = mutex.withLock { if (runId == id) events else null } ?: return emptyFlow()
        // transformWhile (not takeWhile): the terminal event itself must be emitted, and the
        // hot SharedFlow never produces anything after it — a takeWhile would hang forever
        // waiting for the first non-matching element.
        return flow.transformWhile { event ->
            emit(event)
            event !is OrganizeRunEvent.Completed
        }
    }

    /** The in-flight run's id, or `null` when idle / the last run already completed. */
    suspend fun activeRunId(): OrganizeRunId? = mutex.withLock { if (terminal) null else runId }
}
