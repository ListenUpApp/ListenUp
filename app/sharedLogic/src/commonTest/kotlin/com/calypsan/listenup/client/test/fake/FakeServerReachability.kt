package com.calypsan.listenup.client.test.fake

import com.calypsan.listenup.client.domain.repository.Reachability
import com.calypsan.listenup.client.domain.repository.ServerReachability
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * In-memory [ServerReachability] with a settable [state] and a [regained] a test emits into; counts [retry] calls in
 * [retryCalls].
 */
class FakeServerReachability : ServerReachability {
    override val state = MutableStateFlow<Reachability>(Reachability.Unknown)

    override val regained = MutableSharedFlow<Unit>()

    var retryCalls = 0
        private set

    override suspend fun retry() {
        retryCalls++
    }
}
