package com.calypsan.listenup.server.hardcover

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A scripted Hardcover catalogue for #1542, answering exactly the queries ListenUp sends: `me`, edition
 * by ASIN and by ISBN, books by title, a book's details (description, tags, series, contributions) and
 * authors by name or id. Every request lands in [asked] as (operation, bearer token). A token in
 * [rejected] gets a 401 whatever it asks, as Hardcover answers an expired or revoked token; [accounts]
 * says whose each accepted token is for `me`; [unavailable] answers every request with a 502.
 */
class FakeHardcoverCatalog {
    /** An author, with the profile fields the catalogue reads ask for. */
    data class Author(
        val id: Long,
        val name: String,
        val bio: String? = null,
        val imageUrl: String? = null,
    )

    /** A book's place in a series. */
    data class Series(
        val id: Long,
        val name: String,
        val position: Double?,
    )

    /** One catalogue book. Its edition id is `id * 10`; tags are (label, votes). */
    data class Book(
        val id: Long,
        val title: String,
        val authors: List<Author>,
        val asin: String? = null,
        val isbn13: String? = null,
        val description: String? = null,
        val genres: List<Pair<String, Int>> = emptyList(),
        val moods: List<Pair<String, Int>> = emptyList(),
        val series: List<Series> = emptyList(),
        val narrators: List<Author> = emptyList(),
        val ratingsCount: Int = 0,
        val subtitle: String? = null,
        /** The audiobook edition's length; null means the book has no default audiobook edition. */
        val audioSeconds: Long? = null,
        val editionFormat: String? = null,
    )

    /** One request: which operation, and whose token asked. */
    data class Asked(
        val operation: String,
        val token: String,
    )

    private val books = CopyOnWriteArrayList<Book>()
    val asked = CopyOnWriteArrayList<Asked>()

    @Volatile
    var rejected: Set<String> = emptySet()

    @Volatile
    var accounts: Map<String, String> = emptyMap()

    @Volatile
    var unavailable: Boolean = false

    /** When set, every request is a 429 whose `Retry-After` is this many milliseconds, in whole seconds. */
    @Volatile
    var throttleAfterMs: Long? = null

    /** Every operation asked, in order. */
    val operations: List<String> get() = asked.map { it.operation }

    fun add(book: Book) {
        books += book
    }

    /** A [HardcoverGraphQlClient] whose every request this fake answers. */
    fun client(): HardcoverGraphQlClient =
        HardcoverGraphQlClient(
            http =
                HttpClient(
                    MockEngine { request ->
                        val body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                        val token = request.headers[HttpHeaders.Authorization].orEmpty().removePrefix("Bearer ")
                        val (status, reply) = handle(body, token)
                        val retryAfter = throttleAfterMs?.takeIf { status == HttpStatusCode.TooManyRequests }
                        val headers =
                            if (retryAfter == null) {
                                headersOf(HttpHeaders.ContentType, "application/json")
                            } else {
                                headersOf(
                                    HttpHeaders.ContentType to listOf("application/json"),
                                    HttpHeaders.RetryAfter to listOf((retryAfter / 1_000).toString()),
                                )
                            }
                        respond(reply, status, headers)
                    },
                ),
            apiBaseUrl = "https://hc.test",
        )

    /** Answers one GraphQL request body sent with [token]. */
    fun handle(
        body: String,
        token: String,
    ): Pair<HttpStatusCode, String> {
        val request = Json.parseToJsonElement(body).jsonObject
        val query = request.getValue("query").jsonPrimitive.content
        val variables = request["variables"]?.jsonObject ?: JsonObject(emptyMap())
        val operation = operationOf(query)
        asked += Asked(operation, token)
        return when {
            token in rejected -> HttpStatusCode.Unauthorized to """{"error":"invalid_token"}"""
            unavailable -> HttpStatusCode.BadGateway to "{}"
            throttleAfterMs != null -> HttpStatusCode.TooManyRequests to "{}"
            else -> HttpStatusCode.OK to answer(operation, variables, token).toString()
        }
    }

