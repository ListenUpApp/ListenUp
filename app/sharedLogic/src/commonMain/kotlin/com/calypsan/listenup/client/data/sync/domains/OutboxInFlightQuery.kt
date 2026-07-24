package com.calypsan.listenup.client.data.sync.domains

/**
 * The anti-flicker shield's one seam: is a local edit for (domainName, entityId) still in flight?
 *
 * The engine's single apply choke point ([ComposedSyncDomainHandler]) consults this before applying
 * an inbound firehose echo or catch-up snapshot. When a still-dispatchable outbox op exists for the
 * entity, its authoritative post-edit state will arrive via that op's own echo once it drains — so
 * the current (possibly stale) inbound snapshot is shielded rather than allowed to revert the
 * optimistic local edit. One policy point; every mirrored domain inherits it, keyed on entity
 * identity rather than a wire-borne `clientOpId`.
 *
 * Backed in production by [com.calypsan.listenup.client.data.sync.PendingOperationQueue.hasQueuedOpFor].
 * Dead-lettered ops do not count as in-flight, so a permanently-failed edit lifts the shield and the
 * entity converges to server truth.
 */
internal fun interface OutboxInFlightQuery {
    suspend fun isQueued(
        domainName: String,
        entityId: String,
    ): Boolean
}

/** The default for domains/handlers with no outbox and for tests that don't exercise the shield. */
internal val NoOutboxInFlight = OutboxInFlightQuery { _, _ -> false }
