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
    @Serializable
    @SerialName("added")
    data class Added(val book: AnalyzedBook) : ChangeEventDto

    @Serializable
    @SerialName("modified")
    data class Modified(
        val book: AnalyzedBook,
        val previousRootRelPath: String,
    ) : ChangeEventDto

    @Serializable
    @SerialName("removed")
    data class Removed(val rootRelPath: String) : ChangeEventDto

    @Serializable
    @SerialName("moved")
    data class Moved(
        val from: String,
        val to: String,
        val book: AnalyzedBook,
    ) : ChangeEventDto
}
