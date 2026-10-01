package com.calypsan.listenup.api.dto.hardcover

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One Hardcover book a catalog search found, offered when the user links a book by hand. Pass
 * [hcBookId] and [hcEditionId] to [com.calypsan.listenup.api.HardcoverService.linkBook].
 * [hcEditionId] is the audiobook edition Hardcover shows for the book, or null when it names none.
 * [ratingsCount] is how many Hardcover readers rated it — the quickest tell between the real book
 * and a third-party summary of it — or null from a server that predates it.
 */
@Serializable
data class HardcoverBookCandidate(
    @SerialName("hcBookId") val hcBookId: Long,
    @SerialName("hcEditionId") val hcEditionId: Long? = null,
    @SerialName("title") val title: String,
    @SerialName("authors") val authors: List<String> = emptyList(),
    @SerialName("releaseYear") val releaseYear: Int? = null,
    @SerialName("ratingsCount") val ratingsCount: Int? = null,
)
