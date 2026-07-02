package com.calypsan.listenup.client.data.sync.domains

/**
 * Whether the domain participates in digest-based drift reconciliation.
 */
internal sealed interface DigestParticipation {
    /**
     * The domain is fingerprintable: [rows] returns local `(id, revision)` pairs with
     * `revision <= maxRevision`, INCLUDING soft-deleted rows — the exact set the
     * server's digest covers.
     */
    class Full(
        val rows: suspend (maxRevision: Long) -> List<Pair<String, Long>>,
    ) : DigestParticipation

    /**
     * The domain cannot be fingerprinted client-side; the reconciler skips it.
     * [reason] is load-bearing documentation — it states exactly what structural fact
     * prevents participation.
     */
    data class OptOut(
        val reason: String,
    ) : DigestParticipation
}
