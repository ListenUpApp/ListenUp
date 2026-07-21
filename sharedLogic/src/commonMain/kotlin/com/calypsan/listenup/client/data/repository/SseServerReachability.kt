package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.domain.model.ConnectionHealth
import com.calypsan.listenup.client.domain.repository.Reachability
import com.calypsan.listenup.client.domain.repository.ServerReachability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Book-availability reachability, projected from the ONE connection-health source
 * ([ConnectionHealthStore]) rather than derived independently from [SyncEngineState].
 *
 * Consolidating the two former oracles removes the split-brain where the shell banner could read
 * Healthy (no Retry) for up to 90s while book-detail read Unreachable ("download only") for the same
 * instant. Both surfaces now fold the same decision — Healthy/Outdated → [Reachability.Reachable];
 * Unreachable / SessionExpired → [Reachability.Unreachable] (the server genuinely can't be
 * used right now — the health store's evidence model vouches for that, so gating
 * play/download on it is honest) — differing only in presentation. The health store already debounces
 * a transient reconnect flap (its 3s Unreachable window), so no extra presentation debounce is needed.
 *
 * [reconnect] is the never-stranded manual retry (wired through the unified recover seam,
 * `SyncRepository.recoverRealtime`), so the reachable-but-not-syncing case offers a working Retry.
 */
internal class SseServerReachability(
    connectionHealth: StateFlow<ConnectionHealth>,
    scope: CoroutineScope,
    private val reconnect: suspend () -> Unit,
) : ServerReachability {
    override val state: StateFlow<Reachability> =
        connectionHealth
            .map { health ->
                when (health) {
                    is ConnectionHealth.Unreachable -> Reachability.Unreachable
                    ConnectionHealth.SessionExpired -> Reachability.Unreachable
                    is ConnectionHealth.Outdated -> Reachability.Reachable
                    ConnectionHealth.Healthy -> Reachability.Reachable
                }
            }.distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, Reachability.Unknown)

    override suspend fun retry() = reconnect()
}
