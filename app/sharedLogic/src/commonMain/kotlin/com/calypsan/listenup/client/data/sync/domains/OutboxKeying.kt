package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.client.data.sync.TargetedFetch

/**
 * How a domain's outbox keys relate to its rows, for a domain whose repository keys queued ops by
 * something other than the row's wire id.
 *
 * Since SERVER-SYNC-04 an inbound frame names a row by its opaque wire id, but a repository keys
 * its ops by the identity it writes under: a rating by its book (so a book's edits coalesce and stay
 * FIFO even when the server re-ids the row), a junction by its `"parent:child"` pair. Both halves of
 * the framework that look an op up need a translation, one in each direction:
 *  - [keysOf]: the anti-flicker shield asks "is a local edit queued for the entity this inbound
 *    payload describes?" under every key such an edit could be filed under.
 *  - [refetchFor]: reconcile-on-drain and the DRIFT-1 heal re-read the entity behind an op, so they
 *    need a fetch the domain's server side can actually serve for that key.
 *
 * A domain whose ops are keyed by the wire id declares none; the framework then uses the id as-is.
 */
internal data class OutboxKeying<in T>(
    /** Every outbox key a queued local edit to the entity [T] describes could be filed under. */
    val keysOf: suspend (payload: T) -> Set<String>,
    /** The targeted fetch that re-reads the entity behind an op keyed `outboxKey`; null when none can. */
    val refetchFor: (outboxKey: String) -> TargetedFetch?,
)

/** The outbox key of a junction row: its `"parent:child"` pair, e.g. `"$bookId:$moodId"`. */
internal fun junctionOutboxKey(
    parentId: String,
    childId: String,
): String = "$parentId:$childId"

/** The parent half of a [junctionOutboxKey]. */
internal fun junctionOutboxParent(outboxKey: String): String = outboxKey.substringBefore(':')
