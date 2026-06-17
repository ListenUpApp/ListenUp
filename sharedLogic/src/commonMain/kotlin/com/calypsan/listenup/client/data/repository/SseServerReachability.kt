package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.data.sync.ConnectionState
import com.calypsan.listenup.client.data.sync.SyncEngineState
import com.calypsan.listenup.client.domain.repository.Reachability
import com.calypsan.listenup.client.domain.repository.ServerReachability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Derives [Reachability] from the live SSE firehose connection ([SyncEngineState]).
 * A brief debounce absorbs transient reconnect flaps so the UI doesn't flicker.
 *
 * [reconnect] forces the firehose to drop and re-open (wired to `SyncEngine.reconnect`),
 * backing the never-stranded manual retry on the offline banner.
 */
@OptIn(FlowPreview::class)
internal class SseServerReachability(
    engineState: SyncEngineState,
    scope: CoroutineScope,
    private val reconnect: suspend () -> Unit,
) : ServerReachability {
    override val state: StateFlow<Reachability> =
        engineState
            .observe()
            .map { snapshot ->
                when (snapshot.connection) {
                    is ConnectionState.Connected -> Reachability.Reachable
                    ConnectionState.Connecting -> Reachability.Unknown
                    is ConnectionState.Disconnected -> Reachability.Unreachable
                }
            }.debounce(DEBOUNCE_MILLIS)
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, Reachability.Unknown)

    override suspend fun retry() = reconnect()

    private companion object {
        const val DEBOUNCE_MILLIS = 400L
    }
}
