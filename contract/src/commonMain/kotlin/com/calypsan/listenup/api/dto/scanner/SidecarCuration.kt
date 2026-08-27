package com.calypsan.listenup.api.dto.scanner

import com.calypsan.listenup.api.metadata.BookField
import com.calypsan.listenup.api.metadata.FieldProvenance
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Curation provenance re-ingested from an external `listenup.json` sidecar during a scan —
 * the narrow payload the scanner hands to the books-domain persist port on
 * [AnalyzedBook.sidecarCuration], deliberately NOT the raw sidecar model (the scanner→persist
 * boundary stays thin: field-protection provenance plus user chapters, nothing else).
 *
 * The persist merge folds [fieldProvenance] in by **max tier** — the same one write rule the
 * rest of the pipeline obeys (`writeTier >= storedTier`). After a database wipe the scan payload
 * carries only tier-0 entries, so every recorded enrichment/user entry wins and the curation
 * comes back at the tier it was recorded at — the sidecar's reason to exist. Against a live
 * database a stale sidecar can never demote a value that already out-ranks it.
 *
 * [userChapters], when present, persist with `chapter_source = 'user'`. Sidecar curation never
 * *lowers* an existing field's authority.
 */
@Serializable
data class SidecarCuration(
    /**
     * The provenance the sidecar recorded per field — merged into the stored map by max tier.
     * Fields the sidecar names but this server doesn't know are dropped by the reader.
     */
    @SerialName("fieldProvenance")
    val fieldProvenance: Map<BookField, FieldProvenance> = emptyMap(),
    /** The sidecar's USER-sourced chapter set, or null when the sidecar carries none. */
    @SerialName("userChapters")
    val userChapters: List<SidecarCurationChapter>? = null,
)

/** One user-curated chapter from a `listenup.json` sidecar: title + start offset in the book. */
@Serializable
data class SidecarCurationChapter(
    @SerialName("title")
    val title: String,
    @SerialName("startMs")
    val startMs: Long,
)
