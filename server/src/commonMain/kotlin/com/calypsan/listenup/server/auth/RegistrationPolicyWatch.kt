package com.calypsan.listenup.server.auth

import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Fallback re-read cadence for the persisted policy — see [pollRegistrationPolicy]. */
private const val POLICY_RECHECK_INTERVAL_MILLIS = 30_000L

/**
 * Re-reads the persisted instance-wide [RegistrationPolicy] on a fixed cadence — the
 * never-stranded net for a missed live broadcast (the broadcaster is replay = 0, so a change
 * pushed before this subscriber existed is otherwise gone). The RPC watch merges this with the
 * live broadcast and dedups, so an unchanged re-read is silent on the wire.
 */
internal fun pollRegistrationPolicy(currentPolicy: suspend () -> RegistrationPolicy): Flow<RegistrationPolicy> =
    flow {
        while (true) {
            delay(POLICY_RECHECK_INTERVAL_MILLIS)
            emit(currentPolicy())
        }
    }
