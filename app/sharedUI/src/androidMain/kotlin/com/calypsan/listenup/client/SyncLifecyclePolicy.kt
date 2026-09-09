package com.calypsan.listenup.client

import com.calypsan.listenup.client.domain.repository.SyncRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

private val logger = KotlinLogging.logger {}

/**
 * Owns the realtime-sync connect/disconnect pair for one foreground window.
 *
 * Both halves belong to the SAME lifecycle window. Disconnecting in `onPause` treated a
 * photo picker, a permission dialog, and a multi-window focus change as "the app went to
 * the background" — tearing the socket down and rebuilding it seconds later. `onStop`
 * (i.e. leaving `Lifecycle.State.STARTED`) is the honest signal, so the disconnect lives in
 * the `finally` of the block `repeatOnLifecycle(STARTED)` runs.
 *
 * Extracted from `MainActivity` so the pairing is testable without a Robolectric activity.
 */
internal class SyncLifecyclePolicy(
    private val syncRepository: SyncRepository,
    private val authenticatedStates: () -> Flow<Boolean>,
) {
    /**
     * Collects authentication state for as long as the caller's scope lives, connecting
     * realtime sync whenever the user is authenticated, and disconnecting when the scope
     * ends — including on cancellation, which is how `repeatOnLifecycle` signals STOP.
     */
    suspend fun runWhileStarted() {
        try {
            authenticatedStates().distinctUntilChanged().collect { authenticated ->
                if (authenticated) {
                    logger.debug { "Authenticated — connecting realtime sync" }
                    syncRepository.connectRealtime()
                }
            }
        } finally {
            // NonCancellable: this runs while the scope is ALREADY being cancelled, so a
            // plain suspend call would abort at its first suspension point and leak the
            // socket. Same reasoning as PlaybackService.saveCurrentPositionBlocking.
            withContext(NonCancellable) {
                logger.debug { "Left the STARTED window — disconnecting realtime sync" }
                syncRepository.disconnect()
            }
        }
    }
}
