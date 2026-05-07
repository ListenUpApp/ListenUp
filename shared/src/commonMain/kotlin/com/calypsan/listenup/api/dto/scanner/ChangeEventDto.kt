package com.calypsan.listenup.api.dto.scanner

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What the Differ reports between scans. The wire-side mirror of the
 * server-internal `ChangeEvent` — the contract is what crosses the wire,
 * the internal type can be richer.
 */
@Serializable
sealed interface ChangeEventDto {
    /** A book present in the current scan that was absent from the prior index. */
    @Serializable
    @SerialName("added")
    data class Added(
        val book: AnalyzedBook,
    ) : ChangeEventDto

    /**
     * A book whose tracked content changed (metadata, file size, or mtime) since the prior scan.
     * [previousRootRelPath] is the path the prior index recorded for the same book; clients use
     * it to locate and update the existing entry.
     */
    @Serializable
    @SerialName("modified")
    data class Modified(
        val book: AnalyzedBook,
        val previousRootRelPath: String,
    ) : ChangeEventDto

    /** A book that was in the prior index but is no longer present at [rootRelPath]. */
    @Serializable
    @SerialName("removed")
    data class Removed(
        val rootRelPath: String,
    ) : ChangeEventDto

    /**
     * A book identified at a new path. Distinct from [Modified] in that [from] and [to] differ
     * (a pure rename or relocation); [book] is the re-analyzed snapshot at the new location.
     */
    @Serializable
    @SerialName("moved")
    data class Moved(
        val from: String,
        val to: String,
        val book: AnalyzedBook,
    ) : ChangeEventDto
}
