package com.calypsan.listenup.server.hardcover

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** An audiobook edition as Find reads it: what an edition knows that its book doesn't. */
data class HardcoverFindEdition(
    val editionId: Long,
    val asin: String?,
    val isbn: String?,
    val audioSeconds: Long?,
    val editionFormat: String?,
    val releaseDate: String?,
    val imageUrl: String?,
    val narrators: List<String>,
    val isAudiobook: Boolean,
)

/** A book as Find reads it, with its default audiobook edition when Hardcover names one. */
data class HardcoverFindBook(
    val id: Long,
    val title: String,
    val subtitle: String?,
    val releaseYear: Int?,
    val authors: List<String>,
    val imageUrl: String?,
    val audioEdition: HardcoverFindEdition?,
)

/** An edition found by an exact identifier, with its book. */
data class HardcoverFindHit(
    val edition: HardcoverFindEdition,
    val book: HardcoverFindBook,
)

/** One batched Find read: the books asked for by id, and the editions found by ASIN and by ISBN. */
data class HardcoverFindResult(
    val books: List<HardcoverFindBook>,
    val byAsin: HardcoverFindHit?,
    val byIsbn: HardcoverFindHit?,
)

/** Hardcover's `reading_formats` id for audiobooks ("Listened"). */
private const val AUDIOBOOK_READING_FORMAT = 2
private const val AUTHOR_ROLE = "Author"
private const val NARRATOR_ROLE = "Narrator"

private const val EDITION_FIELDS =
    "id asin isbn_13 isbn_10 audio_seconds edition_format release_date reading_format_id image{ url } " +
        "contributions{ contribution author{ name } }"
private const val BOOK_FIELDS =
    "id title subtitle release_year image{ url } contributions{ contribution author{ name } } " +
        "default_audio_edition{ $EDITION_FIELDS }"

/**
 * The books with [ids] (each with its default audiobook edition), the edition with [asin] and the edition with
 * [isbn], in one request (matching redesign PR 2). A clause whose value is missing is left out of the query
 * entirely, never sent as `_eq: null` — Hasura can read that as "match everything". Field names confirmed
 * live, read-only, on 2026-10-05.
 */
suspend fun HardcoverGraphQlClient.findBooks(
    accessToken: String,
    ids: List<Long>,
    asin: String?,
    isbn: String?,
): HardcoverCall<HardcoverFindResult> {
    val params =
        buildList {
            add("\$ids:[Int!]!")
            if (asin != null) add("\$asin:String!")
            if (isbn != null) add("\$isbn:String!")
        }
    val query =
        buildString {
            append("query(").append(params.joinToString(",")).append("){ ")
            append("books(where:{id:{_in:\$ids}}){ $BOOK_FIELDS } ")
            if (asin != null) {
                append("byAsin: editions(where:{asin:{_eq:\$asin}}, limit:1){ $EDITION_FIELDS book{ $BOOK_FIELDS } } ")
            }
            if (isbn != null) {
                append("byIsbn: editions(where:{_or:[{isbn_13:{_eq:\$isbn}},{isbn_10:{_eq:\$isbn}}]}, limit:1){ ")
                append("$EDITION_FIELDS book{ $BOOK_FIELDS } } ")
            }
            append("}")
        }
    val variables =
        buildJsonObject {
            putJsonArray("ids") { ids.forEach { add(it) } }
            asin?.let { put("asin", it) }
            isbn?.let { put("isbn", it) }
        }
    return fetch(accessToken, query, variables, "findBooks") { body ->
        val data = hardcoverJson.decodeFromString<FindResponse>(body).data
        HardcoverFindResult(
            books = data?.books.orEmpty().map { it.toFindBook() },
            byAsin = data?.byAsin?.firstOrNull()?.toFindHit(),
            byIsbn = data?.byIsbn?.firstOrNull()?.toFindHit(),
        )
    }
}

@Serializable
internal data class FindResponse(
    @SerialName("data") val data: FindData? = null,
)

@Serializable
internal data class FindData(
    @SerialName("books") val books: List<FindBookWire> = emptyList(),
    @SerialName("byAsin") val byAsin: List<FindEditionWire> = emptyList(),
    @SerialName("byIsbn") val byIsbn: List<FindEditionWire> = emptyList(),
)

@Serializable
internal data class FindBookWire(
    @SerialName("id") val id: Long,
    @SerialName("title") val title: String = "",
    @SerialName("subtitle") val subtitle: String? = null,
    @SerialName("release_year") val releaseYear: Int? = null,
    @SerialName("image") val image: ImageWire? = null,
    @SerialName("contributions") val contributions: List<RoleCreditWire> = emptyList(),
    @SerialName("default_audio_edition") val defaultAudioEdition: FindEditionWire? = null,
)

@Serializable
internal data class FindEditionWire(
    @SerialName("id") val id: Long,
    @SerialName("asin") val asin: String? = null,
    @SerialName("isbn_13") val isbn13: String? = null,
    @SerialName("isbn_10") val isbn10: String? = null,
    @SerialName("audio_seconds") val audioSeconds: Long? = null,
    @SerialName("edition_format") val editionFormat: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("reading_format_id") val readingFormatId: Int? = null,
    @SerialName("image") val image: ImageWire? = null,
    @SerialName("contributions") val contributions: List<RoleCreditWire> = emptyList(),
    @SerialName("book") val book: FindBookWire? = null,
)

/** A credit with its role: "Author" (null on some records) for an author, "Narrator" and the like otherwise. */
@Serializable
internal data class RoleCreditWire(
    @SerialName("contribution") val contribution: String? = null,
    @SerialName("author") val author: AuthorWire? = null,
)

private fun FindBookWire.toFindBook() =
    HardcoverFindBook(
        id = id,
        title = title,
        subtitle = subtitle?.takeIf { it.isNotBlank() },
        releaseYear = releaseYear,
        authors =
            contributions
                .filter { it.contribution == null || it.contribution.equals(AUTHOR_ROLE, ignoreCase = true) }
                .mapNotNull { it.author?.name },
        imageUrl = image?.url?.takeIf { it.isNotBlank() },
        audioEdition = defaultAudioEdition?.toFindEdition(),
    )

private fun FindEditionWire.toFindEdition() =
    HardcoverFindEdition(
        editionId = id,
        asin = asin?.takeIf { it.isNotBlank() },
        isbn = (isbn13 ?: isbn10)?.takeIf { it.isNotBlank() },
        audioSeconds = audioSeconds?.takeIf { it > 0 },
        editionFormat = editionFormat,
        releaseDate = releaseDate?.takeIf { it.isNotBlank() },
        imageUrl = image?.url?.takeIf { it.isNotBlank() },
        narrators =
            contributions
                .filter { it.contribution.equals(NARRATOR_ROLE, ignoreCase = true) }
                .mapNotNull { it.author?.name },
        isAudiobook = readingFormatId == AUDIOBOOK_READING_FORMAT,
    )

private fun FindEditionWire.toFindHit(): HardcoverFindHit? =
    book?.let {
        HardcoverFindHit(toFindEdition(), it.toFindBook())
    }
