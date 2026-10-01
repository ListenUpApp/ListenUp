package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.api.result.AppResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList

/** One answer from [FakeHardcoverLibrary]: status, body and any extra headers. */
data class FakeReply(
    val status: HttpStatusCode,
    val body: String = "{}",
    val headers: Map<String, String> = emptyMap(),
)

/** The date the fake Hardcover stamps on the read it opens by itself: "today". */
const val FAKE_TODAY = "2026-09-30"

/** A rate limiter that never waits. */
class NoWaitRateLimiter : HardcoverRateLimiter() {
    override suspend fun await() = Unit
}

/**
 * A small in-memory Hardcover: one user's shelf (`user_books` with their reads) and a catalog of
 * editions, answering exactly the GraphQL ListenUp sends. [failNext] queues answers served INSTEAD
 * of the real one, one per request, in order — how a test puts a 429 or a 401 in front of any call.
 * Every request's operation lands in [operations], and its operation with its variables in
 * [requests], in order.
 *
 * [readUpdates] decides what `update_user_book_read` does with a `DatesReadInput` field the request
 * leaves out. The real Hardcover patches ([ReadUpdates.PATCH], seen live 2026-09-30); push also
 * survives the worse answer, [ReadUpdates.REPLACE], which nulls every omitted field.
 *
 * Like the real Hardcover (seen live, 2026-09-30), `insert_user_book` at Currently Reading opens a
 * read of its own, dated [FAKE_TODAY] at the shelved edition.
 *
 * Every shelf carries an `updated_at` ([Shelf.updatedAt]), stamped from a counter so it only ever
 * grows, and the changed-since query the pull sends pages by `(updated_at, id)`. Stamps are written
 * the way Hasura writes them, trailing fractional zeros trimmed (`…00.1+00:00`, `…01+00:00`), and
 * compared as instants, as Postgres compares a `timestamptz` — never as strings. Whether a read's
 * insert, update or deletion bumps its shelf is [readChangesTouchShelf]: an update does on the real
 * Hardcover (seen live 2026-09-30), a deletion is unverified, so the pull is tested both ways.
 */
