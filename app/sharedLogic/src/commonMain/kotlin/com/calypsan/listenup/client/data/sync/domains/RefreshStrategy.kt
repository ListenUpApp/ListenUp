package com.calypsan.listenup.client.data.sync.domains

/**
 * How a [RefreshedDomain] responds to its trigger. Extracted from what the two refresh
 * mechanisms do today: fire a hot refresh signal (collectors re-fetch), or run a
 * best-effort suspend re-fetch directly.
 */
internal sealed interface RefreshStrategy {
    /**
     * Ping a hot refresh signal so its collectors re-fetch. Non-suspending and
     * fire-and-forget — a dropped ping is harmless (the collector re-fetches on the
     * next one, or on reconnect).
     */
    class Ping(
        val ping: () -> Unit,
    ) : RefreshStrategy

    /**
     * Run a suspend re-fetch inline. Declared best-effort: the router swallows
     * non-cancellation failures so a refresh re-fetch can never take the firehose dispatch
     * loop down.
     */
    class Refetch(
        val refetch: suspend () -> Unit,
    ) : RefreshStrategy
}
