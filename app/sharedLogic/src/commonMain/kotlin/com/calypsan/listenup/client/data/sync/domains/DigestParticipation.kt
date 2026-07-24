package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.client.data.local.db.IdRevision

/**
 * Whether the domain participates in digest-based drift reconciliation.
 */
internal sealed interface DigestParticipation {
    /**
     * The domain is fingerprintable: [rows] returns local `(id, revision)` pairs with
     * `revision <= maxRevision`, EXCLUDING soft-deleted rows — the exact LIVE set the
     * server's (now tombstone-excluding) digest covers, so a locally-tombstoned row leaves
     * both digests at once and the member converges (F1).
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

/**
 * The standard [DigestParticipation.Full] shape: every participating domain
 * fingerprints via its DAO's `digestRows` returning the shared [IdRevision]
 * projection. Factored so a descriptor cannot mis-map the pair (a wrong field
 * here would be a silent digest bug 19 times over).
 */
internal fun fullDigest(rows: suspend (maxRevision: Long) -> List<IdRevision>): DigestParticipation.Full =
    DigestParticipation.Full { maxRevision -> rows(maxRevision).map { it.id to it.revision } }
