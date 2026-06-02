package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.sync.SyncControl
import com.calypsan.listenup.api.sync.SyncEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.coroutines.cancellation.CancellationException

private val logger = KotlinLogging.logger {}

/**
 * Routes parsed SSE frames to the right handler. The single seam where:
 *  - typed event payloads are decoded using the handler's `KSerializer<T>`,
 *  - `clientOpId` echoes are matched against the pending queue (and acked),
 *  - control events (`CursorStale`, `StreamError`, `AccessChanged`) are recognised and acted on,
 *  - unknown domains are logged and dropped (graceful for forward-compat).
 */
class SyncEventDispatcher(
    private val registry: ClientSyncDomainRegistry,
    private val queue: PendingOperationQueue,
    private val state: SyncEngineState,
    private val cursorAdvance: suspend (domainName: String, revision: Long) -> Unit,
    private val onCursorStale: suspend (lastKnown: Long?) -> Unit = {},
    private val onAccessChanged: suspend () -> Unit = {},
    private val onUserDeleted: suspend (reason: String?) -> Unit = {},
) {
    /** Route a parsed SSE frame: control events, data events, or no-op for missing event lines. */
    suspend fun handle(frame: ParsedSseFrame) {
        when (frame.event) {
            "control" -> handleControl(frame)
            null -> logger.debug { "SSE frame with no event: line, id=${frame.id}" }
            else -> handleData(frame)
        }
    }

    private suspend fun handleControl(frame: ParsedSseFrame) {
        val control =
            try {
                contractJson.decodeFromString(SyncControl.serializer(), frame.data)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "Failed to decode SyncControl frame" }
                return
            }
        when (control) {
            is SyncControl.CursorStale -> {
                logger.info {
                    "Cursor stale; signalling engine to orchestrate recovery (lastKnown=${control.lastKnownRevision})"
                }
                onCursorStale(control.lastKnownRevision)
            }

            is SyncControl.StreamError -> {
                logger.warn { "SSE stream error from server: ${control.error.code}" }
                state.recordError(control.error)
            }

            SyncControl.AccessChanged -> {
                logger.info { "AccessChanged received; re-deriving accessible set via catch-up" }
                onAccessChanged()
            }

            is SyncControl.UserDeleted -> {
                logger.info { "UserDeleted received; clearing auth" }
                onUserDeleted(control.reason)
            }
        }
    }

    private suspend fun handleData(frame: ParsedSseFrame) {
        val domainName = frame.event ?: return
        val handler =
            registry.lookup(domainName) ?: run {
                logger.debug { "No handler registered for domain '$domainName'; dropping event" }
                return
            }

        @Suppress("UNCHECKED_CAST")
        val typed = handler as SyncDomainHandler<Any>
        val event =
            try {
                contractJson.decodeFromString(
                    SyncEvent.serializer(typed.payloadSerializer),
                    frame.data,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "Failed to decode SyncEvent for domain '$domainName'" }
                return
            }
        val isOwnEcho = event.clientOpId?.let { queue.containsAndAck(it) } ?: false
        typed.onEvent(event, isOwnEcho)
        frame.id?.let { rev -> cursorAdvance(domainName, rev) }
    }
}
