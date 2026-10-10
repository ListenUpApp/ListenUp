package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.client.data.repository.PendingImageUploadsImpl
import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.NetworkMonitor
import com.calypsan.listenup.core.error.ErrorBus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

private val logger = KotlinLogging.logger {}

/**
 * Starts a pass over the pending image uploads at the moments an upload can newly succeed: when the app
 * starts signed in with the network up, when the device comes back online, and when the server connection
 * comes up. It is the image counterpart of the outbox's drain triggers, kept out of `SyncEngine`.
 *
 * Each trigger is an edge to "ready", so a long-lived connection does not start a pass per event, and
 * [PendingImageUploadsImpl.drain] itself collapses overlapping triggers into one pass at a time. An
 * image the server refuses is reported once on the [ErrorBus] with the server's own error.
 */
internal class PendingImageUploadScheduler(
    private val uploads: PendingImageUploadsImpl,
    private val authSession: AuthSession,
    private val networkMonitor: NetworkMonitor,
    private val syncEngineState: SyncEngineState,
    private val errorBus: ErrorBus,
    private val scope: CoroutineScope,
) {
    /** Begin watching. Called once at app start; the returned job lives as long as [scope]. */
    fun start(): Job =
        scope.launch {
            val deviceReady =
                combine(
                    authSession.authState.map { it is AuthState.Authenticated },
                    networkMonitor.onlineFlow,
                ) { signedIn, online -> signedIn && online }
                    .distinctUntilChanged()
                    .filter { it }
            val serverConnected =
                syncEngineState
                    .observe()
                    .map { it.connection is ConnectionState.Connected }
                    .distinctUntilChanged()
                    .filter { it }
            merge(deviceReady, serverConnected).collect { drainOnce() }
        }

    private suspend fun drainOnce() {
        try {
            val outcome = uploads.drain()
            outcome.refused.forEach { errorBus.emit(it.error) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A pass that throws must not end the watch: the next trigger tries again.
            logger.warn(e) { "Pending image upload pass failed; the next trigger retries" }
        }
    }
}
