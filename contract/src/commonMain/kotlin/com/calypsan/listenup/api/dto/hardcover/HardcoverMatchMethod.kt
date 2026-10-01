package com.calypsan.listenup.api.dto.hardcover

import kotlinx.serialization.Serializable

/**
 * How a ListenUp book was matched on Hardcover: found by ListenUp ([ASIN], [ISBN], [SEARCH]) or chosen by
 * the user ([MANUAL]). The server stores it per book (`hardcover_book_links.match_method`); a client needs
 * it to put a replaced match back exactly as it was.
 */
@Serializable
enum class HardcoverMatchMethod {
    /** Its Audible ASIN is an edition's ASIN: the exact audiobook edition. */
    ASIN,

    /** Its ISBN is an edition's ISBN-13 or ISBN-10. */
    ISBN,

    /** Exactly one Hardcover book agrees on title and on at least one author. */
    SEARCH,

    /** The user chose the book. */
    MANUAL,
}
