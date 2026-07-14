package com.calypsan.listenup.api.metadata

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Where a book field's current value came from — the authority tier that governs whether a
 * later write may replace it.
 *
 * The [tier] is the whole point: a write may replace a field's value **iff its tier is
 * greater than or equal to the stored value's tier**. Three tiers, coarsest-wins:
 *  - **[tier] 0 — SCAN**: the value was derived from the files on disk. Every scan-derived
 *    kind ([FOLDER], [FILENAME], [SIDECAR], [EMBEDDED], [ABS_METADATA]) shares this tier;
 *    they differ only in *which* on-disk source won, and ties between them are broken by the
 *    library's `MetadataPrecedence` order **within a single scan pass** (never sticky across
 *    scans — a later scan re-runs the precedence from scratch).
 *  - **[tier] 1 — [ENRICHMENT]**: a metadata provider (Audible/iTunes/Audnexus) was applied to
 *    the field. Out-ranks any scan, so a rescan preserves the enriched value.
 *  - **[tier] 2 — [USER]**: the field was hand-edited in the app. Out-ranks everything, so
 *    neither a rescan nor a provider apply silently reverts it (a provider apply overrides a
 *    user pin only with explicit per-field consent — a ticked checkbox in the review).
 *
 * Rides the wire on [com.calypsan.listenup.api.sync.BookSyncPayload.fieldProvenance], hence
 * `:contract` and `@Serializable`.
 */
@Serializable
enum class FieldSourceKind(
    /** The authority tier: higher wins. A write replaces a value iff `writeTier >= storedTier`. */
    val tier: Int,
) {
    /** SCAN tier: value derived from the book's folder name. */
    FOLDER(0),

    /** SCAN tier: value derived from an audio file's name. */
    FILENAME(0),

    /** SCAN tier: value derived from a sidecar file (e.g. `metadata.json`, `desc.txt`). */
    SIDECAR(0),

    /** SCAN tier: value derived from tags embedded in an audio file. */
    EMBEDDED(0),

    /** SCAN tier: value derived from an Audiobookshelf `metadata.json` import. */
    ABS_METADATA(0),

    /** ENRICHMENT tier: value applied from a metadata provider (Audible/iTunes/Audnexus). */
    ENRICHMENT(1),

    /** USER tier: value hand-edited in the app. Out-ranks scan and enrichment. */
    USER(2),
    ;

    /** True when this kind is a scan-derived source (tier 0). */
    val isScan: Boolean get() = tier == FOLDER.tier
}

/**
 * The provenance of one book field's current value: the authority [kind] that wrote it, the
 * enriching [provider] (when applicable), and when it landed ([at]).
 *
 * Keyed by [BookField] on [com.calypsan.listenup.api.sync.BookSyncPayload.fieldProvenance], this
 * replaces the coarse `userEditedFields` set with per-field authority. The
 * [kind]'s [FieldSourceKind.tier] decides whether the next write to that field is allowed:
 * `writeTier >= storedTier` replaces, otherwise the stored value is preserved. The persisted map
 * is the per-field max-tier union — sticky, exactly like the set-union it replaces.
 */
@Serializable
@SerialName("FieldProvenance")
data class FieldProvenance(
    /** The authority that wrote the field's current value. */
    val kind: FieldSourceKind,
    /**
     * The provider that supplied the value — [MetadataProviderId.value] when
     * [kind] is [FieldSourceKind.ENRICHMENT], `null` otherwise.
     */
    val provider: String? = null,
    /** Epoch milliseconds when this value was written; `0` when unknown. */
    val at: Long = 0,
) {
    /** The authority tier of this provenance — shorthand for `kind.tier`. */
    val tier: Int get() = kind.tier
}
