package com.calypsan.listenup.server.hardcover

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
internal data class GraphQlRequest(
    @SerialName("query") val query: String,
    @SerialName("variables") val variables: JsonObject = JsonObject(emptyMap()),
)

@Serializable
internal data class MeResponse(
    @SerialName("data") val data: MeData? = null,
)

@Serializable
internal data class MeData(
    @SerialName("me") val me: List<MeWire> = emptyList(),
)

@Serializable
internal data class MeWire(
    @SerialName("id") val id: Long,
    @SerialName("username") val username: String,
)

@Serializable
internal data class EditionsResponse(
    @SerialName("data") val data: EditionsData? = null,
)

@Serializable
internal data class EditionsData(
    @SerialName("editions") val editions: List<EditionWire> = emptyList(),
)

@Serializable
internal data class EditionWire(
    @SerialName("book") val book: BookWire? = null,
)

@Serializable
internal data class BooksResponse(
    @SerialName("data") val data: BooksData? = null,
)

@Serializable
internal data class BooksData(
    @SerialName("books") val books: List<BookWire> = emptyList(),
)

@Serializable
internal data class BookWire(
    @SerialName("title") val title: String,
    @SerialName("rating") val rating: Double? = null,
    @SerialName("ratings_count") val ratingsCount: Int = 0,
    @SerialName("contributions") val contributions: List<ContributionWire> = emptyList(),
) {
    internal fun toCandidate() =
        HardcoverBookCandidate(
            title = title,
            author = contributions.firstOrNull()?.author?.name,
            average = rating.takeIf { ratingsCount > 0 },
            count = ratingsCount,
        )
}

@Serializable
internal data class ContributionWire(
    @SerialName("author") val author: AuthorWire? = null,
)

@Serializable
internal data class AuthorWire(
    @SerialName("name") val name: String? = null,
)
