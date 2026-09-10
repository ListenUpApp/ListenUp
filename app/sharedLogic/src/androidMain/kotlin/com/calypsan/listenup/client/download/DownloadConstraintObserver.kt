package com.calypsan.listenup.client.download

import com.calypsan.listenup.client.domain.repository.LocalPreferences
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/**
 * Keeps queued and in-flight downloads in step with the Wi-Fi-only preference.
 *
 * WorkManager captures network `Constraints` at enqueue time, so a preference change reaches
 * already-enqueued work only if something re-enqueues it. Without this, turning Wi-Fi-only ON
 * left twenty queued books downloading over cellular while the book-detail button — which
 * reads the LIVE preference through `DefaultBookAvailability` — showed "waiting for Wi-Fi".
 * The app was lying about what it was doing.
 *
 * `drop(1)` skips the StateFlow's current value: at process start
 * `DownloadManager.resumeIncompleteDownloads` has already enqueued everything under the right
 * policy, and re-enqueueing it here would cancel and restart healthy work for nothing.
 */
internal class DownloadConstraintObserver(
    localPreferences: LocalPreferences,
    private val downloadManager: DownloadManager,
    scope: CoroutineScope,
) {
    init {
        scope.launch {
            localPreferences.wifiOnlyDownloads
                .drop(1)
                .distinctUntilChanged()
                .collect { wifiOnly ->
                    // Guard per item so one failure logs and the observer KEEPS COLLECTING —
                    // a dead collector would silently restore the exact bug this fixes.
                    // Re-throw CancellationException so structured cancellation still works.
                    try {
                        downloadManager.reapplyNetworkConstraints(wifiOnly)
                    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.warn(e) { "Failed to re-apply download network constraints" }
                    }
                }
        }
    }
}
