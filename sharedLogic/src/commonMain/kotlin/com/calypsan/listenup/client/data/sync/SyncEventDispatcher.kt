package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.AccessScope
import com.calypsan.listenup.api.sync.SyncControl
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.client.data.sync.domains.RefreshedDomainRouter
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.coroutines.cancellation.CancellationException

private val logger = KotlinLogging.logger {}

/**
 * Routes parsed SSE frames to the right handler. The single seam where:
 *  - typed event payloads are decoded using the handler's `KSerializer<T>`,
 *  - `clientOpId` echoes are matched against the pending queue (and acked),
 *  - control events are recognised: refresh controls run their catalog-declared refresh
 *    strategy via [refreshedRouter]; engine/lifecycle controls (`CursorStale`,
 *    `StreamError`, `AccessChanged`, `UserDeleted`, `LibraryDataChanged`) fire their callbacks,
 *  - unknown domains are logged and dropped (graceful for forward-compat),
 *  - the per-domain cursor advances only after a successful apply — a Failure leaves it for catch-up redelivery.
 */
internal class SyncEventDispatcher(
    private val registry: ClientSyncDomainRegistry,
    private val queue: PendingOperationQueue,
    private val state: SyncEngineState,
    private val cursorAdvance: suspend (domainName: String, revision: Long) -> Unit,
    private val refreshedRouter: RefreshedDomainRouter = RefreshedDomainRouter(emptyList()),
    private val onCursorStale: suspend () -> Unit = {},
    private val onAccessChanged: suspend (scope: AccessScope?) -> Unit = {},
    private val onUserDeleted: suspend (reason: String?) -> Unit = {},
    private val onLibraryDataChanged: suspend () -> Unit = {},
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
        // Refreshed tier: catalog-declared refresh strategies. Engine/lifecycle controls fall through.
        if (refreshedRouter.dispatch(control)) return
        when (control) {
            is SyncControl.CursorStale -> {
                logger.info {
                    "Cursor stale; signalling engine to orchestrate recovery (lastKnown=${control.lastKnownRevision})"
                }
                onCursorStale()
            }

            is SyncControl.StreamError -> {
                logger.warn { "SSE stream error from server: ${control.error.code}" }
                state.recordError(control.error)
            }

            is SyncControl.AccessChanged -> {
                logger.info {
                    val shape =
                        control.scope?.let { "delta(${it.collectionIds.size}c/${it.bookIds.size}b)" } ?: "coarse"
                    "AccessChanged received ($shape); re-deriving accessible set"
                }
                onAccessChanged(control.scope)
            }

            is SyncControl.UserDeleted -> {
                logger.info { "UserDeleted received; clearing auth" }
                onUserDeleted(control.reason)
            }

            SyncControl.LibraryDataChanged -> {
                logger.info { "LibraryDataChanged received; reconciling all domains via digest" }
                onLibraryDataChanged()
            }

            // The refreshed tier is handled by refreshedRouter above. Reaching here means a
            // catalog RefreshedDomain entry is missing — log loudly rather than drop silently.
            SyncControl.ActiveSessionsChanged,
            SyncControl.ServerInfoChanged,
            SyncControl.PreferencesChanged,
            SyncControl.CampfiresChanged,
            -> {
                logger.warn { "Refresh control $control unclaimed by any RefreshedDomain; dropped" }
            }

            // Activities are now a Room-mirrored data domain, not a refresh trigger. A stray ActivityChanged
            // control (e.g. from an older server) has no refresh strategy — drop it generically.
            SyncControl.ActivityChanged -> {
                logger.warn {
                    "Received legacy ActivityChanged control; activities now sync as a data domain — dropped"
                }
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
        when (val result = typed.onEvent(event, isOwnEcho)) {
            is AppResult.Success -> {
                frame.id?.let { rev -> cursorAdvance(domainName, rev) }
            }

            is AppResult.Failure -> {
                // Leave the cursor where it is: the next REST catch-up (engine start or
                // CursorStale recovery) starts at-or-below this revision and re-delivers the
                // entity's canonical state. Applies are idempotent upserts, so redelivery is
                // safe. The ack above is deliberately NOT rolled back — the server confirmed
                // the op; catch-up re-applies the canonical state without echo shielding.
                logger.warn {
                    "Apply failed for domain '$domainName' (revision=${frame.id}): " +
                        "${result.error.code}; cursor not advanced"
                }
                state.recordError(result.error)
            }
        }
    }
}
