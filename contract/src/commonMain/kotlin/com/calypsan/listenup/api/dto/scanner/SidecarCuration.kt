package com.calypsan.listenup.api.dto.scanner

import com.calypsan.listenup.api.sync.UserEditedField
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Curation provenance re-ingested from an external `listenup.json` sidecar during a scan —
 * the narrow payload the scanner hands to the books-domain persist port on
 * [AnalyzedBook.sidecarCuration], deliberately NOT the raw sidecar model (the scanner→persist
 * boundary stays thin: field-protection provenance plus user chapters, nothing else).
 *
 * The persist merge applies it *additively*: [userEditedFields] unions into the stored
 * protection set (restoring rescan-protection after a DB wipe — the sidecar's reason to
 * exist) and [userChapters], when present, persist with `chapter_source = 'user'`. Sidecar
 * curation never *clears* an existing protection.
 */
@Serializable
data class SidecarCuration(
    /** Fields the sidecar declares user-edited — unioned into the stored protection set. */
    @SerialName("userEditedFields")
    val userEditedFields: Set<UserEditedField> = emptySet(),
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
