package com.calypsan.listenup.server.auth

import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

private const val POLICY_BUFFER = 8

/**
 * In-memory fan-out of the instance-wide [RegistrationPolicy] to unauthenticated SSE subscribers
 * (clients sitting on the login screen).
 *
 * Single-instance self-hosted — one global stream, not a per-user map: the policy is instance-wide.
 * [notify] is non-blocking (`tryEmit`); with no live subscribers it is a no-op drop, because the
 * policy is durable in `server_settings` and every subscriber re-reads the current value on connect
 * (the SSE route's `onStart`) — so a dropped notification is recovered on the next connect/reconnect
 * and never strands a client on a stale button.
 *
 * `replay = 0` (a policy change is a live push, not cursored state) with a small
 * `extraBufferCapacity` and `DROP_OLDEST`, mirroring [RegistrationBroadcaster] and the sync
 * [com.calypsan.listenup.server.sync.ChangeBus] control channel, so a slow subscriber can never
 * block the admin's write.
 */
class RegistrationPolicyBroadcaster {
    private val flow =
        MutableSharedFlow<RegistrationPolicy>(
            replay = 0,
            extraBufferCapacity = POLICY_BUFFER,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    /** A live stream of policy changes. The SSE route collects this for the connection's lifetime. */
    fun subscribe(): SharedFlow<RegistrationPolicy> = flow.asSharedFlow()

    /** Notify all current subscribers of a new [policy]. Non-blocking; a no-op drop if none listen. */
    fun notify(policy: RegistrationPolicy) {
        flow.tryEmit(policy)
    }
}
