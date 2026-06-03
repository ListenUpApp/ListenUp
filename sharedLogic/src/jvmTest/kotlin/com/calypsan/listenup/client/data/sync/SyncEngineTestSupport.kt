package com.calypsan.listenup.client.data.sync

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
        digestClient =
            DomainDigestClient(
                httpClientProvider = { error("noopSyncReconciler digest client should never be called") },
                serverUrlProvider = { "" },
            ),
        catchUp = catchUp,
    )
