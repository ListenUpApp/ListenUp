package com.calypsan.listenup.server.hardcover

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** A person Hardcover's author index found by name: who, their top titles, how many books, their photo. */
data class HardcoverPersonHit(
    val id: Long,
    val name: String,
    val books: List<String>,
    val booksCount: Int,
    val imageUrl: String?,
)

/**
 * A person as the batched people read sees them: their profile, and how many credits they hold as a narrator
 * (on editions) and as an author (on books). Hardcover's roles are free text and noisy — most narrators carry a
 * handful of "Author" credits too — so these are counts, reported as the source states them.
 */
data class HardcoverPerson(
    val id: Long,
    val name: String,
    val bio: String?,
    val imageUrl: String?,
    val booksCount: Int?,
    val narrations: Int,
    val authorships: Int,
)

/** A credit on an edition or a book: the role as Hardcover spells it, and who. */
data class HardcoverCredit(
    val role: String?,
    val person: HardcoverPerson,
)

/**
 * A library book's edition found by ASIN or ISBN, or a linked Hardcover book's audiobook editions, with every
 * credit on it — edition credits (narrators) and its book's (authors).
 */
data class HardcoverCreditedEdition(
    val asin: String?,
    val isbns: List<String>,
    val bookId: Long?,
    val credits: List<HardcoverCredit>,
)

/** One batched people read: the people asked for by id, and the credits of the library's books. */
data class HardcoverPeopleDetails(
    val people: List<HardcoverPerson>,
    val editions: List<HardcoverCreditedEdition>,
)

/**
 * The spellings of Hardcover's narrator role, confirmed live on 2026-10-06. The role is free text and `_ilike`
 * is refused to API tokens, so the variants are listed.
 */
internal val NARRATOR_ROLES =
    listOf("Narrator", "narrator", "Read by", "Read By", "Reader", "reader", "Narrated by", "Narrated By")

/** Hardcover's `reading_formats` id for audiobooks ("Listened"). */
private const val AUDIO_FORMAT = 2
private const val MAX_PEOPLE_HITS = 5
private const val MAX_AUDIO_EDITIONS_PER_BOOK = 20

private val PERSON_FIELDS =
    """
    id name bio image{ url } books_count
    narrations: contributions_aggregate(where:{contributable_type:{_eq:"Edition"},
      contribution:{_in:${'$'}narrator}}){ aggregate{ count } }
    authorships: contributions_aggregate(where:{contributable_type:{_eq:"Book"},
      _or:[{contribution:{_is_null:true}},{contribution:{_eq:"Author"}}]}){ aggregate{ count } }
    """.trimIndent().replace('\n', ' ')
private val CREDITS = "contributions{ contribution author{ $PERSON_FIELDS } }"
private const val PEOPLE_SEARCH_QUERY =
    "query(\$query:String!){ search(query:\$query, query_type:\"Author\", per_page:10, page:1){ results } }"

/**
 * Up to five people Hardcover's author index finds for [name], best first. Index entries credited on no book
 * (comma-joined credit strings like "Kate Reading, Michael Kramer" are indexed as people) are left out.
 */
suspend fun HardcoverGraphQlClient.searchPeople(
    accessToken: String,
    name: String,
): HardcoverCall<List<HardcoverPersonHit>> =
    fetch(accessToken, PEOPLE_SEARCH_QUERY, buildJsonObject { put("query", name) }, "searchPeople") { body ->
        hardcoverJson
            .decodeFromString<PeopleSearchResponse>(body)
            .data
            ?.search
            ?.results
            ?.hits
            .orEmpty()
            .mapNotNull { it.document.toHit() }
            .filter { it.booksCount > 0 }
            .take(MAX_PEOPLE_HITS)
    }

/**
 * The people with [ids], plus every credit on the editions with [asins] or [isbns] and on the audiobook editions
 * of the books with [bookIds], in one request. A clause with nothing to ask is left out of the query.
 */
suspend fun HardcoverGraphQlClient.peopleDetails(
    accessToken: String,
    ids: List<Long>,
    asins: List<String>,
    isbns: List<String>,
    bookIds: List<Long>,
): HardcoverCall<HardcoverPeopleDetails> {
    val byIdentifier = asins.isNotEmpty() || isbns.isNotEmpty()
    val query =
        buildString {
            append("query(\$narrator:[String!]!")
            if (ids.isNotEmpty()) append(",\$ids:[Int!]!")
            if (byIdentifier) append(",\$asins:[String!]!,\$isbns:[String!]!")
            if (bookIds.isNotEmpty()) append(",\$books:[Int!]!")
            append("){ ")
            if (ids.isNotEmpty()) append("people: authors(where:{id:{_in:\$ids}}){ $PERSON_FIELDS } ")
            if (byIdentifier) {
                append("byIdentifier: editions(where:{_or:[{asin:{_in:\$asins}},{isbn_13:{_in:\$isbns}},")
                append("{isbn_10:{_in:\$isbns}}]}){ asin isbn_13 isbn_10 $CREDITS book{ id $CREDITS } } ")
            }
            if (bookIds.isNotEmpty()) {
                append("byBook: books(where:{id:{_in:\$books}}){ id $CREDITS ")
                append(
                    "editions(where:{reading_format_id:{_eq:$AUDIO_FORMAT}}, limit:$MAX_AUDIO_EDITIONS_PER_BOOK){ $CREDITS } } ",
                )
            }
            append("}")
        }
    val variables =
        buildJsonObject {
            putJsonArray("narrator") { NARRATOR_ROLES.forEach { add(it) } }
            if (ids.isNotEmpty()) putJsonArray("ids") { ids.forEach { add(it) } }
            if (byIdentifier) {
                putJsonArray("asins") { asins.forEach { add(it) } }
                putJsonArray("isbns") { isbns.forEach { add(it) } }
            }
            if (bookIds.isNotEmpty()) putJsonArray("books") { bookIds.forEach { add(it) } }
        }
    return fetch(accessToken, query, variables, "peopleDetails") { body ->
        val data = hardcoverJson.decodeFromString<PeopleDetailsResponse>(body).data
        HardcoverPeopleDetails(
            people = data?.people.orEmpty().map { it.toPerson() },
            editions =
                data?.byIdentifier.orEmpty().map { it.toCredited() } +
                    data?.byBook.orEmpty().flatMap { book ->
                        book.editions.map { edition ->
                            HardcoverCreditedEdition(
                                asin = null,
                                isbns = emptyList(),
                                bookId = book.id,
                                credits = (edition.contributions + book.contributions).toCredits(),
                            )
                        }
                    },
        )
    }
}

