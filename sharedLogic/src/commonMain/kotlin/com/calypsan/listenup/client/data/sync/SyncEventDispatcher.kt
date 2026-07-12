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
 *  - control events are recognised: refresh controls run their catalog-declared refresh
 *    strategy via [refreshedRouter]; engine/lifecycle controls (`CursorStale`,
 *    `StreamError`, `AccessChanged`, `UserDeleted`, `LibraryDataChanged`) fire their callbacks,
 *  - unknown domains are logged and dropped (graceful for forward-compat),
 *  - the per-domain cursor advances only after a successful apply — a Failure leaves it for catch-up redelivery.
 */
internal class SyncEventDispatcher(
    private val registry: ClientSyncDomainRegistry,
    private val state: SyncEngineState,
    private val cursorAdvance: suspend (domainName: String, revision: Long) -> Unit,
    private val refreshedRouter: RefreshedDomainRouter = RefreshedDomainRouter(emptyList()),
    private val onCursorStale: suspend () -> Unit = {},
    private val onAccessChanged: suspend (scope: AccessScope?) -> Unit = {},
    private val onUserDeleted: suspend (reason: String?) -> Unit = {},
    private val onLibraryDataChanged: suspend () -> Unit = {},
    private val reportCompat: (String) -> Unit = {},
) {
    /**
     * Digest OPT-OUT domains (positions) whose live cursor advancement is frozen because an apply
     * failed. For a domain with no digest backstop the cursor is the ONLY redelivery path, so once
     * a hole opens we must not let a *later* event (a different book at a higher revision) step the
     * cursor past the failed revision — that would strand it forever. Frozen entries stay put for
     * the session; the next catch-up re-pulls from the held cursor and heals the hole (its own
     * `setCursor` then advances the real cursor, which is monotonic, so the freeze never regresses
     * it). SSE frames are processed by a single collector, so a plain set needs no synchronisation.
     */
    private val frozenOptOutDomains = mutableSetOf<String>()

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
                reportCompat("SSE control frame undecodable: ${e.message}")
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
                reportCompat("SSE event undecodable for domain '$domainName': ${e.message}")
                return
            }
        when (val result = typed.onEvent(event)) {
            is AppResult.Success -> {
                // A frozen OptOut domain must not advance the cursor past its held (pre-hole)
                // watermark: doing so on this later event would strand the earlier failed
                // revision that only the cursor can redeliver. Digest-backed domains always
                // advance (a missed apply self-heals on the next reconcile).
                if (typed.hasDigestBackstop || domainName !in frozenOptOutDomains) {
                    frame.id?.let { rev -> cursorAdvance(domainName, rev) }
                } else {
                    logger.debug {
                        "[$domainName] cursor frozen (no digest backstop, prior apply failed); " +
                            "not advancing to ${frame.id} — catch-up will re-pull and heal"
                    }
                }
            }

            is AppResult.Failure -> {
                // Leave the cursor where it is: the next REST catch-up (engine start or
                // CursorStale recovery) starts at-or-below this revision and re-delivers the
                // entity's canonical state. Applies are idempotent upserts, so redelivery is
                // safe. The ack above is deliberately NOT rolled back — the server confirmed
                // the op; catch-up re-applies the canonical state without echo shielding.
                if (!typed.hasDigestBackstop) {
                    // No reconcile backstop: freeze cursor advancement at the last successfully
                    // applied revision (SSE frames are revision-ordered, so the cursor already
                    // sits just below this hole). Only catch-up re-pull can advance past it now.
                    frozenOptOutDomains += domainName
                }
                logger.warn {
                    "Apply failed for domain '$domainName' (revision=${frame.id}): " +
                        "${result.error.code}; cursor not advanced" +
                        if (!typed.hasDigestBackstop) " (frozen: no digest backstop)" else ""
                }
                state.recordError(result.error)
            }
        }
    }
}
