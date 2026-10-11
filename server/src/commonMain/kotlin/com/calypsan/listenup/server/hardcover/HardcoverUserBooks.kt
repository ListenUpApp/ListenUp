package com.calypsan.listenup.server.hardcover

import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Hardcover's privacy settings (`privacy_settings`, verified live 2026-10-10): every `user_books` row carries one. */
object HardcoverPrivacy {
    /** "Public" — the only setting whose rating ListenUp imports. 2 is "Followers only", 3 "Private". */
    const val PUBLIC: Int = 1
}

/** Hardcover's reading statuses (`user_books.status_id`) that ListenUp reads or writes. */
object HardcoverStatus {
    /** "Want to Read": the pull puts these on the user's To Read shelf (#1539). */
    const val WANT_TO_READ: Int = 1

    /** "Currently Reading". */
    const val READING: Int = 2

    /** "Read". */
    const val READ: Int = 3
}

/**
 * One read of a book on Hardcover (`user_book_reads`), as far as ListenUp knows it. Dates are
 * Hardcover's `YYYY-MM-DD` strings, passed through untouched. [HardcoverUserBooks.updateRead] sends
 * the whole of it, so a change is always a copy of the read Hardcover last answered.
 */
data class HardcoverRead(
    val id: Long,
    val startedAt: String?,
    val finishedAt: String?,
    val progressSeconds: Long?,
    val editionId: Long? = null,
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
 * A dated, finished read on the user's shelf, as the pull sees it: Hardcover's read id and its finish
 * date (`YYYY-MM-DD`). A read with no finish date is never one of these — ListenUp won't invent a date.
 */
data class HardcoverFinishedRead(
    val id: Long,
    val finishedOn: String,
)

/**
 * One shelf entry the pull (spec B3) found changed: its identity and cursor position ([userBookId],
 * [updatedAt]), its finished reads, and what the reverse match needs — the logged edition's
 * identifiers and the book's title and every contributor's name ([authors], illustrators and
 * translators included: Hardcover gives them no role, and may list one first).
 *
 * [statusId] is the entry's reading status ([HardcoverStatus]): Want to Read entries go on the user's
 * To Read shelf, and an entry that moves to any other status comes off it if Hardcover put it there.
 *
 * [updatedAt] is Hardcover's own `timestamptz` text, opaque: it is handed back verbatim as the next
 * cursor and never parsed, re-rendered or compared locally — Hasura trims trailing fractional zeros
 * (`…19.1+00:00` beside `…19.10654+00:00`), so only Hardcover orders it.
 *
 * [ratingHalfStars] is the user's own Hardcover rating in ListenUp half stars, or null when unrated;
 * [privacySettingId] is the entry's Hardcover privacy ([HardcoverPrivacy]).
 */
data class HardcoverShelfEntry(
    val userBookId: Long,
    val hcBookId: Long,
    val updatedAt: String,
    val statusId: Int,
    val finishedReads: List<HardcoverFinishedRead>,
    val title: String?,
    val authors: List<String>,
    val editionAsin: String?,
    val editionIsbns: List<String>,
    val defaultAudioEditionId: Long?,
    val ratingHalfStars: Int? = null,
    val privacySettingId: Int? = null,
) {
    /** [ratingHalfStars] when the entry is public on Hardcover, else null: a private or followers-only rating is never shared with a library's members. */
    val sharedRatingHalfStars: Int?
        get() = ratingHalfStars.takeIf { privacySettingId == HardcoverPrivacy.PUBLIC }
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
            accessToken = accessToken,
            query = USER_BOOK_QUERY,
            variables = buildJsonObject { put("bookId", hcBookId) },
            label = "userBookFor",
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
                            shelf.reads.map { read ->
                                HardcoverRead(
                                    id = read.id,
                                    startedAt = read.startedAt,
                                    finishedAt = read.finishedAt,
                                    progressSeconds = read.progressSeconds,
                                    editionId = read.editionId,
                                )
                            },
                    )
                }
        }

    /**
     * Up to [limit] of the user's shelf entries changed after the cursor ([after], [afterId]), oldest
     * first by `(updated_at, id)`. [after] is Hardcover's own `updated_at` text, passed back verbatim;
     * Hardcover does the ordering and comparing, and the pair makes entries that share a timestamp
     * page cleanly.
     */
    suspend fun changedSince(
        accessToken: String,
        after: String,
        afterId: Long,
        limit: Int,
    ): HardcoverCall<List<HardcoverShelfEntry>> =
        graphQl.fetch(
            accessToken = accessToken,
            query = CHANGED_SINCE_QUERY,
            variables =
                buildJsonObject {
                    put("after", after)
                    put("afterId", afterId)
                    put("limit", limit)
                },
            label = "changedSince",
        ) { body ->
            hardcoverJson
                .decodeFromString<ChangedUserBooksResponse>(body)
                .data
                ?.me
                ?.firstOrNull()
                ?.userBooks
                .orEmpty()
                .map { it.toEntry() }
        }

    /** Shelves [hcBookId] at [statusId], as [hcEditionId] when one is known. Answers the new entry's id. */
    suspend fun createUserBook(
        accessToken: String,
        hcBookId: Long,
        hcEditionId: Long?,
        statusId: Int,
    ): HardcoverCall<Long> =
        mutate(
            accessToken = accessToken,
            mutation = INSERT_USER_BOOK,
            field = "insert_user_book",
            variables =
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
            accessToken = accessToken,
            mutation = UPDATE_USER_BOOK,
            field = "update_user_book",
            variables =
                buildJsonObject {
                    put("id", userBookId)
                    putJsonObject("object") { put("status_id", statusId) }
                },
        ).map { }

    /**
     * Adds a read to [userBookId], started on [startedAt] when known — finished on [finishedAt] too when
     * given, which is how a history read arrives whole, in one call. Answers the read's id.
     */
    suspend fun openRead(
        accessToken: String,
        userBookId: Long,
        startedAt: LocalDate?,
        hcEditionId: Long?,
        finishedAt: LocalDate? = null,
    ): HardcoverCall<Long> =
        mutate(
            accessToken = accessToken,
            mutation = INSERT_READ,
            field = "insert_user_book_read",
            variables =
                buildJsonObject {
                    put("userBookId", userBookId)
                    putJsonObject("read") {
                        startedAt?.let { put("started_at", it.toString()) }
                        finishedAt?.let { put("finished_at", it.toString()) }
                        hcEditionId?.let { put("edition_id", it) }
                    }
                },
        )

    /**
     * Writes [read]'s whole known state — start, finish, position and edition — to Hardcover. Only
     * what ListenUp doesn't know is left out. Hardcover treats a partial `DatesReadInput` as a patch
     * (seen live, 2026-09-30: a lone `progress_seconds` kept `started_at` and `edition_id`), so the
     * whole read is belt and braces, not a necessity.
     */
    suspend fun updateRead(
        accessToken: String,
        read: HardcoverRead,
    ): HardcoverCall<Unit> =
        mutate(
            accessToken = accessToken,
            mutation = UPDATE_READ,
            field = "update_user_book_read",
            variables =
                buildJsonObject {
                    put("id", read.id)
                    putJsonObject("read") {
                        read.startedAt?.let { put("started_at", it) }
                        read.finishedAt?.let { put("finished_at", it) }
                        read.progressSeconds?.let { put("progress_seconds", it) }
                        read.editionId?.let { put("edition_id", it) }
                    }
                },
        ).map { }

    /**
     * Removes read [readId] from the user's shelf: how ListenUp takes back a read Hardcover made of its own,
     * dated today, for a status change ListenUp sent (`delete_user_book_read`, seen working live 2026-10-01).
     */
    suspend fun deleteRead(
        accessToken: String,
        readId: Long,
    ): HardcoverCall<Unit> =
        mutate(
            accessToken = accessToken,
            mutation = DELETE_READ,
            field = "delete_user_book_read",
            variables = buildJsonObject { put("id", readId) },
        ).map { }

    /** Runs one of Hardcover's `{ id error }` mutations; an `error`, or no id, is [HardcoverCall.Failed]. */
    private suspend fun mutate(
        accessToken: String,
        mutation: String,
        field: String,
        variables: JsonObject,
    ): HardcoverCall<Long> {
        val body =
            graphQl
                .call(accessToken = accessToken, query = mutation, variables = variables, label = field)
                .valueOr { return it }
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
                "id status_id user_book_reads(order_by:{id:asc}){ id started_at finished_at progress_seconds edition_id } } } }"
        const val CHANGED_SINCE_QUERY =
            "query(\$after:timestamptz!,\$afterId:Int!,\$limit:Int!){ me { user_books(" +
                "where:{_or:[{updated_at:{_gt:\$after}},{updated_at:{_eq:\$after},id:{_gt:\$afterId}}]}, " +
                "order_by:[{updated_at:asc},{id:asc}], limit:\$limit){ id book_id status_id rating privacy_setting_id updated_at " +
                "user_book_reads(order_by:{id:asc}){ id finished_at } edition { asin isbn_13 isbn_10 } " +
                "book { id title default_audio_edition_id contributions { author { name } } } } } }"
        const val INSERT_USER_BOOK =
            "mutation(\$object:UserBookCreateInput!){ insert_user_book(object:\$object){ id error } }"
        const val UPDATE_USER_BOOK =
            "mutation(\$id:Int!,\$object:UserBookUpdateInput!){ update_user_book(id:\$id, object:\$object){ id error } }"
        const val INSERT_READ =
            "mutation(\$userBookId:Int!,\$read:DatesReadInput!){ " +
                "insert_user_book_read(user_book_id:\$userBookId, user_book_read:\$read){ id error } }"
        const val DELETE_READ = "mutation(\$id:Int!){ delete_user_book_read(id:\$id){ id } }"
        const val UPDATE_READ =
            "mutation(\$id:Int!,\$read:DatesReadInput!){ update_user_book_read(id:\$id, object:\$read){ id error } }"
    }
}
