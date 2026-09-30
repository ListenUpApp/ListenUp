package com.calypsan.listenup.server.hardcover

import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Hardcover's reading statuses (`user_books.status_id`) that ListenUp writes. */
object HardcoverStatus {
    /** "Currently Reading". */
    const val READING: Int = 2

    /** "Read". */
    const val READ: Int = 3
}

/** One read of a book on Hardcover (`user_book_reads`). Dates are Hardcover's `YYYY-MM-DD` strings. */
data class HardcoverRead(
    val id: Long,
    val startedAt: String?,
    val finishedAt: String?,
    val progressSeconds: Long?,
)

/** The user's shelf entry for one Hardcover book (`user_books`), with its reads oldest first. */
data class HardcoverUserBook(
    val id: Long,
    val statusId: Int,
    val reads: List<HardcoverRead>,
) {
    /** The newest read with no finish date — the one listening continues — or null. */
    val openRead: HardcoverRead? get() = reads.lastOrNull { it.finishedAt == null }
}

/**
 * The user's own library on Hardcover: one book's shelf entry, and the writes push needs. A face on
 * [HardcoverGraphQlClient]'s transport — same client, same [HardcoverCall] classification — kept apart
 * so that class stays about the catalog. Dates are calendar dates; the caller picks the user's zone.
 */
class HardcoverUserBooks(
    private val graphQl: HardcoverGraphQlClient,
) {
    /** The user's shelf entry for [hcBookId], or null when the book isn't on their shelf. */
    suspend fun userBookFor(
        accessToken: String,
        hcBookId: Long,
    ): HardcoverCall<HardcoverUserBook?> =
        graphQl.fetch(
            accessToken,
            USER_BOOK_QUERY,
            buildJsonObject { put("bookId", hcBookId) },
            "userBookFor",
        ) { body ->
            hardcoverJson
                .decodeFromString<UserBooksResponse>(body)
                .data
                ?.me
                ?.firstOrNull()
                ?.userBooks
                ?.firstOrNull()
                ?.let { shelf ->
                    HardcoverUserBook(
                        id = shelf.id,
                        statusId = shelf.statusId,
                        reads =
                            shelf.reads.map {
                                HardcoverRead(
                                    it.id,
                                    it.startedAt,
                                    it.finishedAt,
                                    it.progressSeconds,
                                )
                            },
                    )
                }
        }

    /** Shelves [hcBookId] at [statusId], as [hcEditionId] when one is known. Answers the new entry's id. */
    suspend fun createUserBook(
        accessToken: String,
        hcBookId: Long,
        hcEditionId: Long?,
        statusId: Int,
    ): HardcoverCall<Long> =
        mutate(
            accessToken,
            INSERT_USER_BOOK,
            "insert_user_book",
            buildJsonObject {
                putJsonObject("object") {
                    put("book_id", hcBookId)
                    put("status_id", statusId)
                    hcEditionId?.let { put("edition_id", it) }
                }
            },
        )

    /** Moves shelf entry [userBookId] to [statusId]. */
    suspend fun setStatus(
        accessToken: String,
        userBookId: Long,
        statusId: Int,
    ): HardcoverCall<Unit> =
        mutate(
            accessToken,
            UPDATE_USER_BOOK,
            "update_user_book",
            buildJsonObject {
                put("id", userBookId)
                putJsonObject("object") { put("status_id", statusId) }
            },
        ).map { }

    /** Opens a new read on [userBookId], started on [startedAt] when known. Answers the read's id. */
    suspend fun openRead(
        accessToken: String,
        userBookId: Long,
        startedAt: LocalDate?,
        hcEditionId: Long?,
    ): HardcoverCall<Long> =
        mutate(
            accessToken,
            INSERT_READ,
            "insert_user_book_read",
            buildJsonObject {
                put("userBookId", userBookId)
                putJsonObject("read") {
                    startedAt?.let { put("started_at", it.toString()) }
                    hcEditionId?.let { put("edition_id", it) }
                }
            },
        )

    /** Sets read [readId]'s audiobook position to [progressSeconds]. */
    suspend fun recordProgress(
        accessToken: String,
        readId: Long,
        progressSeconds: Long,
    ): HardcoverCall<Unit> =
        mutate(
            accessToken,
            UPDATE_READ,
            "update_user_book_read",
            buildJsonObject {
                put("id", readId)
                putJsonObject("read") { put("progress_seconds", progressSeconds) }
            },
        ).map { }

    /** Finishes read [readId] on [finishedAt]. */
    suspend fun finishRead(
        accessToken: String,
        readId: Long,
        finishedAt: LocalDate,
    ): HardcoverCall<Unit> =
        mutate(
            accessToken,
            UPDATE_READ,
            "update_user_book_read",
            buildJsonObject {
                put("id", readId)
                putJsonObject("read") { put("finished_at", finishedAt.toString()) }
            },
        ).map { }

    /** Runs one of Hardcover's `{ id error }` mutations; an `error`, or no id, is [HardcoverCall.Failed]. */
    private suspend fun mutate(
        accessToken: String,
        mutation: String,
        field: String,
        variables: JsonObject,
    ): HardcoverCall<Long> {
        val body = graphQl.call(accessToken, mutation, variables, field).valueOr { return it }
        val result = mutationResult(body, field)
        val id = result?.id
        return when {
            result == null -> HardcoverCall.Failed("$field: no result")
            result.error != null -> HardcoverCall.Failed("$field: ${result.error}")
            id == null -> HardcoverCall.Failed("$field: no id")
            else -> HardcoverCall.Ok(id)
        }
    }

    private fun mutationResult(
        body: String,
        field: String,
    ): MutationResultWire? =
        try {
            hardcoverJson
                .parseToJsonElement(body)
                .jsonObject["data"]
                ?.jsonObject
                ?.get(field)
                ?.let { hardcoverJson.decodeFromJsonElement<MutationResultWire>(it) }
        } catch (_: IllegalArgumentException) {
            null
        }

    private companion object {
        const val USER_BOOK_QUERY =
            "query(\$bookId:Int!){ me { user_books(where:{book_id:{_eq:\$bookId}}, limit:1){ " +
                "id status_id user_book_reads(order_by:{id:asc}){ id started_at finished_at progress_seconds } } } }"
        const val INSERT_USER_BOOK =
            "mutation(\$object:UserBookCreateInput!){ insert_user_book(object:\$object){ id error } }"
        const val UPDATE_USER_BOOK =
            "mutation(\$id:Int!,\$object:UserBookUpdateInput!){ update_user_book(id:\$id, object:\$object){ id error } }"
        const val INSERT_READ =
            "mutation(\$userBookId:Int!,\$read:DatesReadInput!){ " +
                "insert_user_book_read(user_book_id:\$userBookId, user_book_read:\$read){ id error } }"
        const val UPDATE_READ =
            "mutation(\$id:Int!,\$read:DatesReadInput!){ update_user_book_read(id:\$id, object:\$read){ id error } }"
    }
}
