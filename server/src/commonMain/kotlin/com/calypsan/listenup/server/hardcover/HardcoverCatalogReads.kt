package com.calypsan.listenup.server.hardcover

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** One community tag on a Hardcover book — a genre or a mood — and how many readers applied it. */
data class HardcoverTag(
    val name: String,
    val count: Int,
)

/** A Hardcover book's place in a series; [position] is Hardcover's number, null when it gives none. */
data class HardcoverSeriesPlacement(
    val seriesId: Long,
    val name: String,
    val position: Double?,
) {
    /** [position] as ListenUp spells a sequence: `"1"` for a whole number, `"1.5"` otherwise. */
    val sequence: String?
        get() = position?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }
}

/** An author's Hardcover profile: what fills a missing bio or photo. */
data class HardcoverAuthorProfile(
    val id: Long,
    val name: String,
    val bio: String?,
    val imageUrl: String?,
)

/** What the metadata source reads off one Hardcover book (#1542). [authors] leaves out narrators and other credits. */
data class HardcoverBookDetails(
    val hcBookId: Long,
    val description: String?,
    val genres: List<HardcoverTag>,
    val moods: List<HardcoverTag>,
    val series: List<HardcoverSeriesPlacement>,
    val authors: List<HardcoverAuthorProfile>,
)

/** `cached_tags` categories and a tag's keys, as confirmed live in the plan's Task 0. */
internal const val GENRE_CATEGORY = "Genre"
internal const val MOOD_CATEGORY = "Mood"
internal const val TAG_LABEL_KEY = "tag"
internal const val TAG_COUNT_KEY = "count"

private const val AUTHOR_FIELDS = "id name bio image{ url }"
private const val BOOK_DETAILS_QUERY =
    "query(\$id:Int!){ books(where:{id:{_eq:\$id}}, limit:1){ id description cached_tags " +
        "book_series{ position series{ id name } } contributions{ contribution author{ $AUTHOR_FIELDS } } } }"
private const val AUTHORS_NAMED_QUERY =
    "query(\$name:String!){ authors(where:{name:{_eq:\$name}}, order_by:{books_count:desc}, limit:5){ $AUTHOR_FIELDS } }"
private const val AUTHOR_BY_ID_QUERY = "query(\$id:Int!){ authors(where:{id:{_eq:\$id}}, limit:1){ $AUTHOR_FIELDS } }"

/** [hcBookId]'s description, genre and mood tags, series and authors; `Ok(null)` when Hardcover has no such book. */
suspend fun HardcoverGraphQlClient.bookDetails(
    accessToken: String,
    hcBookId: Long,
): HardcoverCall<HardcoverBookDetails?> =
    fetch(accessToken, BOOK_DETAILS_QUERY, buildJsonObject { put("id", hcBookId) }, "bookDetails") { body ->
        hardcoverJson
            .decodeFromString<BookDetailsResponse>(body)
            .data
            ?.books
            ?.firstOrNull()
            ?.toDetails()
    }

/** Up to five authors named exactly [name], most-published first. */
suspend fun HardcoverGraphQlClient.authorsNamed(
    accessToken: String,
    name: String,
): HardcoverCall<List<HardcoverAuthorProfile>> =
    fetch(accessToken, AUTHORS_NAMED_QUERY, buildJsonObject { put("name", name) }, "authorsNamed") { body ->
        hardcoverJson
            .decodeFromString<AuthorsResponse>(body)
            .data
            ?.authors
            .orEmpty()
            .map { it.toProfile() }
    }

/** The author with Hardcover id [authorId], or `Ok(null)`. */
suspend fun HardcoverGraphQlClient.authorById(
    accessToken: String,
    authorId: Long,
): HardcoverCall<HardcoverAuthorProfile?> =
    fetch(accessToken, AUTHOR_BY_ID_QUERY, buildJsonObject { put("id", authorId) }, "authorById") { body ->
        hardcoverJson
            .decodeFromString<AuthorsResponse>(body)
            .data
            ?.authors
            ?.firstOrNull()
            ?.toProfile()
    }

internal fun BookDetailsWire.toDetails(): HardcoverBookDetails =
    HardcoverBookDetails(
        hcBookId = id,
        description = description?.trim()?.takeIf { it.isNotEmpty() },
        genres = cachedTagsIn(cachedTags, GENRE_CATEGORY),
        moods = cachedTagsIn(cachedTags, MOOD_CATEGORY),
        series =
            bookSeries.mapNotNull { placement ->
                placement.series?.let { HardcoverSeriesPlacement(it.id, it.name, placement.position) }
            },
        authors =
            contributions
                .filter { it.contribution.isNullOrBlank() || it.contribution.equals("Author", ignoreCase = true) }
                .mapNotNull { it.author?.toProfile() }
                .distinctBy { it.id },
    )

internal fun AuthorProfileWire.toProfile(): HardcoverAuthorProfile =
    HardcoverAuthorProfile(
        id = id,
        name = name,
        bio = bio?.trim()?.takeIf { it.isNotEmpty() },
        imageUrl = image?.url?.takeIf { it.isNotBlank() },
    )

/**
 * The tags under [category] in a book's `cached_tags`. Hasura returns `jsonb` as an object; a string
 * holding one is read the same way. A tag without a label is skipped; one without a count counts zero.
 */
internal fun cachedTagsIn(
    element: JsonElement?,
    category: String,
): List<HardcoverTag> {
    val tags =
        when (element) {
            is JsonObject -> element
            is JsonPrimitive -> element.contentOrNull?.let(::objectOrNull)
            else -> null
        } ?: return emptyList()
    return (tags[category] as? JsonArray).orEmpty().mapNotNull { entry ->
        val tag = entry as? JsonObject ?: return@mapNotNull null
        val label =
            (tag[TAG_LABEL_KEY] as? JsonPrimitive)
                ?.contentOrNull
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
        HardcoverTag(label, (tag[TAG_COUNT_KEY] as? JsonPrimitive)?.intOrNull ?: 0)
    }
}

private fun objectOrNull(text: String): JsonObject? =
    try {
        hardcoverJson.parseToJsonElement(text) as? JsonObject
    } catch (_: IllegalArgumentException) {
        null
    }
