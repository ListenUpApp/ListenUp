package com.calypsan.listenup.server.hardcover

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
internal data class BooksResponse(
    @SerialName("data") val data: BooksData? = null,
)

@Serializable
internal data class BooksData(
    @SerialName("books") val books: List<BookWire> = emptyList(),
)

@Serializable
internal data class EditionWire(
    @SerialName("id") val id: Long = 0,
    @SerialName("reading_format_id") val readingFormatId: Int? = null,
    @SerialName("book") val book: BookWire? = null,
)

@Serializable
internal data class BookWire(
    @SerialName("id") val id: Long = 0,
    @SerialName("title") val title: String,
    @SerialName("rating") val rating: Double? = null,
    @SerialName("ratings_count") val ratingsCount: Int = 0,
    @SerialName("release_year") val releaseYear: Int? = null,
    @SerialName("default_audio_edition_id") val defaultAudioEditionId: Long? = null,
    @SerialName("contributions") val contributions: List<ContributionWire> = emptyList(),
) {
    fun toCatalogBook() =
        HardcoverCatalogBook(
            id = id,
            title = title,
            authors = contributions.mapNotNull { it.author?.name },
            average = rating.takeIf { ratingsCount > 0 },
            count = ratingsCount,
            releaseYear = releaseYear,
            defaultAudioEditionId = defaultAudioEditionId,
        )
}

@Serializable
internal data class SearchResponse(
    @SerialName("data") val data: SearchData? = null,
)

@Serializable
internal data class SearchData(
    @SerialName("search") val search: SearchWire? = null,
)

@Serializable
internal data class SearchWire(
    @SerialName("results") val results: SearchResultsWire? = null,
)

@Serializable
internal data class SearchResultsWire(
    @SerialName("hits") val hits: List<SearchHitWire> = emptyList(),
)

@Serializable
internal data class SearchHitWire(
    @SerialName("document") val document: SearchDocumentWire,
)

@Serializable
internal data class SearchDocumentWire(
    @SerialName("id") val id: JsonPrimitive,
    @SerialName("title") val title: String = "",
    @SerialName("author_names") val authorNames: List<String> = emptyList(),
    @SerialName("release_year") val releaseYear: Int? = null,
)

@Serializable
internal data class ContributionWire(
    @SerialName("author") val author: AuthorWire? = null,
)

@Serializable
internal data class AuthorWire(
    @SerialName("name") val name: String? = null,
)