class FakeHardcoverLibrary(
    private val readUpdates: ReadUpdates = ReadUpdates.PATCH,
    private val readChangesTouchShelf: Boolean = true,
) {
    /** How `update_user_book_read` treats the `DatesReadInput` fields a request omits. */
    enum class ReadUpdates {
        /** Omitted fields keep their values. */
        PATCH,

        /** Omitted fields become null: the input replaces the read. */
        REPLACE,
    }

    /** One request as Hardcover received it: the operation and its GraphQL variables. */
    data class Request(
        val operation: String,
        val variables: JsonObject,
    )

    /** One `user_book_reads` row. */
    class Read(
        val id: Long,
        var startedAt: String?,
        var finishedAt: String?,
        var progressSeconds: Long?,
        var editionId: Long? = null,
    )

    /** One `user_books` row. */
    class Shelf(
        val id: Long,
        val bookId: Long,
        var statusId: Int,
        val editionId: Long?,
        val reads: MutableList<Read> = mutableListOf(),
        var updatedAt: String = "",
    )

    /** One catalog edition, with the book fields the lookups return. */
    data class Edition(
        val id: Long,
        val bookId: Long,
        val title: String,
        val authors: List<String>,
        val asin: String? = null,
        val isbn13: String? = null,
        val readingFormatId: Int = 1,
        val defaultAudioEditionId: Long? = null,
        val ratingsCount: Int = 0,
        val releaseYear: Int? = null,
    )

    private val lock = Any()
    private val shelves = mutableListOf<Shelf>()
    private val editions = mutableListOf<Edition>()
    private val scripted = ArrayDeque<FakeReply>()
    private val lostReplies = mutableListOf<String>()
    private var nextId = 5_000L
    private var tick = 0L

    /** A strictly increasing `timestamptz` string, 100 ms apart, its fraction trimmed as Hasura trims it. */
    private fun stamp(): String = hasuraTimestamp(STAMP_BASE.plusMillis(++tick * STAMP_STEP_MS))

    private fun touch(shelf: Shelf) {
        shelf.updatedAt = stamp()
    }

    private fun touchForRead(shelf: Shelf) {
        if (readChangesTouchShelf) touch(shelf)
    }

    /** Every request's operation name, in the order Hardcover received them. */
    val operations = CopyOnWriteArrayList<String>()

    /** Every request, with its variables, in the order Hardcover received them. */
    val requests = CopyOnWriteArrayList<Request>()

    fun addEdition(edition: Edition) = synchronized(lock) { editions += edition }

    fun failNext(reply: FakeReply) = synchronized(lock) { scripted.addLast(reply) }

    /**
     * The next [operation] takes effect on Hardcover, but its answer never reaches ListenUp: the caller
     * sees a gateway timeout, exactly as when the reply is lost on the way back.
     */
    fun loseNextReplyTo(operation: String) = synchronized(lock) { lostReplies += operation }

    fun shelfFor(hcBookId: Long): Shelf? = synchronized(lock) { shelves.firstOrNull { it.bookId == hcBookId } }

    /** Puts [hcBookId] on the shelf at [statusId] with [reads] given as (started_at, finished_at). */
    fun seedShelf(
        hcBookId: Long,
        statusId: Int,
        vararg reads: Pair<String?, String?>,
        editionId: Long? = null,
    ): Shelf =
        synchronized(lock) {
            Shelf(nextId++, hcBookId, statusId, editionId).also { shelf ->
                reads.forEach { (started, finished) -> shelf.reads += Read(nextId++, started, finished, null) }
                touch(shelf)
                shelves += shelf
            }
        }

    /** Appends a read to [hcBookId]'s shelf entry and answers its id. */
    fun seedRead(
        hcBookId: Long,
        startedAt: String?,
        finishedAt: String?,
    ): Long =
        synchronized(lock) {
            val shelf = shelves.first { it.bookId == hcBookId }
            Read(nextId++, startedAt, finishedAt, null)
                .also {
                    shelf.reads += it
                    touchForRead(shelf)
                }.id
        }

    /** Changes read [readId] as the user would on Hardcover's site. */
    fun editRead(
        readId: Long,
        change: (Read) -> Unit,
    ) = synchronized(lock) {
        val shelf = shelves.first { s -> s.reads.any { it.id == readId } }
        change(shelf.reads.first { it.id == readId })
        touchForRead(shelf)
    }

    /** Pins [hcBookId]'s shelf to [updatedAt] (any `timestamptz` text), to put entries on the same instant. */
    fun setUpdatedAt(
        hcBookId: Long,
        updatedAt: String,
    ) = synchronized(lock) { shelves.first { it.bookId == hcBookId }.updatedAt = updatedAt }

    fun deleteShelf(hcBookId: Long) = synchronized(lock) { shelves.removeAll { it.bookId == hcBookId } }

    fun deleteRead(readId: Long) =
        synchronized(lock) {
            shelves.forEach { shelf -> if (shelf.reads.removeAll { it.id == readId }) touchForRead(shelf) }
        }

    /** Runs while a request is in flight, before it is answered: what happens during a Hardcover call. */
    @Volatile
    var whileInFlight: (suspend () -> Unit)? = null

    /** A [HardcoverGraphQlClient] whose every request this fake answers. */
    fun client(): HardcoverGraphQlClient =
        HardcoverGraphQlClient(
            http =
                HttpClient(
                    MockEngine { request ->
                        whileInFlight?.invoke()
                        val reply = handle((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString())
                        respond(
                            reply.body,
                            reply.status,
                            Headers.build {
                                append(HttpHeaders.ContentType, "application/json")
                                reply.headers.forEach { (name, value) -> append(name, value) }
                            },
                        )
                    },
                ),
            apiBaseUrl = "https://hc.test",
        )

    /** Answers one GraphQL request body — a scripted failure first, when one is queued. */
    fun handle(requestBody: String): FakeReply =
        synchronized(lock) {
            val request = Json.parseToJsonElement(requestBody).jsonObject
            val query = request.getValue("query").jsonPrimitive.content
            val variables = request["variables"]?.jsonObject ?: JsonObject(emptyMap())
            val operation = operationOf(query)
            operations += operation
            requests += Request(operation, variables)
            scripted.removeFirstOrNull() ?: answer(operation, variables).let { reply ->
                if (lostReplies.remove(operation)) FakeReply(HttpStatusCode.GatewayTimeout) else reply
            }
        }

    private fun operationOf(query: String): String =
        when {
            "insert_user_book_read(" in query -> "insert_user_book_read"
            "update_user_book_read(" in query -> "update_user_book_read"
            "insert_user_book(" in query -> "insert_user_book"
            "update_user_book(" in query -> "update_user_book"
            "updated_at:{_gt" in query -> "user_books_changed"
            "user_books(" in query -> "user_books"
            "asin:{_eq" in query -> "edition_by_asin"
            "isbn_13" in query -> "edition_by_isbn"
            "search(" in query -> "search"
            "_in:" in query -> "books_by_ids"
            "title:{_eq" in query -> "books_by_title"
            else -> "unknown"
        }

    private fun answer(
        operation: String,
        variables: JsonObject,
    ): FakeReply =
        when (operation) {
            "user_books" -> {
                ok(userBooksJson(variables.long("bookId")))
            }

            "user_books_changed" -> {
                ok(changedJson(variables.string("after"), variables.long("afterId"), variables.int("limit")))
            }

            "insert_user_book" -> {
                val input = variables.obj("object")
                val shelf =
                    Shelf(nextId++, input.long("book_id"), input.int("status_id"), input.longOrNull("edition_id"))
                if (shelf.statusId == HardcoverStatus.READING) {
                    shelf.reads +=
                        Read(
                            nextId++,
                            FAKE_TODAY,
                            finishedAt = null,
                            progressSeconds = null,
                            editionId = shelf.editionId,
                        )
                }
                touch(shelf)
                shelves += shelf
                mutation("insert_user_book", shelf.id)
            }

            "update_user_book" -> {
                shelves.firstOrNull { it.id == variables.long("id") }?.let { shelf ->
                    shelf.statusId = variables.obj("object").int("status_id")
                    touch(shelf)
                    mutation("update_user_book", shelf.id)
                } ?: mutationError("update_user_book", "User book not found")
            }

            "insert_user_book_read" -> {
                shelves.firstOrNull { it.id == variables.long("userBookId") }?.let { shelf ->
                    val input = variables.obj("read")
                    val read =
                        Read(
                            nextId++,
                            input.stringOrNull("started_at"),
                            input.stringOrNull("finished_at"),
                            input.longOrNull("progress_seconds"),
                            input.longOrNull("edition_id"),
                        )
                    shelf.reads += read
                    touchForRead(shelf)
                    mutation("insert_user_book_read", read.id)
                } ?: mutationError("insert_user_book_read", "User book not found")
            }

            "update_user_book_read" -> {
                shelves.flatMap { it.reads }.firstOrNull { it.id == variables.long("id") }?.let { read ->
                    updateRead(read, variables.obj("read"))
                    shelves.first { s -> s.reads.any { it === read } }.let(::touchForRead)
                    mutation("update_user_book_read", read.id)
                } ?: mutationError("update_user_book_read", "Read not found")
            }

            "edition_by_asin" -> {
                ok(editionsJson(editions.filter { it.asin == variables.string("asin") }))
            }

            "edition_by_isbn" -> {
                ok(editionsJson(editions.filter { it.isbn13 == variables.string("isbn") }))
            }

            "books_by_title" -> {
                ok(booksJson(editions.filter { it.title == variables.string("title") }.distinctBy { it.bookId }))
            }

            "search" -> {
                val query = variables.string("query")
                ok(searchJson(editions.filter { it.title.contains(query, ignoreCase = true) }.distinctBy { it.bookId }))
            }

            "books_by_ids" -> {
                val ids =
                    variables
                        .getValue("ids")
                        .jsonArray
                        .map { it.jsonPrimitive.long }
                        .toSet()
                ok(booksJson(editions.filter { it.bookId in ids }.distinctBy { it.bookId }))
            }

            else -> {
                FakeReply(
                    HttpStatusCode.BadRequest,
                    """{"errors":[{"message":"fake Hardcover: unsupported $operation"}]}""",
                )
            }
        }

    private fun updateRead(
        read: Read,
        input: JsonObject,
    ) {
        when (readUpdates) {
            ReadUpdates.PATCH -> {
                if ("started_at" in input) read.startedAt = input.stringOrNull("started_at")
                if ("finished_at" in input) read.finishedAt = input.stringOrNull("finished_at")
                if ("progress_seconds" in input) read.progressSeconds = input.longOrNull("progress_seconds")
                if ("edition_id" in input) read.editionId = input.longOrNull("edition_id")
            }

            ReadUpdates.REPLACE -> {
                read.startedAt = input.stringOrNull("started_at")
                read.finishedAt = input.stringOrNull("finished_at")
                read.progressSeconds = input.longOrNull("progress_seconds")
                read.editionId = input.longOrNull("edition_id")
            }
        }
    }

    private fun userBooksJson(hcBookId: Long): JsonObject =
        buildJsonObject {
            putJsonObject("data") {
                putJsonArray("me") {
                    addJsonObject {
                        putJsonArray("user_books") {
                            shelves.filter { it.bookId == hcBookId }.take(1).forEach { shelf ->
                                addJsonObject {
                                    put("id", shelf.id)
                                    put("status_id", shelf.statusId)
                                    putJsonArray("user_book_reads") {
                                        shelf.reads.sortedBy { it.id }.forEach { read ->
                                            addJsonObject {
                                                put("id", read.id)
                                                put("started_at", read.startedAt)
                                                put("finished_at", read.finishedAt)
                                                put("progress_seconds", read.progressSeconds)
                                                put("edition_id", read.editionId)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

    /**
     * The pull's page: shelves after (after, afterId) by (updated_at, id), with their edition and book.
     * `updated_at` compares as an instant, as Postgres compares a `timestamptz`.
     */
    private fun changedJson(
        after: String,
        afterId: Long,
        limit: Int,
    ): JsonObject {
        val cursor = instantOf(after)
        return buildJsonObject {
            putJsonObject("data") {
                putJsonArray("me") {
                    addJsonObject {
                        putJsonArray("user_books") {
                            shelves
                                .filter {
                                    val at = instantOf(it.updatedAt)
                                    at > cursor || (at == cursor && it.id > afterId)
                                }.sortedWith(compareBy<Shelf>({ instantOf(it.updatedAt) }, { it.id }))
                                .take(limit)
                                .forEach { shelf -> add(changedShelfJson(shelf)) }
                        }
                    }
                }
            }
        }
    }

    private fun changedShelfJson(shelf: Shelf): JsonObject =
        buildJsonObject {
            put("id", shelf.id)
            put("book_id", shelf.bookId)
            put("updated_at", shelf.updatedAt)
            putJsonArray("user_book_reads") {
                shelf.reads.sortedBy { it.id }.forEach { read ->
                    addJsonObject {
                        put("id", read.id)
                        put("finished_at", read.finishedAt)
                    }
                }
            }
            val edition = editions.firstOrNull { it.id == shelf.editionId }
            if (edition == null) {
                put("edition", null as String?)
            } else {
                putJsonObject("edition") {
                    put("asin", edition.asin)
                    put("isbn_13", edition.isbn13)
                    put("isbn_10", null as String?)
                }
            }
            val book = editions.firstOrNull { it.bookId == shelf.bookId }
            if (book == null) put("book", null as String?) else put("book", bookJson(book))
        }

    private fun editionsJson(found: List<Edition>): JsonObject =
        buildJsonObject {
            putJsonObject("data") {
                putJsonArray("editions") {
                    found.take(1).forEach { edition ->
                        addJsonObject {
                            put("id", edition.id)
                            put("reading_format_id", edition.readingFormatId)
                            put("book", bookJson(edition))
                        }
                    }
                }
            }
        }

    private fun booksJson(found: List<Edition>): JsonObject =
        buildJsonObject { putJsonObject("data") { putJsonArray("books") { found.forEach { add(bookJson(it)) } } } }

    /** Hardcover's search answers Typesense documents under `results.hits`, with the id as a string. */
    private fun searchJson(found: List<Edition>): JsonObject =
        buildJsonObject {
            putJsonObject("data") {
                putJsonObject("search") {
                    putJsonObject("results") {
                        put("found", found.size)
                        putJsonArray("hits") {
                            found.forEach { edition ->
                                addJsonObject {
                                    putJsonObject("document") {
                                        put("id", edition.bookId.toString())
                                        put("title", edition.title)
                                        putJsonArray("author_names") { edition.authors.forEach { add(it) } }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

    private fun bookJson(edition: Edition): JsonObject =
        buildJsonObject {
            put("id", edition.bookId)
            put("title", edition.title)
            put("rating", null as Double?)
            put("ratings_count", edition.ratingsCount)
            put("release_year", edition.releaseYear)
            put("default_audio_edition_id", edition.defaultAudioEditionId)
            putJsonArray("contributions") {
                edition.authors.forEach { name -> addJsonObject { putJsonObject("author") { put("name", name) } } }
            }
        }

    private fun mutation(
        field: String,
        id: Long,
    ) = ok(
        buildJsonObject {
            putJsonObject("data") {
                putJsonObject(field) {
                    put("id", id)
                    put("error", null as String?)
                }
            }
        },
    )

    private fun mutationError(
        field: String,
        error: String,
    ) = ok(
        buildJsonObject {
            putJsonObject("data") {
                putJsonObject(field) {
                    put("id", null as Long?)
                    put("error", error)
                }
            }
        },
    )

    private fun ok(json: JsonObject) = FakeReply(HttpStatusCode.OK, json.toString())

    private fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject

    private fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.long

    private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int

    private fun JsonObject.longOrNull(key: String): Long? = get(key)?.jsonPrimitive?.longOrNull

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.stringOrNull(key: String): String? = get(key)?.jsonPrimitive?.contentOrNull

    private companion object {
        val STAMP_BASE: Instant = Instant.parse("2026-09-30T00:00:00Z")
        const val STAMP_STEP_MS = 100L

        fun instantOf(timestamptz: String): Instant = OffsetDateTime.parse(timestamptz).toInstant()
    }
}

/**
 * [instant] as Hasura writes a `timestamptz`: `+00:00`, and the fraction's trailing zeros trimmed, the
 * whole fraction gone when it is zero (`…19.1+00:00`, `…19.10654+00:00`, `…19+00:00`; seen live 2026-09-30).
 */
fun hasuraTimestamp(instant: Instant): String {
    val seconds = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC).format(instant)
    val fraction = (instant.nano / 1_000).toString().padStart(6, '0').trimEnd('0')
    return if (fraction.isEmpty()) "$seconds+00:00" else "$seconds.$fraction+00:00"
}

/** A [HardcoverPullRequests] that records what it is asked and pulls nothing. */
class RecordingPullRequests : HardcoverPullRequests {
    val syncNowCalls = CopyOnWriteArrayList<String>()
    val staleChecks = CopyOnWriteArrayList<String>()
    val matchChanges = CopyOnWriteArrayList<Pair<String, String>>()
    var syncNowResult: AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun syncNow(userId: String): AppResult<Unit> {
        syncNowCalls += userId
        return syncNowResult
    }

    override suspend fun syncIfStale(userId: String) {
        staleChecks += userId
    }

    override suspend fun onMatchChanged(
        userId: String,
        bookId: String,
    ) {
        matchChanges += userId to bookId
    }
}
