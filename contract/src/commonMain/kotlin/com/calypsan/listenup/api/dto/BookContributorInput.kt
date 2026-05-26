package com.calypsan.listenup.api.dto

import com.calypsan.listenup.core.ContributorId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Input row for [com.calypsan.listenup.api.BookService.setBookContributors].
 *
 * When [id] is non-null, the server uses that contributor as-is.
 * When [id] is null, the server resolves via
 * `ContributorRepository.resolveOrCreate` using [name] (same path as scanner
 * ingest). [creditedAs] preserves a per-book display variant when the canonical
 * contributor name differs from how the contributor should be credited on this
 * specific book.
 */
@Serializable
@SerialName("BookContributorInput")
data class BookContributorInput(
    @SerialName("id") val id: ContributorId? = null,
    @SerialName("name") val name: String,
    @SerialName("role") val role: String,
    @SerialName("creditedAs") val creditedAs: String? = null,
    @SerialName("position") val position: Int,
) {
    init {
        require(name.isNotBlank()) { "name must not be blank" }
        require(name.length <= MAX_NAME) { "name must be <= $MAX_NAME chars" }
        require(role.isNotBlank()) { "role must not be blank" }
        require(role.length <= MAX_ROLE) { "role must be <= $MAX_ROLE chars" }
        require(position >= 0) { "position must be non-negative" }
        creditedAs?.let { require(it.length <= MAX_CREDITED_AS) { "creditedAs must be <= $MAX_CREDITED_AS chars" } }
    }

    companion object {
        const val MAX_NAME = 500
        const val MAX_ROLE = 64
        const val MAX_CREDITED_AS = 512
    }
}
