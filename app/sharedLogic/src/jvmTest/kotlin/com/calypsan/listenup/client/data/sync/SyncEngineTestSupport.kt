package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest

/**
 * Constructs a [SyncReconciler] suitable for unit tests that do not need digest
 * reconciliation to run. The [DomainDigestClient] is never called in practice
 * because [SyncReconciler.reconcileAll] returns early when the store has no cursor,
 * which is the case in every freshly-initialised unit-test database.
 */
internal fun noopSyncReconciler(
    registry: ClientSyncDomainRegistry,
    store: SyncCursorStore,
    catchUp: CatchUp,
): SyncReconciler =
    SyncReconciler(
        registry = registry,
        store = store,
        // Every FakeSyncStreamService member throws, so a digest call this reconciler was not
        // meant to make names itself instead of quietly returning a plausible empty answer.
        digestClient = DomainDigestClient(channel = RpcChannel.forTest(object : FakeSyncStreamService() {})),
        catchUp = catchUp,
    )
