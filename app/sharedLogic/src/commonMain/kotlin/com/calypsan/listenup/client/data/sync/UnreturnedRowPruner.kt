package com.calypsan.listenup.client.data.sync

/**
 * Capability of a domain whose digest repair re-pulls it from zero: after a complete pull it tombstones
 * the live local rows the server no longer has. A restore to an older backup leaves every device holding
 * rows created after that backup; kept, they hold the digest apart forever and the domain is re-pulled
 * on every connect. Access-gated domains remove rows through [AccessFilteredSyncHandler.pruneTo] instead,
 * so [SyncReconciler] asks that capability first.
 */
internal interface UnreturnedRowPruner {
    /**
     * Tombstones, at [now], every live local row with a revision up to [FromZeroPull.Complete.throughRevision]
     * whose id [pull] did not return. A row the server never acknowledged (revision 0: an optimistic add or
     * a stub another domain wrote) and a row with a local write still queued are kept: neither is a row the
     * server dropped. A row newer than the pull is outside its range and kept too.
     */
    suspend fun pruneUnreturned(
        pull: FromZeroPull.Complete,
        now: Long,
    )
}