@Serializable
internal data class PeopleSearchResponse(
    @SerialName("data") val data: PeopleSearchData? = null,
)

@Serializable
internal data class PeopleSearchData(
    @SerialName("search") val search: PeopleSearchWire? = null,
)

@Serializable
internal data class PeopleSearchWire(
    @SerialName("results") val results: PeopleSearchResultsWire? = null,
)

@Serializable
internal data class PeopleSearchResultsWire(
    @SerialName("hits") val hits: List<PeopleSearchHitWire> = emptyList(),
)

@Serializable
internal data class PeopleSearchHitWire(
    @SerialName("document") val document: PeopleSearchDocumentWire,
)

@Serializable
internal data class PeopleSearchDocumentWire(
    @SerialName("id") val id: JsonPrimitive,
    @SerialName("name") val name: String = "",
    @SerialName("books") val books: List<String> = emptyList(),
    @SerialName("books_count") val booksCount: Int = 0,
    @SerialName("image") val image: ImageWire? = null,
)

@Serializable
internal data class PeopleDetailsResponse(
    @SerialName("data") val data: PeopleDetailsData? = null,
)

@Serializable
internal data class PeopleDetailsData(
    @SerialName("people") val people: List<PersonWire> = emptyList(),
    @SerialName("byIdentifier") val byIdentifier: List<CreditedEditionWire> = emptyList(),
    @SerialName("byBook") val byBook: List<CreditedBookWire> = emptyList(),
)

@Serializable
internal data class PersonWire(
    @SerialName("id") val id: Long,
    @SerialName("name") val name: String = "",
    @SerialName("bio") val bio: String? = null,
    @SerialName("image") val image: ImageWire? = null,
    @SerialName("books_count") val booksCount: Int? = null,
    @SerialName("narrations") val narrations: CountWire? = null,
    @SerialName("authorships") val authorships: CountWire? = null,
)

@Serializable
internal data class CountWire(
    @SerialName("aggregate") val aggregate: CountValueWire? = null,
)

@Serializable
internal data class CountValueWire(
    @SerialName("count") val count: Int = 0,
)

@Serializable
internal data class PersonCreditWire(
    @SerialName("contribution") val contribution: String? = null,
    @SerialName("author") val author: PersonWire? = null,
)

@Serializable
internal data class CreditedEditionWire(
    @SerialName("asin") val asin: String? = null,
    @SerialName("isbn_13") val isbn13: String? = null,
    @SerialName("isbn_10") val isbn10: String? = null,
    @SerialName("contributions") val contributions: List<PersonCreditWire> = emptyList(),
    @SerialName("book") val book: CreditedBookWire? = null,
)

@Serializable
internal data class CreditedBookWire(
    @SerialName("id") val id: Long,
    @SerialName("contributions") val contributions: List<PersonCreditWire> = emptyList(),
    @SerialName("editions") val editions: List<CreditedEditionWire> = emptyList(),
)

private fun PeopleSearchDocumentWire.toHit(): HardcoverPersonHit? =
    id.content.toLongOrNull()?.let {
        HardcoverPersonHit(
            id = it,
            name = name.trim(),
            books = books,
            booksCount = booksCount,
            imageUrl = image?.url?.takeIf { url -> url.isNotBlank() },
        )
    }

internal fun PersonWire.toPerson(): HardcoverPerson =
    HardcoverPerson(
        id = id,
        name = name.trim(),
        bio = bio?.trim()?.takeIf { it.isNotEmpty() },
        imageUrl = image?.url?.takeIf { it.isNotBlank() },
        booksCount = booksCount,
        narrations = narrations?.aggregate?.count ?: 0,
        authorships = authorships?.aggregate?.count ?: 0,
    )

private fun List<PersonCreditWire>.toCredits(): List<HardcoverCredit> =
    mapNotNull { credit -> credit.author?.let { HardcoverCredit(credit.contribution?.trim(), it.toPerson()) } }

private fun CreditedEditionWire.toCredited(): HardcoverCreditedEdition =
    HardcoverCreditedEdition(
        asin = asin?.trim()?.takeIf { it.isNotEmpty() },
        isbns = listOfNotNull(isbn13, isbn10).map { it.trim() }.filter { it.isNotEmpty() },
        bookId = book?.id,
        credits = (contributions + book?.contributions.orEmpty()).toCredits(),
    )
