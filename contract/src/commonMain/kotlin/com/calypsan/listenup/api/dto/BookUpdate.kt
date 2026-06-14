package com.calypsan.listenup.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * PATCH payload for [com.calypsan.listenup.api.BookService.updateBook].
 *
 * Every field is nullable — `null` means "don't touch." Each `require(...)`
 * in `init` validates per-field constraints; violations surface as
 * [com.calypsan.listenup.api.error.BookError.InvalidInput] via the RPC guard.
 *
 * Length caps mirror SQLite column definitions in `books` (V4 / V18 migrations);
 * `publishYear` validation is the same range the scanner accepts.
 */
@Serializable
@SerialName("BookUpdate")
data class BookUpdate(
    @SerialName("title") val title: String? = null,
    @SerialName("sortTitle") val sortTitle: String? = null,
    @SerialName("subtitle") val subtitle: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("publisher") val publisher: String? = null,
    @SerialName("publishYear") val publishYear: Int? = null,
    @SerialName("language") val language: String? = null,
    @SerialName("isbn") val isbn: String? = null,
    @SerialName("asin") val asin: String? = null,
    @SerialName("abridged") val abridged: Boolean? = null,
    /**
     * The book's effective "added date" as epoch milliseconds — the value the
     * library's *Recently Added* ordering sorts on (persisted server-side as the
     * book's `createdAt`). `null` means "don't touch"; a positive value re-stamps
     * the added date so the book re-sorts.
     */
    @SerialName("addedAt") val addedAt: Long? = null,
) {
    init {
        title?.let { require(it.isNotBlank() && it.length <= MAX_TITLE) { "title must be 1..$MAX_TITLE chars" } }
        sortTitle?.let { require(it.length <= MAX_TITLE) { "sortTitle must be <= $MAX_TITLE chars" } }
        subtitle?.let { require(it.length <= MAX_SUBTITLE) { "subtitle must be <= $MAX_SUBTITLE chars" } }
        description?.let { require(it.length <= MAX_DESCRIPTION) { "description must be <= $MAX_DESCRIPTION chars" } }
        publisher?.let { require(it.length <= MAX_PUBLISHER) { "publisher must be <= $MAX_PUBLISHER chars" } }
        publishYear?.let { require(it in MIN_YEAR..MAX_YEAR) { "publishYear must be in $MIN_YEAR..$MAX_YEAR" } }
        language?.let { require(it.length <= MAX_LANGUAGE) { "language must be <= $MAX_LANGUAGE chars" } }
        isbn?.let { require(it.length <= MAX_ISBN) { "isbn must be <= $MAX_ISBN chars" } }
        asin?.let { require(it.length <= MAX_ASIN) { "asin must be <= $MAX_ASIN chars" } }
        addedAt?.let { require(it > 0) { "addedAt must be a positive epoch-millis value" } }
    }

    companion object {
        const val MAX_TITLE = 500
        const val MAX_SUBTITLE = 500
        const val MAX_DESCRIPTION = 10_000
        const val MAX_PUBLISHER = 200
        const val MAX_LANGUAGE = 20
        const val MAX_ISBN = 20
        const val MAX_ASIN = 20
        const val MIN_YEAR = -3000
        const val MAX_YEAR = 9999
    }
}
