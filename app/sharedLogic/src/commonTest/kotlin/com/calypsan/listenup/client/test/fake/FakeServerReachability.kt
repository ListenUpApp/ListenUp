package com.calypsan.listenup.client.test.fake

import com.calypsan.listenup.client.domain.repository.Reachability
import com.calypsan.listenup.client.domain.repository.ServerReachability
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory [ServerReachability] with a settable [state]; counts [retry] calls in [retryCalls]. */
class FakeServerReachability : ServerReachability {
    override val state = MutableStateFlow<Reachability>(Reachability.Unknown)

    var retryCalls = 0
        private set

    override suspend fun retry() {
        retryCalls++
    }
}