    private fun operationOf(query: String): String =
        when {
            "query_type:\"Author\"" in query -> "people_search"
            "narrations:" in query -> "people_details"
            "default_audio_edition{" in query -> "find_books"
            "search(query:" in query -> "search"
            "me {" in query -> "me"
            "cached_tags" in query -> "book_details"
            "asin:{_eq" in query -> "edition_by_asin"
            "isbn_13" in query -> "edition_by_isbn"
            "authors(where:{name" in query -> "authors_named"
            "authors(where:{id" in query -> "author_by_id"
            "title:{_eq" in query -> "books_by_title"
            else -> "unknown"
        }

    private fun answer(
        operation: String,
        variables: JsonObject,
        token: String,
    ): JsonObject =
        buildJsonObject {
            putJsonObject("data") {
                when (operation) {
                    "me" -> {
                        putJsonArray("me") {
                            accounts[token]?.let { name ->
                                addJsonObject {
                                    put("id", 1)
                                    put("username", name)
                                }
                            }
                        }
                    }

                    "edition_by_asin" -> {
                        putJsonArray(
                            "editions",
                        ) { books.firstOrNull { it.asin == variables.text("asin") }?.let { add(editionJson(it)) } }
                    }

                    "edition_by_isbn" -> {
                        putJsonArray(
                            "editions",
                        ) { books.firstOrNull { it.isbn13 == variables.text("isbn") }?.let { add(editionJson(it)) } }
                    }

                    "books_by_title" -> {
                        putJsonArray("books") {
                            books
                                .filter { it.title == variables.text("title") }
                                .sortedByDescending { it.ratingsCount }
                                .forEach { add(bookJson(it)) }
                        }
                    }

                    "search" -> {
                        putJsonObject("search") {
                            putJsonObject("results") {
                                putJsonArray("hits") {
                                    val firstWord = variables.text("query").substringBefore(' ')
                                    books.filter { it.title.contains(firstWord, ignoreCase = true) }.forEach { book ->
                                        addJsonObject {
                                            putJsonObject("document") {
                                                put("id", book.id.toString())
                                                put("title", book.title)
                                                putJsonArray(
                                                    "author_names",
                                                ) { book.authors.forEach { add(JsonPrimitive(it.name)) } }
                                                put("release_year", JsonNull)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    "find_books" -> {
                        val ids =
                            variables
                                .getValue("ids")
                                .jsonArray
                                .map { it.jsonPrimitive.long }
                                .toSet()
                        putJsonArray("books") { books.filter { it.id in ids }.forEach { add(findBookJson(it)) } }
                        variables["asin"]?.let { asin ->
                            putJsonArray("byAsin") {
                                books.firstOrNull { it.asin == asin.jsonPrimitive.content }?.let {
                                    add(
                                        findEditionJson(it, 2, nestBook = true),
                                    )
                                }
                            }
                        }
                        variables["isbn"]?.let { isbn ->
                            putJsonArray("byIsbn") {
                                books.firstOrNull { it.isbn13 == isbn.jsonPrimitive.content }?.let {
                                    add(
                                        findEditionJson(it, 1, nestBook = true),
                                    )
                                }
                            }
                        }
                    }

                    "book_details" -> {
                        putJsonArray(
                            "books",
                        ) { books.firstOrNull { it.id == variables.number("id") }?.let { add(detailsJson(it)) } }
                    }

                    "authors_named" -> {
                        putJsonArray(
                            "authors",
                        ) { allAuthors().filter { it.name == variables.text("name") }.forEach { add(authorJson(it)) } }
                    }

                    "people_search", "people_details" -> {
                        people(operation, variables)
                    }

                    "author_by_id" -> {
                        putJsonArray(
                            "authors",
                        ) { allAuthors().firstOrNull { it.id == variables.number("id") }?.let { add(authorJson(it)) } }
                    }
                }
            }
        }

    private fun allAuthors(): List<Author> = books.flatMap { it.authors }.distinctBy { it.id }

    /** The two people operations: the author index search, and the batched people read. */
    private fun JsonObjectBuilder.people(
        operation: String,
        variables: JsonObject,
    ) {
        if (operation == "people_search") peopleSearch(variables.text("query")) else peopleDetails(variables)
    }

    /** The author index: everyone whose name contains [query], with their titles and photo. */
    private fun JsonObjectBuilder.peopleSearch(query: String) {
        putJsonObject("search") {
            putJsonObject("results") {
                putJsonArray("hits") {
                    allPeople().filter { it.name.contains(query, ignoreCase = true) }.forEach { person ->
                        addJsonObject {
                            putJsonObject("document") {
                                put("id", person.id.toString())
                                put("name", person.name)
                                putJsonArray("books") { creditedBooks(person).forEach { add(JsonPrimitive(it.title)) } }
                                put("books_count", creditedBooks(person).size)
                                if (person.imageUrl == null) {
                                    put("image", JsonNull)
                                } else {
                                    putJsonObject("image") { put("url", person.imageUrl) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** The batched people read: the people by id, and the credits on editions by ASIN/ISBN and on linked books. */
    private fun JsonObjectBuilder.peopleDetails(variables: JsonObject) {
        val ids =
            variables["ids"]
                ?.jsonArray
                ?.map { it.jsonPrimitive.long }
                ?.toSet()
                .orEmpty()
        val asins =
            variables["asins"]
                ?.jsonArray
                ?.map { it.jsonPrimitive.content }
                ?.toSet()
                .orEmpty()
        val isbns =
            variables["isbns"]
                ?.jsonArray
                ?.map { it.jsonPrimitive.content }
                ?.toSet()
                .orEmpty()
        val bookIds =
            variables["books"]
                ?.jsonArray
                ?.map { it.jsonPrimitive.long }
                ?.toSet()
                .orEmpty()
        putJsonArray("people") { allPeople().filter { it.id in ids }.forEach { add(personJson(it)) } }
        putJsonArray("byIdentifier") {
            books.filter { it.asin in asins || it.isbn13 in isbns }.forEach { book ->
                addJsonObject {
                    put("asin", book.asin)
                    put("isbn_13", book.isbn13)
                    put("isbn_10", JsonNull)
                    put("contributions", creditsJson(book.narrators, "Narrator"))
                    putJsonObject("book") {
                        put("id", book.id)
                        put("contributions", creditsJson(book.authors, null))
                    }
                }
            }
        }
        putJsonArray("byBook") {
            books.filter { it.id in bookIds }.forEach { book ->
                addJsonObject {
                    put("id", book.id)
                    put("contributions", creditsJson(book.authors, null))
                    putJsonArray("editions") {
                        addJsonObject { put("contributions", creditsJson(book.narrators, "Narrator")) }
                    }
                }
            }
        }
    }

    /** Everyone credited on any book, narrators included — Hardcover keeps both in `authors`. */
    private fun allPeople(): List<Author> = books.flatMap { it.authors + it.narrators }.distinctBy { it.id }

    private fun creditedBooks(person: Author): List<Book> =
        books.filter { book -> (book.authors + book.narrators).any { it.id == person.id } }

    /** A person with their role counts: narrations are edition credits, authorships book credits. */
    private fun personJson(person: Author) =
        buildJsonObject {
            put("id", person.id)
            put("name", person.name)
            put("bio", person.bio)
            if (person.imageUrl ==
                null
            ) {
                put("image", JsonNull)
            } else {
                putJsonObject("image") { put("url", person.imageUrl) }
            }
            put("books_count", creditedBooks(person).size)
            putJsonObject("narrations") {
                putJsonObject("aggregate") { put("count", books.count { b -> b.narrators.any { it.id == person.id } }) }
            }
            putJsonObject("authorships") {
                putJsonObject("aggregate") { put("count", books.count { b -> b.authors.any { it.id == person.id } }) }
            }
        }

    private fun creditsJson(
        people: List<Author>,
        role: String?,
    ) = buildJsonArray {
        people.forEach { person ->
            addJsonObject {
                put("contribution", role)
                put("author", personJson(person))
            }
        }
    }

    /** A book as Find's batched read asks for it, with its default audiobook edition when it has one. */
    private fun findBookJson(book: Book): JsonObject =
        buildJsonObject {
            put("id", book.id)
            put("title", book.title)
            put("subtitle", book.subtitle)
            put("release_year", JsonNull)
            put("image", JsonNull)
            putJsonArray("contributions") {
                book.authors.forEach { a ->
                    addJsonObject {
                        put("contribution", "Author")
                        putJsonObject("author") { put("name", a.name) }
                    }
                }
            }
            if (book.audioSeconds ==
                null
            ) {
                put("default_audio_edition", JsonNull)
            } else {
                put("default_audio_edition", findEditionJson(book, 2))
            }
        }

    /** The book's edition as Find reads it: id `book.id * 10`, its identifiers, length and credits by role. */
    private fun findEditionJson(
        book: Book,
        readingFormat: Int,
        nestBook: Boolean = false,
    ): JsonObject =
        buildJsonObject {
            put("id", book.id * 10)
            put("asin", book.asin)
            put("isbn_13", book.isbn13)
            put("isbn_10", JsonNull)
            put("audio_seconds", book.audioSeconds)
            put("edition_format", book.editionFormat)
            put("release_date", JsonNull)
            put("reading_format_id", readingFormat)
            put("image", JsonNull)
            putJsonArray("contributions") {
                book.authors.forEach { a ->
                    addJsonObject {
                        put("contribution", "Author")
                        putJsonObject("author") { put("name", a.name) }
                    }
                }
                book.narrators.forEach { n ->
                    addJsonObject {
                        put("contribution", "Narrator")
                        putJsonObject("author") { put("name", n.name) }
                    }
                }
            }
            if (nestBook) put("book", findBookJson(book))
        }

    private fun editionJson(book: Book) =
        buildJsonObject {
            put("id", book.id * 10)
            put("reading_format_id", 2)
            put("book", bookJson(book))
        }

    private fun bookJson(book: Book) =
        buildJsonObject {
            put("id", book.id)
            put("title", book.title)
            if (book.ratingsCount > 0) put("rating", 4.5) else put("rating", JsonNull)
            put("ratings_count", book.ratingsCount)
            put("release_year", JsonNull)
            put("default_audio_edition_id", book.id * 10)
            putJsonArray("contributions") {
                book.authors.forEach { a ->
                    addJsonObject { putJsonObject("author") { put("name", a.name) } }
                }
            }
        }

    private fun detailsJson(book: Book) =
        buildJsonObject {
            put("id", book.id)
            put("description", book.description)
            putJsonObject("cached_tags") {
                put("Genre", tagsJson(book.genres))
                put("Mood", tagsJson(book.moods))
            }
            putJsonArray("book_series") {
                book.series.forEach { s ->
                    addJsonObject {
                        put("position", s.position)
                        putJsonObject("series") {
                            put("id", s.id)
                            put("name", s.name)
                        }
                    }
                }
            }
            putJsonArray("contributions") {
                book.authors.forEach { a ->
                    addJsonObject {
                        put("contribution", "Author")
                        put("author", authorJson(a))
                    }
                }
                book.narrators.forEach { n ->
                    addJsonObject {
                        put("contribution", "Narrator")
                        put("author", authorJson(n))
                    }
                }
            }
        }

    private fun tagsJson(tags: List<Pair<String, Int>>): JsonArray =
        buildJsonArray {
            tags.forEach { (label, votes) ->
                addJsonObject {
                    put("tag", label)
                    put("count", votes)
                }
            }
        }

    private fun authorJson(author: Author) =
        buildJsonObject {
            put("id", author.id)
            put("name", author.name)
            put("bio", author.bio)
            if (author.imageUrl ==
                null
            ) {
                put("image", JsonNull)
            } else {
                putJsonObject("image") { put("url", author.imageUrl) }
            }
        }

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.number(key: String): Long = getValue(key).jsonPrimitive.long
}
