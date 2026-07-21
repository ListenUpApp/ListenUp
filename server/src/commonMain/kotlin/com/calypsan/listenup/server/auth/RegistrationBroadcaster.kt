package com.calypsan.listenup.server.auth

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

private const val DECISION_BUFFER = 8

/** A terminal registration decision pushed to a waiting (unauthenticated) registrant. */
sealed interface RegistrationDecision {
    /** The registrant was approved; their account is now active. */
    data object Approved : RegistrationDecision

    /** The registrant was denied; [message] is an optional operator-supplied reason. */
    data class Denied(
        val message: String?,
    ) : RegistrationDecision
}

/**
 * In-memory per-userId fan-out of registration approve/deny decisions to waiting SSE subscribers.
 *
 * Single-instance self-hosted — there is no cross-instance fan-out. [notify] is non-blocking
 * (`tryEmit`); with no live subscribers it is a no-op drop (the registrant simply hasn't connected
 * yet, or has already disconnected). A dropped decision is recovered the next time the registrant
 * polls or reconnects, so it never strands them.
 *
 * Each userId owns its own `replay = 0` [MutableSharedFlow], mirroring [com.calypsan.listenup.server.sync.ChangeBus]'s
 * transient control channel: no replay (a decision is a live push, not cursored state) and a small
 * `extraBufferCapacity` with `DROP_OLDEST` so a slow subscriber can never block the notifier.
 *
 * The per-userId map entry is tiny and bounded by the count of distinct pending registrants; on a
 * self-hosted instance that set is trivially small, so no eviction is needed.
 */
class RegistrationBroadcaster {
    private val lock = SynchronizedObject()
    private val flows = HashMap<String, MutableSharedFlow<RegistrationDecision>>()

    private fun flowFor(userId: String): MutableSharedFlow<RegistrationDecision> =
        synchronized(lock) {
            flows.getOrPut(userId) {
                MutableSharedFlow(
                    replay = 0,
                    extraBufferCapacity = DECISION_BUFFER,
                    onBufferOverflow = BufferOverflow.DROP_OLDEST,
                )
            }
        }

    /** A live stream of decisions for [userId]. The RPC watch collects until a terminal decision. */
    fun subscribe(userId: String): SharedFlow<RegistrationDecision> = flowFor(userId).asSharedFlow()

    /** Notify all current subscribers for [userId]. Non-blocking; a no-op drop if none are listening. */
    fun notify(
        userId: String,
        decision: RegistrationDecision,
    ) {
        val flow = synchronized(lock) { flows[userId] }
        flow?.tryEmit(decision)
    }
}
